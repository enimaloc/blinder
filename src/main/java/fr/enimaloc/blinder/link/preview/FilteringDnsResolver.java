package fr.enimaloc.blinder.link.preview;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Résolveur DNS qui refuse toute adresse non autorisée par la politique ; la connexion se fait sur les adresses
 * qu'il a validées : pas de seconde résolution, donc pas de DNS rebinding. La résolution est bornée par un délai
 * (les délais de connexion du client HTTP ne la couvrent pas) : au-delà, elle échoue comme un hôte inconnu.
 */
public class FilteringDnsResolver implements DnsResolver {

    /** Plafond de résolutions simultanées : une résolution qui ne rend pas la main garde son thread un temps. */
    static final int MAX_CONCURRENT = 16;

    /** Partagé par tous les résolveurs : les threads inactifs disparaissent après 30 s. */
    private static final ThreadPoolExecutor RESOLVERS = new ThreadPoolExecutor(0, MAX_CONCURRENT,
            30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "dns-resolver");
        thread.setDaemon(true);
        return thread;
    });

    private final AddressPolicy policy;
    private final DnsResolver delegate;
    private final Duration timeout;

    public FilteringDnsResolver(AddressPolicy policy, Duration timeout) {
        this(policy, SystemDefaultDnsResolver.INSTANCE, timeout);
    }

    public FilteringDnsResolver(AddressPolicy policy, DnsResolver delegate, Duration timeout) {
        this.policy = policy;
        this.delegate = delegate;
        this.timeout = timeout;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = resolveWithin(host);
        for (InetAddress address : addresses) {
            if (!policy.allowed(address)) {
                throw new UnknownHostException("Adresse refusée pour " + host);
            }
        }
        return addresses;
    }

    private InetAddress[] resolveWithin(String host) throws UnknownHostException {
        Future<InetAddress[]> pending;
        try {
            pending = RESOLVERS.submit(() -> delegate.resolve(host));
        } catch (RejectedExecutionException e) {
            throw new UnknownHostException("Trop de résolutions DNS en cours pour " + host);
        }
        try {
            return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new UnknownHostException("Délai de résolution DNS dépassé pour " + host);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new UnknownHostException("Résolution DNS interrompue pour " + host);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownHostException unknown) {
                throw unknown;
            }
            UnknownHostException failure = new UnknownHostException("Résolution DNS impossible pour " + host);
            failure.initCause(e.getCause());
            throw failure;
        }
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return host;
    }
}
