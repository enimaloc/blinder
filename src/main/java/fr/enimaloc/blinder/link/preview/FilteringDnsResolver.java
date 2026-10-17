package fr.enimaloc.blinder.link.preview;

import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Résolveur DNS qui refuse toute adresse non autorisée par la politique ; la connexion se fait sur les adresses
 * qu'il a validées : pas de seconde résolution, donc pas de DNS rebinding. La résolution est bornée par un délai
 * (les délais de connexion du client HTTP ne la couvrent pas) : au-delà, elle échoue comme un hôte inconnu.
 */
@Slf4j
public class FilteringDnsResolver implements DnsResolver {

    /** Plafond de résolutions simultanées : une résolution qui ne rend pas la main garde son thread un temps. */
    static final int MAX_CONCURRENT = 16;

    /** Au plus un avertissement de saturation par minute. */
    static final long SATURATION_WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    /**
     * Pool sans file (au-delà de sa taille, la soumission est refusée aussitôt) et horodatage du dernier
     * avertissement de saturation, pour en émettre au plus un par minute et par pool ; {@code users} nomme, dans
     * cet avertissement, ce qui échoue tant que le pool est saturé.
     */
    record Pool(ThreadPoolExecutor executor, AtomicLong lastSaturationWarn, String users) {

        static Pool of(int max) {
            return of(max, "webhooks et aperçus");
        }

        static Pool of(int max, String users) {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(0, max, 30, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "dns-resolver");
                thread.setDaemon(true);
                return thread;
            });
            return new Pool(executor, new AtomicLong(System.nanoTime() - SATURATION_WARN_INTERVAL_NANOS), users);
        }

        /** {@code true} pour l'appelant qui doit avertir (au plus une fois par intervalle). */
        boolean shouldWarn() {
            long now = System.nanoTime();
            long last = lastSaturationWarn.get();
            return now - last >= SATURATION_WARN_INTERVAL_NANOS && lastSaturationWarn.compareAndSet(last, now);
        }
    }

    /** Partagé par tous les résolveurs : les threads inactifs disparaissent après 30 s. */
    private static final Pool SHARED = Pool.of(MAX_CONCURRENT);

    private final AddressPolicy policy;
    private final DnsResolver delegate;
    private final Duration timeout;
    private final Pool pool;

    public FilteringDnsResolver(AddressPolicy policy, Duration timeout) {
        this(policy, SystemDefaultDnsResolver.INSTANCE, timeout);
    }

    public FilteringDnsResolver(AddressPolicy policy, DnsResolver delegate, Duration timeout) {
        this(policy, delegate, timeout, SHARED);
    }

    /**
     * Résolveur doté de son propre pool de {@code maxConcurrent} résolutions : un afflux d'appels d'un usage
     * (ex. contrôles à la visite) ne sature pas le pool partagé des webhooks et aperçus, et inversement.
     */
    public static FilteringDnsResolver withDedicatedPool(AddressPolicy policy, DnsResolver delegate, Duration timeout,
                                                         int maxConcurrent, String users) {
        return new FilteringDnsResolver(policy, delegate, timeout, Pool.of(maxConcurrent, users));
    }

    FilteringDnsResolver(AddressPolicy policy, DnsResolver delegate, Duration timeout, Pool pool) {
        this.policy = policy;
        this.delegate = delegate;
        this.timeout = timeout;
        this.pool = pool;
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
            pending = pool.executor().submit(() -> delegate.resolve(host));
        } catch (RejectedExecutionException e) {
            warnSaturated();
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

    /** Rend la saturation visible sans inonder les journaux. */
    private void warnSaturated() {
        if (pool.shouldWarn()) {
            log.warn("Pool de résolution DNS saturé ({} résolutions en cours) : {} échouent jusqu'à libération",
                    pool.executor().getActiveCount(), pool.users());
        }
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return host;
    }
}
