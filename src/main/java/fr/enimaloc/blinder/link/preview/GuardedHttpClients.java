package fr.enimaloc.blinder.link.preview;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

/**
 * Client HTTP pour joindre une URL fournie par un utilisateur (anti-SSRF) : la résolution DNS passe par un
 * {@link FilteringDnsResolver} (adresses refusées par la politique → échec) et la connexion se fait sur les
 * adresses qu'il a validées, sans seconde résolution (pas de DNS rebinding). Aucune redirection suivie, ni cookie,
 * ni nouvel essai ; délais de connexion, de lecture et d'attente d'une connexion bornés ; pool de connexions borné.
 */
public final class GuardedHttpClients {

    private GuardedHttpClients() {
    }

    /**
     * @param resolver     résolveur filtrant (il porte la politique d'adresses)
     * @param leaseTimeout attente maximale d'une connexion libre du pool
     * @param maxTotal     connexions simultanées au plus, toutes destinations confondues
     * @param maxPerRoute  connexions simultanées au plus vers une même destination
     */
    public static CloseableHttpClient create(FilteringDnsResolver resolver, Timeout timeout, Timeout leaseTimeout,
                                             String userAgent, int maxTotal, int maxPerRoute) {
        PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(maxPerRoute)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout)
                        .setSocketTimeout(timeout)
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(timeout)
                        .setConnectionRequestTimeout(leaseTimeout)
                        .setRedirectsEnabled(false)
                        .build())
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAutomaticRetries()
                .setUserAgent(userAgent)
                .build();
    }
}
