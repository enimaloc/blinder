package fr.enimaloc.blinder.link.preview;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Récupère les métadonnées d'une page. Le résolveur DNS refuse toute adresse non autorisée et la connexion
 * se fait sur les adresses qu'il a validées : pas de seconde résolution, donc pas de DNS rebinding.
 */
public class PreviewFetcher implements AutoCloseable {

    static final int MAX_BYTES = 512 * 1024;
    static final int MAX_REDIRECTS = 3;
    static final Timeout TIMEOUT = Timeout.ofSeconds(3);
    static final String USER_AGENT = "BlinderPreview/1.0";

    private final CloseableHttpClient client;
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "preview-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    public PreviewFetcher(AddressPolicy policy) {
        watchdog.setRemoveOnCancelPolicy(true);
        DnsResolver resolver = new DnsResolver() {
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
        };
        PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(TIMEOUT)
                        .setSocketTimeout(TIMEOUT)
                        .build())
                .build();
        this.client = HttpClients.custom()
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

    private record Step(URI redirect, PreviewData data) {
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        try {
            client.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    public Optional<PreviewData> fetch(String url) {
        long deadline = System.nanoTime() + TIMEOUT.toNanoseconds();
        try {
            URI current = new URI(url);
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                if (!isHttp(current)) {
                    return Optional.empty();
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return Optional.empty();
                }
                Step step = fetchOnce(current, deadline, remaining);
                if (step.redirect() != null) {
                    current = step.redirect();
                    continue;
                }
                return Optional.ofNullable(step.data());
            }
            return Optional.empty();
        } catch (IOException | URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Une requête ; la connexion est toujours abandonnée (jamais vidée) et la durée totale est bornée. */
    private Step fetchOnce(URI requested, long deadline, long remainingNanos) throws IOException {
        HttpGet get = new HttpGet(requested);
        get.setHeader("Accept", "text/html");
        ScheduledFuture<?> hardAbort = watchdog.schedule(get::abort, remainingNanos, TimeUnit.NANOSECONDS);
        ClassicHttpResponse response = null;
        try {
            response = client.executeOpen(null, get, null);
            int code = response.getCode();
            if (code >= 300 && code < 400) {
                Header location = response.getFirstHeader("Location");
                return location == null ? new Step(null, null)
                        : new Step(requested.resolve(location.getValue()), null);
            }
            HttpEntity entity = response.getEntity();
            if (code != 200 || entity == null) {
                return new Step(null, null);
            }
            ContentType type = ContentType.parseLenient(entity.getContentType());
            if (type == null || !"text/html".equalsIgnoreCase(type.getMimeType())) {
                return new Step(null, null);
            }
            Charset charset = type.getCharset() != null ? type.getCharset() : StandardCharsets.UTF_8;
            byte[] body = readAtMost(entity.getContent(), MAX_BYTES, deadline);
            return new Step(null, PreviewExtractor.extract(new String(body, charset), requested.toString()));
        } finally {
            hardAbort.cancel(false);
            // abandonner AVANT de fermer : la fermeture d'une réponse non abandonnée vide le corps
            get.abort();
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

    static byte[] readAtMost(InputStream in, int max, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while (total < max && (read = in.read(buffer, 0, Math.min(buffer.length, max - total))) != -1) {
            out.write(buffer, 0, read);
            total += read;
            if (System.nanoTime() - deadline > 0) {
                throw new IOException("Délai dépassé");
            }
        }
        return out.toByteArray();
    }
}
