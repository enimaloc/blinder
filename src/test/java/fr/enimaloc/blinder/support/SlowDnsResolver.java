package fr.enimaloc.blinder.support;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/** Résolveur DNS simulé qui répond 127.0.0.1 au bout de {@code delay}. */
public record SlowDnsResolver(Duration delay) implements DnsResolver {

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1})};
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }
}
