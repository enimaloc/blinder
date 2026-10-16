package fr.enimaloc.blinder.link.preview;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Résolveur DNS qui refuse toute adresse non autorisée par la politique ; la connexion se fait sur les adresses
 * qu'il a validées : pas de seconde résolution, donc pas de DNS rebinding.
 */
public class FilteringDnsResolver implements DnsResolver {

    private final AddressPolicy policy;

    public FilteringDnsResolver(AddressPolicy policy) {
        this.policy = policy;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = SystemDefaultDnsResolver.INSTANCE.resolve(host);
        for (InetAddress address : addresses) {
            if (!policy.allowed(address)) {
                throw new UnknownHostException("Adresse refusée pour " + host);
            }
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return host;
    }
}
