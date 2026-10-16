package fr.enimaloc.blinder.notification;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSenderTest {

    record Received(String method, String body, com.sun.net.httpserver.Headers headers) {
    }

    HttpServer server;
    String base;
    final CopyOnWriteArrayList<Received> received = new CopyOnWriteArrayList<>();
    final AtomicInteger hits = new AtomicInteger();
    final WebhookSender sender = new WebhookSender(Clock.systemUTC());

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/ok", ex -> {
            record(ex);
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.createContext("/boom", ex -> {
            record(ex);
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.createContext("/redirect", ex -> {
            record(ex);
            ex.getResponseHeaders().set("Location", "/ok");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/slow", ex -> {
            record(ex);
            try {
                Thread.sleep(9_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        sender.close();
    }

    private void record(HttpExchange ex) throws IOException {
        hits.incrementAndGet();
        received.add(new Received(ex.getRequestMethod(),
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), ex.getRequestHeaders()));
    }

    private NotificationChannel channel(String url, boolean allowPrivate) {
        NotificationChannel channel = new NotificationChannel(null, "c", ChannelType.WEBHOOK, url);
        channel.setSecret("topsecret");
        channel.setAllowPrivateNetwork(allowPrivate);
        return channel;
    }

    private NotificationDelivery delivery(NotificationChannel channel) {
        return new NotificationDelivery(null, channel, null, "test", TriggerType.TEST,
                "{\"event\":\"TEST\",\"text\":\"é\"}", Instant.now());
    }

    private static String hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void signatureMatchesIndependentHmac() throws Exception {
        assertThat(WebhookSignatures.sign("topsecret", 1700000000L, "{\"a\":1}"))
                .isEqualTo(hmac("topsecret", "1700000000.{\"a\":1}"))
                .startsWith("sha256=").hasSize(7 + 64);
    }

    @Test
    void privateAddressRefusedWithoutAnyRequest() {
        NotificationChannel channel = channel(base + "/ok", false);
        SendResult result = sender.send(channel, delivery(channel));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isNotBlank();
        assertThat(hits.get()).isZero();
    }

    @Test
    void deliversSignedRequestWhenPrivateAllowed() throws Exception {
        NotificationChannel channel = channel(base + "/ok", true);
        NotificationDelivery delivery = delivery(channel);
        SendResult result = sender.send(channel, delivery);
        assertThat(result).isEqualTo(new SendResult(true, null));
        Received r = received.getFirst();
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.body()).isEqualTo(delivery.getPayload());
        assertThat(r.headers().getFirst("Content-Type")).startsWith("application/json");
        assertThat(r.headers().getFirst("User-Agent")).isEqualTo("BlinderWebhook/1.0");
        assertThat(r.headers().getFirst("X-Blinder-Event")).isEqualTo("TEST");
        assertThat(r.headers().getFirst("X-Blinder-Delivery")).isEqualTo(delivery.getId().toString());
        String timestamp = r.headers().getFirst("X-Blinder-Timestamp");
        assertThat(Long.parseLong(timestamp)).isCloseTo(Instant.now().getEpochSecond(), org.assertj.core.data.Offset.offset(10L));
        assertThat(r.headers().getFirst("X-Blinder-Signature")).isEqualTo(hmac("topsecret", timestamp + "." + r.body()));
    }

    @Test
    void serverErrorIsFailure() {
        NotificationChannel channel = channel(base + "/boom", true);
        assertThat(sender.send(channel, delivery(channel))).isEqualTo(new SendResult(false, "HTTP 500"));
    }

    @Test
    void redirectIsNotFollowed() {
        NotificationChannel channel = channel(base + "/redirect", true);
        assertThat(sender.send(channel, delivery(channel))).isEqualTo(new SendResult(false, "HTTP 302"));
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void unresponsiveServerTimesOutQuickly() {
        NotificationChannel channel = channel(base + "/slow", true);
        long start = System.nanoTime();
        SendResult result = sender.send(channel, delivery(channel));
        assertThat(result.success()).isFalse();
        assertThat((System.nanoTime() - start) / 1_000_000_000.0).isLessThan(7.0);
    }

    @Test
    void invalidTargetsAreRejectedAtSendTimeWithoutRequest() {
        for (String target : new String[]{"ftp://127.0.0.1/x", "http://user:pw@127.0.0.1:" + server.getAddress().getPort() + "/ok",
                "pas une url", "http:///x"}) {
            NotificationChannel channel = channel(target, true);
            assertThat(sender.send(channel, delivery(channel))).isEqualTo(new SendResult(false, "Cible invalide"));
        }
        assertThat(hits.get()).isZero();
    }

    @Test
    void missingSecretIsFailure() {
        NotificationChannel channel = channel(base + "/ok", true);
        channel.setSecret(null);
        assertThat(sender.send(channel, delivery(channel)).success()).isFalse();
        assertThat(hits.get()).isZero();
    }
}
