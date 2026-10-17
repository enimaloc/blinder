package fr.enimaloc.blinder.link.check;

import fr.enimaloc.blinder.link.preview.AddressPolicy;
import fr.enimaloc.blinder.link.preview.FilteringDnsResolver;
import fr.enimaloc.blinder.link.preview.GuardedHttpClients;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Vérifie, à chaque visite, que la destination d'un lien renvoie l'en-tête attendu : une requête {@code HEAD} vers
 * l'URL exacte de redirection, sans suivre de redirection, bornée à {@link #TIMEOUT} au total. Seule la première
 * réponse compte, quel que soit son statut. Même politique réseau que l'aperçu (adresses publiques uniquement,
 * connexion sur les adresses validées), avec un pool DNS et un pool de connexions propres et bornés : un afflux de
 * contrôles ne prive ni les webhooks ni les aperçus, et une destination lente n'occupe qu'une part du pool.
 * Tout échec (en-tête absent ou différent, délai dépassé, erreur réseau/TLS, adresse refusée) est un refus.
 */
@Slf4j
public class HeaderCheckClient implements AutoCloseable {

    static final Timeout TIMEOUT = Timeout.ofSeconds(3);
    static final String USER_AGENT = "BlinderHeaderCheck/1.0";
    /** Résolutions DNS simultanées au plus (au-delà : refus immédiat). */
    static final int MAX_CONCURRENT_DNS = 32;
    /** Connexions simultanées au plus, toutes destinations confondues, puis vers une même destination. */
    static final int MAX_CONNECTIONS = 64;
    static final int MAX_CONNECTIONS_PER_ROUTE = 16;

    private final CloseableHttpClient client;
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "header-check-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    public HeaderCheckClient(AddressPolicy policy) {
        this(policy, SystemDefaultDnsResolver.INSTANCE);
    }

    HeaderCheckClient(AddressPolicy policy, DnsResolver system) {
        watchdog.setRemoveOnCancelPolicy(true);
        FilteringDnsResolver resolver = FilteringDnsResolver.withDedicatedPool(policy, system, TIMEOUT.toDuration(),
                MAX_CONCURRENT_DNS, "contrôles d'en-tête");
        this.client = GuardedHttpClients.create(resolver, TIMEOUT, USER_AGENT, MAX_CONNECTIONS,
                MAX_CONNECTIONS_PER_ROUTE);
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        try {
            client.close();
        } catch (IOException ignored) {
            // rien à faire
        }
    }

    /**
     * @return {@code true} si la première réponse de {@code url} porte un en-tête {@code headerName} (nom insensible
     * à la casse) dont la valeur, rognée, vaut exactement {@code expectedValue} ; {@code false} dans tout autre cas
     */
    public boolean passes(String url, String headerName, String expectedValue) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            log.debug("Contrôle d'en-tête refusé : URL invalide");
            return false;
        }
        if (!isHttp(uri)) {
            log.debug("Contrôle d'en-tête refusé : schéma non HTTP");
            return false;
        }
        HttpHead head = new HttpHead(uri);
        ScheduledFuture<?> hardAbort;
        try {
            hardAbort = watchdog.schedule(head::abort, TIMEOUT.toNanoseconds(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("Contrôle d'en-tête refusé pour {} : client fermé", uri.getHost());
            return false;
        }
        ClassicHttpResponse response = null;
        try {
            response = client.executeOpen(null, head, null);
            for (Header header : response.getHeaders(headerName)) {
                if (header.getValue() != null && header.getValue().trim().equals(expectedValue)) {
                    return true;
                }
            }
            log.debug("Contrôle d'en-tête refusé pour {} : en-tête {} absent ou différent (statut {})",
                    uri.getHost(), headerName, response.getCode());
            return false;
        } catch (IOException | RuntimeException e) {
            log.debug("Contrôle d'en-tête refusé pour {} : {}", uri.getHost(), e.toString());
            return false;
        } finally {
            hardAbort.cancel(false);
            // abandonner AVANT de fermer : la connexion n'est jamais réutilisée ni vidée
            head.abort();
            if (response != null) {
                try {
                    response.close();
                } catch (IOException ignored) {
                    // connexion déjà abandonnée
                }
            }
        }
    }

    private static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        return uri.getHost() != null && scheme != null
                && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
    }
}
