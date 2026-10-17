package fr.enimaloc.blinder.link.check;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.enimaloc.blinder.link.preview.PublicAddressPolicy;
import fr.enimaloc.blinder.support.SlowDnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderCheckClientTest {

    HttpServer server;
    String base;
    int port;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger hanging = new AtomicInteger();

    final HeaderCheckClient permissive = new HeaderCheckClient(address -> true);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;
        server.createContext("/ok", ex -> respond(ex, 200, Map.of("X-Blinder-Check", List.of("sesame"))));
        server.createContext("/padded", ex -> respond(ex, 200, Map.of("X-Blinder-Check", List.of("  sesame  "))));
        server.createContext("/other", ex -> respond(ex, 200, Map.of("X-Blinder-Check", List.of("autre"))));
        server.createContext("/prefix", ex -> respond(ex, 200, Map.of("X-Blinder-Check", List.of("sesame-plus"))));
        server.createContext("/absent", ex -> respond(ex, 200, Map.of()));
        server.createContext("/many", ex -> respond(ex, 200,
                Map.of("X-Blinder-Check", List.of("premier", "sesame", "dernier"))));
        server.createContext("/error-with-header", ex -> respond(ex, 503,
                Map.of("X-Blinder-Check", List.of("sesame"))));
        server.createContext("/redirect", ex -> respond(ex, 302, Map.of("Location", List.of("/ok"))));
        server.createContext("/redirect-with-header", ex -> respond(ex, 302,
                Map.of("Location", List.of("/absent"), "X-Blinder-Check", List.of("sesame"))));
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(6_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, Map.of("X-Blinder-Check", List.of("sesame")));
        });
        server.createContext("/hang", ex -> {
            hanging.incrementAndGet();
            try {
                Thread.sleep(6_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        permissive.close();
        server.stop(0);
    }

    void respond(HttpExchange ex, int status, Map<String, List<String>> headers) throws IOException {
        requests.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " "
                + ex.getRequestHeaders().getFirst("User-Agent"));
        headers.forEach((name, values) -> values.forEach(v -> ex.getResponseHeaders().add(name, v)));
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    @Test
    void acceptsTheExactValue() {
        assertThat(permissive.passes(base + "/ok", "X-Blinder-Check", "sesame")).isTrue();
    }

    @Test
    void headerNameIsCaseInsensitive() {
        assertThat(permissive.passes(base + "/ok", "x-blinder-check", "sesame")).isTrue();
        assertThat(permissive.passes(base + "/ok", "X-BLINDER-CHECK", "sesame")).isTrue();
    }

    @Test
    void receivedValueIsTrimmed() {
        assertThat(permissive.passes(base + "/padded", "X-Blinder-Check", "sesame")).isTrue();
    }

    @Test
    void valueIsCaseSensitiveAndExact() {
        assertThat(permissive.passes(base + "/ok", "X-Blinder-Check", "Sesame")).isFalse();
        assertThat(permissive.passes(base + "/other", "X-Blinder-Check", "sesame")).isFalse();
        assertThat(permissive.passes(base + "/prefix", "X-Blinder-Check", "sesame")).isFalse();
    }

    @Test
    void refusesWhenTheHeaderIsAbsent() {
        assertThat(permissive.passes(base + "/absent", "X-Blinder-Check", "sesame")).isFalse();
    }

    @Test
    void acceptsWhenOneOfSeveralHeadersMatches() {
        assertThat(permissive.passes(base + "/many", "X-Blinder-Check", "sesame")).isTrue();
        assertThat(permissive.passes(base + "/many", "X-Blinder-Check", "absente")).isFalse();
    }

    @Test
    void onlyTheFirstResponseCountsWhateverItsStatus() {
        assertThat(permissive.passes(base + "/error-with-header", "X-Blinder-Check", "sesame")).isTrue();
        assertThat(permissive.passes(base + "/redirect-with-header", "X-Blinder-Check", "sesame")).isTrue();
    }

    @Test
    void doesNotFollowRedirects() {
        assertThat(permissive.passes(base + "/redirect", "X-Blinder-Check", "sesame")).isFalse();
        assertThat(requests).hasSize(1).first().asString().startsWith("HEAD /redirect ");
    }

    @Test
    void sendsAHeadRequestToTheExactUrlWithItsUserAgent() {
        permissive.passes(base + "/ok?utm_source=x&a=b%20c", "X-Blinder-Check", "sesame");
        assertThat(requests).containsExactly("HEAD /ok?utm_source=x&a=b%20c BlinderHeaderCheck/1.0");
    }

    @Test
    void slowDestinationIsRefusedWithinTheDeadline() {
        long start = System.nanoTime();
        assertThat(permissive.passes(base + "/slow", "X-Blinder-Check", "sesame")).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(4_500));
    }

    @Test
    void slowDnsResolutionIsBoundedByTheDeadline() {
        HeaderCheckClient slowDns = new HeaderCheckClient(address -> true, new SlowDnsResolver(Duration.ofSeconds(6)));
        try {
            long start = System.nanoTime();
            assertThat(slowDns.passes("http://slow.test:" + port + "/ok", "X-Blinder-Check", "sesame")).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(4_500));
        } finally {
            slowDns.close();
        }
    }

    /** Occupe {@code count} contrôles sur /hang et attend que le serveur les ait tous reçus. */
    List<Future<Boolean>> saturate(HeaderCheckClient client, ExecutorService callers, int count) throws Exception {
        List<Future<Boolean>> pending = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            pending.add(callers.submit(() -> client.passes(base + "/hang", "X-Blinder-Check", "sesame")));
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (hanging.get() < count && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        assertThat(hanging.get()).isEqualTo(count);
        return pending;
    }

    @Test
    void saturatedDestinationIsRefusedAtOnceWhileOthersStillWork() throws Exception {
        int perRoute = HeaderCheckClient.MAX_CONNECTIONS_PER_ROUTE;
        ExecutorService callers = Executors.newFixedThreadPool(perRoute);
        try {
            List<Future<Boolean>> pending = saturate(permissive, callers, perRoute);

            long start = System.nanoTime();
            assertThat(permissive.passes(base + "/ok", "X-Blinder-Check", "sesame")).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
            assertThat(requests).isEmpty();

            assertThat(permissive.passes("http://localhost:" + port + "/ok", "X-Blinder-Check", "sesame")).isTrue();
            for (Future<Boolean> f : pending) {
                assertThat(f.get(5, TimeUnit.SECONDS)).isFalse();
            }
            // places libérées : la destination est de nouveau contrôlée
            assertThat(permissive.passes(base + "/ok", "X-Blinder-Check", "sesame")).isTrue();
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void globalCapRefusesEveryDestinationAtOnceWhenFull() throws Exception {
        HeaderCheckClient small = new HeaderCheckClient(address -> true, SystemDefaultDnsResolver.INSTANCE, 2, 2);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            saturate(small, callers, 2);
            long start = System.nanoTime();
            assertThat(small.passes("http://localhost:" + port + "/ok", "X-Blinder-Check", "sesame")).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
            assertThat(requests).isEmpty();
        } finally {
            callers.shutdownNow();
            small.close();
        }
    }

    @Test
    void publicPolicyRefusesLoopbackAndPrivateAddresses() {
        HeaderCheckClient real = new HeaderCheckClient(new PublicAddressPolicy());
        try {
            assertThat(real.passes(base + "/ok", "X-Blinder-Check", "sesame")).isFalse();
            assertThat(real.passes("http://localhost:" + port + "/ok", "X-Blinder-Check", "sesame")).isFalse();
            assertThat(real.passes("http://10.0.0.1/ok", "X-Blinder-Check", "sesame")).isFalse();
            assertThat(real.passes("http://169.254.169.254/latest/meta-data", "X-Blinder-Check", "sesame")).isFalse();
            assertThat(real.passes("http://[::1]:" + port + "/ok", "X-Blinder-Check", "sesame")).isFalse();
        } finally {
            real.close();
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void refusesOtherSchemesAndMalformedUrls() {
        assertThat(permissive.passes("file:///etc/passwd", "X-Blinder-Check", "sesame")).isFalse();
        assertThat(permissive.passes("ftp://127.0.0.1/", "X-Blinder-Check", "sesame")).isFalse();
        assertThat(permissive.passes("not a url", "X-Blinder-Check", "sesame")).isFalse();
    }

    @Test
    void checkAfterCloseIsRefusedWithoutThrowing() {
        HeaderCheckClient closed = new HeaderCheckClient(address -> true);
        closed.close();
        assertThat(closed.passes(base + "/ok", "X-Blinder-Check", "sesame")).isFalse();
    }
}
