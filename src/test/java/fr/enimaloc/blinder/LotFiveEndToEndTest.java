package fr.enimaloc.blinder;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.link.QrCodes;
import fr.enimaloc.blinder.notification.FiredTriggerRepository;
import fr.enimaloc.blinder.notification.NotificationChannel;
import fr.enimaloc.blinder.notification.NotificationChannelRepository;
import fr.enimaloc.blinder.notification.TriggerType;
import fr.enimaloc.blinder.stats.VisitWriter;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lot 5 : jeton créé dans le panel, lien tagué créé par l'API, visite, statistiques par tag, QR, PATCH, DELETE. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class LotFiveEndToEndTest {

    private static final String API = "/_/api/v1";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @Autowired
    NotificationChannelRepository channels;
    @Autowired
    FiredTriggerRepository fired;
    @Autowired
    VisitWriter writer;
    @Autowired
    JsonMapper json;

    @Test
    void adminTokenDrivesTaggedLinkThroughApi() throws Exception {
        data.reset();
        User admin = data.admin("admin");
        Domain domain = data.domain("go.example.com", admin);
        RequestPostProcessor asAdmin = user(new BlinderUserDetails(admin));

        // 1. L'admin crée un jeton dans le panel : le jeton brut n'existe que dans le flash.
        MvcResult created = mvc.perform(post("/_/tokens").with(asAdmin).with(csrf()).param("name", "ci"))
                .andExpect(redirectedUrl("/_/tokens"))
                .andReturn();
        String raw = (String) created.getFlashMap().get("createdToken");
        assertThat(raw).startsWith("bl_");

        // Sans jeton l'API refuse (la session admin ne l'ouvre pas : voir ApiAuthTest).
        mvc.perform(get(API + "/domains")).andExpect(status().isUnauthorized());

        // Un canal sert à l'alerte « chaque visite » (créé par le panel).
        mvc.perform(post("/_/notifications/channels").with(asAdmin).with(csrf())
                        .param("name", "Hook").param("type", "WEBHOOK")
                        .param("target", "http://127.0.0.1:9/hook")
                        .param("allowPrivateNetwork", "true").param("enabled", "true"))
                .andExpect(redirectedUrl("/_/notifications"));
        NotificationChannel channel = channels.findByOwnerOrderByNameAsc(admin).getFirst();

        // 2. L'API crée un lien tagué avec notification « chaque visite » et « épuisé ».
        String body = """
                {"domainId":"%s","slug":"promo","targetUrl":"https://example.com/promo","maxUses":1,
                 "tags":["Campagne"],
                 "notifications":{"eachVisit":true,"exhausted":true,"channelIds":["%s"]}}
                """.formatted(domain.getId(), channel.getId());
        MvcResult post = mvc.perform(api(post(API + "/links"), raw).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode link = json.readTree(post.getResponse().getContentAsString());
        String linkId = link.get("id").asString();
        assertThat(link.get("tags").get(0).asString()).isEqualTo("Campagne");
        assertThat(link.get("notifications").get("eachVisit").asBoolean()).isTrue();
        assertThat(link.get("hasPassword").asBoolean()).isFalse();
        assertThat(post.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith(API + "/links/" + linkId);

        JsonNode tags = json.readTree(mvc.perform(api(get(API + "/tags"), raw)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(tags).hasSize(1);
        assertThat(tags.get(0).get("links").asInt()).isEqualTo(1);
        String tagId = tags.get(0).get("id").asString();

        // 3. Une visite publique redirige ; l'écriture asynchrone est forcée.
        mvc.perform(get("http://go.example.com/promo")).andExpect(status().isFound());
        writer.flush();

        // 4. Les statistiques filtrées par tag comptent la visite.
        mvc.perform(api(get(API + "/stats").param("tag", tagId).param("days", "7"), raw))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.total").value(1))
                .andExpect(jsonPath("$.totals.byOutcome.REDIRECTED").value(1));

        // 5. Le QR PNG est une image lisible, identique à celle du service pour l'URL publique.
        byte[] png = mvc.perform(api(get(API + "/links/" + linkId + "/qr.png").param("size", "256"), raw))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(256);
        assertThat(png).isEqualTo(new QrCodes().png("http://go.example.com/promo", 256));

        // 6. Changer la cible ne réarme pas l'alerte « épuisé » ; changer maxUses la réarme.
        var exhausted = links.findByDomainAndSlug(domain, "promo").orElseThrow().getId();
        assertThat(fired.findByLinkIdAndTriggerType(exhausted, TriggerType.EXHAUSTED)).isPresent();
        mvc.perform(api(patch(API + "/links/" + linkId), raw).content("{\"targetUrl\":\"https://example.com/v2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/v2"));
        assertThat(fired.findByLinkIdAndTriggerType(exhausted, TriggerType.EXHAUSTED)).isPresent();
        mvc.perform(api(patch(API + "/links/" + linkId), raw).content("{\"maxUses\":5}"))
                .andExpect(status().isOk());
        assertThat(fired.findByLinkIdAndTriggerType(exhausted, TriggerType.EXHAUSTED)).isEmpty();

        // 7. Suppression : le lien disparaît, la visite publique est introuvable.
        mvc.perform(api(delete(API + "/links/" + linkId), raw)).andExpect(status().isNoContent());
        mvc.perform(api(get(API + "/links/" + linkId), raw)).andExpect(status().isNotFound());
        mvc.perform(get("http://go.example.com/promo")).andExpect(status().isNotFound());
        mvc.perform(api(get(API + "/domains"), raw)).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    private static MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder builder, String raw) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + raw).contentType(MediaType.APPLICATION_JSON);
    }
}
