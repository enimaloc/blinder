package fr.enimaloc.blinder.link.preview;

import fr.enimaloc.blinder.support.SlowDnsResolver;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

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
}
