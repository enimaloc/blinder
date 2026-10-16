package fr.enimaloc.blinder.link.preview;

import fr.enimaloc.blinder.support.SlowDnsResolver;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilteringDnsResolverTest {

    @Test
    void slowResolutionFailsOnceTheTimeoutElapses() {
        FilteringDnsResolver resolver = new FilteringDnsResolver(address -> true, new SlowDnsResolver(Duration.ofSeconds(5)),
                Duration.ofMillis(200));
        long start = System.nanoTime();
        assertThatThrownBy(() -> resolver.resolve("slow.test"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("DNS");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void resolutionWithinTheTimeoutIsFilteredAndReturned() throws UnknownHostException {
        FilteringDnsResolver permissive = new FilteringDnsResolver(address -> true, new SlowDnsResolver(Duration.ZERO),
                Duration.ofSeconds(2));
        assertThat(permissive.resolve("fast.test")).extracting(InetAddress::getHostAddress).containsExactly("127.0.0.1");

        FilteringDnsResolver strict = new FilteringDnsResolver(new PublicAddressPolicy(), new SlowDnsResolver(Duration.ZERO),
                Duration.ofSeconds(2));
        assertThatThrownBy(() -> strict.resolve("fast.test")).isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("Adresse refusée");
    }

    /** Recherche qui reste bloquée jusqu'à {@code release}, sourde aux interruptions (comme getaddrinfo). */
    static final class StuckDnsResolver implements DnsResolver {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started;
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();

        StuckDnsResolver(int expected) {
            started = new CountDownLatch(expected);
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            started.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // ignoré exprès : une résolution système ne s'interrompt pas
                }
            }
            running.decrementAndGet();
            return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1})};
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }

    @Test
    void saturatedPoolFailsFastWithoutExceedingItsBoundAndWarnsOnce() throws Exception {
        int max = 4;
        FilteringDnsResolver.Pool pool = FilteringDnsResolver.Pool.of(max);
        StuckDnsResolver stuck = new StuckDnsResolver(max);
        FilteringDnsResolver resolver = new FilteringDnsResolver(address -> true, stuck, Duration.ofSeconds(10), pool);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(FilteringDnsResolver.class);
        logs.start();
        logger.addAppender(logs);
        ExecutorService callers = Executors.newFixedThreadPool(max);
        try {
            List<Future<InetAddress[]>> occupying = new ArrayList<>();
            for (int i = 0; i < max; i++) {
                String host = "stuck" + i + ".test";
                occupying.add(callers.submit(() -> resolver.resolve(host)));
            }
            assertThat(stuck.started.await(5, TimeUnit.SECONDS)).isTrue();

            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();
                assertThatThrownBy(() -> resolver.resolve("one-more.test"))
                        .isInstanceOf(UnknownHostException.class)
                        .hasMessageContaining("Trop de résolutions DNS en cours");
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
            }
            assertThat(pool.executor().getPoolSize()).isEqualTo(max);
            assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                    .satisfies(e -> assertThat(e.getFormattedMessage()).contains("saturé"));

            stuck.release.countDown();
            for (Future<InetAddress[]> f : occupying) {
                assertThat(f.get(5, TimeUnit.SECONDS)).hasSize(1);
            }
            assertThat(stuck.maxRunning.get()).isEqualTo(max);
            assertThat(pool.executor().getLargestPoolSize()).isEqualTo(max);
        } finally {
            stuck.release.countDown();
            logger.detachAppender(logs);
            callers.shutdownNow();
            pool.executor().shutdownNow();
        }
    }
}
