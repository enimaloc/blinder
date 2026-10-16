package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.link.preview.FilteringDnsResolver;
import fr.enimaloc.blinder.link.preview.PublicAddressPolicy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Webhook signé (HMAC-SHA256). Deux clients : l'un au résolveur DNS filtrant, l'autre sans filtre pour les canaux
 * {@code allowPrivateNetwork}. Pas de redirection suivie, 5 s au total (plus 5 s au plus de résolution DNS),
 * corps de réponse ignoré.
 */
@Component
public class WebhookSender implements ChannelSender, AutoCloseable {

    static final Timeout TIMEOUT = Timeout.ofSeconds(5);
    /** La résolution DNS n'est couverte par aucun délai du client HTTP : elle a le sien. */
    static final Duration DNS_TIMEOUT = Duration.ofSeconds(5);
    static final String USER_AGENT = "BlinderWebhook/1.0";
    static final String INVALID_TARGET = "Cible invalide";

    private final Clock clock;
    private final CloseableHttpClient filtering;
    private final CloseableHttpClient unfiltered;
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "webhook-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public WebhookSender(Clock clock) {
        this(clock, SystemDefaultDnsResolver.INSTANCE, DNS_TIMEOUT);
    }

    /** Les deux clients bornent la résolution DNS ; seul le premier filtre les adresses. */
    WebhookSender(Clock clock, DnsResolver system, Duration dnsTimeout) {
        this.clock = clock;
        watchdog.setRemoveOnCancelPolicy(true);
        this.filtering = client(PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new FilteringDnsResolver(new PublicAddressPolicy(), system, dnsTimeout)));
        this.unfiltered = client(PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new FilteringDnsResolver(address -> true, system, dnsTimeout)));
    }

    private static CloseableHttpClient client(PoolingHttpClientConnectionManagerBuilder managerBuilder) {
        PoolingHttpClientConnectionManager manager = managerBuilder
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(TIMEOUT)
                        .setSocketTimeout(TIMEOUT)
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(TIMEOUT)
                        .setConnectionRequestTimeout(TIMEOUT)
                        .setRedirectsEnabled(false)
                        .build())
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAutomaticRetries()
                .setUserAgent(USER_AGENT)
                .build();
    }

    @Override
    public ChannelType type() {
        return ChannelType.WEBHOOK;
    }

    @Override
    public SendResult send(NotificationChannel channel, NotificationDelivery delivery) {
        try {
            URI uri = checkedUri(channel.getTarget());
            if (uri == null) {
                return SendResult.failure(INVALID_TARGET);
            }
            if (channel.getSecret() == null || channel.getSecret().isEmpty()) {
                return SendResult.failure("Secret manquant");
            }
            return post(channel.isAllowPrivateNetwork() ? unfiltered : filtering, uri, channel, delivery);
        } catch (Exception e) {
            return SendResult.failure(e);
        }
    }

    /** Revalidation à l'envoi : http(s), hôte présent, pas d'identifiants dans l'URL. */
    static URI checkedUri(String target) {
        if (target == null) {
            return null;
        }
        try {
            URI uri = new URI(target);
            String scheme = uri.getScheme();
            boolean http = scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
            if (!http || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private SendResult post(CloseableHttpClient client, URI uri, NotificationChannel channel,
                            NotificationDelivery delivery) throws IOException {
        String body = delivery.getPayload();
        long timestamp = clock.instant().getEpochSecond();
        HttpPost post = new HttpPost(uri);
        post.setHeader("X-Blinder-Event", delivery.getEvent().name());
        post.setHeader("X-Blinder-Delivery", delivery.getId().toString());
        post.setHeader("X-Blinder-Timestamp", Long.toString(timestamp));
        post.setHeader("X-Blinder-Signature", WebhookSignatures.sign(channel.getSecret(), timestamp, body));
        post.setEntity(new StringEntity(body, ContentType.create("application/json", StandardCharsets.UTF_8)));
        ScheduledFuture<?> hardAbort = watchdog.schedule(post::abort, TIMEOUT.toNanoseconds(), TimeUnit.NANOSECONDS);
        ClassicHttpResponse response = null;
        try {
            response = client.executeOpen(null, post, null);
            int code = response.getCode();
            return code >= 200 && code < 300 ? SendResult.ok() : SendResult.failure("HTTP " + code);
        } finally {
            hardAbort.cancel(false);
            // abandonner AVANT de fermer : la fermeture d'une réponse non abandonnée vide le corps
            post.abort();
            if (response != null) {
                try {
                    response.close();
                } catch (IOException ignored) {
                    // connexion déjà abandonnée
                }
            }
        }
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        for (CloseableHttpClient client : new CloseableHttpClient[]{filtering, unfiltered}) {
            try {
                client.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }
}
