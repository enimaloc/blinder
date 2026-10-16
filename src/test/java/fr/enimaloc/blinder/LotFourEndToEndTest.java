package fr.enimaloc.blinder;

import com.sun.net.httpserver.HttpServer;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.notification.DeliveryDispatcher;
import fr.enimaloc.blinder.notification.NotificationChannel;
import fr.enimaloc.blinder.notification.NotificationChannelRepository;
import fr.enimaloc.blinder.stats.VisitWriter;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lot 4 : canal et lien créés par l'admin, visites, statistiques, CSV et webhooks signés. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class LotFourEndToEndTest {

    record Received(String event, String timestamp, String signature, String body) {
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @Autowired
    NotificationChannelRepository channels;
    @Autowired
    VisitWriter writer;
    @Autowired
    DeliveryDispatcher dispatcher;

    HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void statsAndNotificationsFlow() throws Exception {
        data.reset();
        User admin = data.admin("admin");
        Domain domain = data.domain("go.example.com", admin);
        RequestPostProcessor asAdmin = user(new BlinderUserDetails(admin));

        List<Received> received = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/hook", ex -> {
            received.add(new Received(ex.getRequestHeaders().getFirst("X-Blinder-Event"),
                    ex.getRequestHeaders().getFirst("X-Blinder-Timestamp"),
                    ex.getRequestHeaders().getFirst("X-Blinder-Signature"),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();

        mvc.perform(post("/_/notifications/channels").with(asAdmin).with(csrf())
                        .param("name", "Hook local").param("type", "WEBHOOK")
                        .param("target", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook")
                        .param("allowPrivateNetwork", "true").param("enabled", "true"))
                .andExpect(redirectedUrl("/_/notifications"));
        NotificationChannel channel = channels.findByOwnerOrderByNameAsc(admin).getFirst();

        mvc.perform(post("/_/links").with(asAdmin).with(csrf())
                        .param("domainId", domain.getId().toString())
                        .param("slug", "promo")
                        .param("targetUrl", "https://example.com/promo")
                        .param("enabled", "true")
                        .param("redirectStatus", "302")
                        .param("metaMode", "NONE")
                        .param("maxUses", "2")
                        .param("notifyEachVisit", "true")
                        .param("notifyExhausted", "true")
                        .param("notifyChannelIds", channel.getId().toString()))
                .andExpect(redirectedUrl("/_/"));
        Link link = links.findByDomainAndSlug(domain, "promo").orElseThrow();

        for (int i = 0; i < 2; i++) {
            mvc.perform(get("http://go.example.com/promo").header("Referer", "https://news.example.org/a")
                            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Firefox/130.0"))
                    .andExpect(status().isFound());
        }
        mvc.perform(get("http://go.example.com/promo").header("Referer", "https://news.example.org/a")
                        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Firefox/130.0"))
                .andExpect(status().is4xxClientError());

        writer.flush();
        dispatcher.dispatch(Instant.now());

        assertThat(received).extracting(Received::event).containsExactlyInAnyOrder("VISIT", "VISIT", "EXHAUSTED");
        for (Received r : received) {
            assertThat(r.signature()).isEqualTo(hmac(channel.getSecret(), r.timestamp() + "." + r.body()));
            assertThat(r.body()).doesNotContain("https://example.com/promo").contains("go.example.com");
        }

        mvc.perform(get("/_/links/{id}/stats", link.getId()).with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Redirigée</span> : <span>2</span>")))
                .andExpect(content().string(containsString("Refusée</span> : <span>1</span>")));

        String csv = mvc.perform(get("/_/links/{id}/stats.csv", link.getId()).param("days", "7").with(asAdmin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv.split("\r\n")).hasSize(4);
    }

    /** Calcul indépendant de la signature (pas {@code WebhookSignatures}) : {@code sha256=} + HMAC-SHA256 hex. */
    private static String hmac(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(message.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
