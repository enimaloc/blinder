package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.device.Device;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.notification.ChannelType;
import fr.enimaloc.blinder.notification.LinkNotificationService;
import fr.enimaloc.blinder.notification.LinkNotificationSettings;
import fr.enimaloc.blinder.notification.NotificationChannel;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.tag.Tag;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
@TestPropertySource(properties = "blinder.admin.time-zone=Europe/Paris")
class LinkApiTest {

    private static final String BASE = "/_/api/v1/links";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @Autowired
    LinkNotificationService notifications;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    JsonMapper json;

    User admin;
    String token;
    Domain domain;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        token = data.apiToken(admin, "ci");
        domain = data.domain("go.example.com", admin);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder request, String body) {
        return auth(request).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void minimalCreationGeneratesSlugAndReturnsLocation() throws Exception {
        MvcResult result = mvc.perform(jsonBody(post(BASE),
                        "{\"domainId\":\"" + domain.getId() + "\",\"targetUrl\":\"https://example.org/a\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").isNotEmpty())
                .andExpect(jsonPath("$.domain").value("go.example.com"))
                .andExpect(jsonPath("$.domainId").value(domain.getId().toString()))
                .andExpect(jsonPath("$.targetUrl").value("https://example.org/a"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.redirectStatus").value(302))
                .andExpect(jsonPath("$.metaMode").value("NONE"))
                .andExpect(jsonPath("$.useCount").value(0))
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.tags", hasSize(0)))
                .andExpect(jsonPath("$.ipAllowlist", hasSize(0)))
                .andExpect(jsonPath("$.notifications.exhausted").value(false))
                .andExpect(jsonPath("$.version").isNumber())
                .andExpect(jsonPath("$.createdAt").isString())
                .andReturn();
        JsonNode body = read(result);
        String id = body.get("id").asString();
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(BASE + "/" + id);
        assertThat(body.get("url").asString()).isEqualTo("http://go.example.com/" + body.get("slug").asString());
        assertThat(links.findById(UUID.fromString(id))).isPresent();
    }

    @Test
    void fullCreationRoundTripsEveryField() throws Exception {
        NotificationChannel channel = data.channel(admin, "hook", ChannelType.WEBHOOK, "https://example.com/h");
        Device device = data.device(admin, "phone");
        String body = """
                {"domainId":"%s","slug":"Promo","targetUrl":"https://example.org/full","enabled":true,
                 "notBefore":"2030-01-01T10:00:00+02:00","expiresAt":"2030-06-01T08:30:00+02:00",
                 "ttlMinutes":60,"maxUses":10,"deniedUrl":"https://example.org/denied",
                 "ipAllowlist":["10.0.0.0/8","192.168.1.1"],"deviceIds":["%s"],
                 "redirectStatus":307,"forwardQuery":true,"interstitial":true,"interstitialSeconds":5,
                 "metaMode":"CUSTOM","metaTitle":"Titre","metaDescription":"Desc","metaImageUrl":"https://example.org/i.png",
                 "tags":["Promo","Été"],
                 "notifications":{"exhausted":true,"expired":false,"denials":true,"eachVisit":false,"threshold":5,
                                  "channelIds":["%s"]}}
                """.formatted(domain.getId(), device.getId(), channel.getId());
        MvcResult result = mvc.perform(jsonBody(post(BASE), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("promo"))
                .andExpect(jsonPath("$.ttlMinutes").value(60))
                .andExpect(jsonPath("$.maxUses").value(10))
                .andExpect(jsonPath("$.deniedUrl").value("https://example.org/denied"))
                .andExpect(jsonPath("$.ipAllowlist", contains("10.0.0.0/8", "192.168.1.1")))
                .andExpect(jsonPath("$.deviceProtected").value(true))
                .andExpect(jsonPath("$.deviceIds", contains(device.getId().toString())))
                .andExpect(jsonPath("$.redirectStatus").value(307))
                .andExpect(jsonPath("$.forwardQuery").value(true))
                .andExpect(jsonPath("$.interstitial").value(true))
                .andExpect(jsonPath("$.interstitialSeconds").value(5))
                .andExpect(jsonPath("$.metaMode").value("CUSTOM"))
                .andExpect(jsonPath("$.metaTitle").value("Titre"))
                .andExpect(jsonPath("$.metaDescription").value("Desc"))
                .andExpect(jsonPath("$.metaImageUrl").value("https://example.org/i.png"))
                .andExpect(jsonPath("$.tags", contains("Promo", "Été")))
                .andExpect(jsonPath("$.notifications.exhausted").value(true))
                .andExpect(jsonPath("$.notifications.expired").value(false))
                .andExpect(jsonPath("$.notifications.denials").value(true))
                .andExpect(jsonPath("$.notifications.eachVisit").value(false))
                .andExpect(jsonPath("$.notifications.threshold").value(5))
                .andExpect(jsonPath("$.notifications.channelIds", contains(channel.getId().toString())))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andReturn();
        JsonNode node = read(result);
        OffsetDateTime notBefore = OffsetDateTime.parse(node.get("notBefore").asString());
        OffsetDateTime expiresAt = OffsetDateTime.parse(node.get("expiresAt").asString());
        assertThat(notBefore.toInstant()).isEqualTo(OffsetDateTime.parse("2030-01-01T10:00:00+02:00").toInstant());
        assertThat(expiresAt.toInstant()).isEqualTo(OffsetDateTime.parse("2030-06-01T08:30:00+02:00").toInstant());
        // Renvoyées dans le fuseau de l'admin (Europe/Paris : +01:00 en hiver, +02:00 en été).
        assertThat(notBefore.getOffset().getTotalSeconds()).isEqualTo(3600);
        assertThat(expiresAt.getOffset().getTotalSeconds()).isEqualTo(7200);

        mvc.perform(auth(get(BASE + "/" + node.get("id").asString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notBefore").value(node.get("notBefore").asString()))
                .andExpect(jsonPath("$.tags", contains("Promo", "Été")));
    }

    @Test
    void passwordIsWriteOnly() throws Exception {
        MvcResult result = mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"https://example.org/p","password":"s3cret-pass"}
                        """.formatted(domain.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("s3cret-pass");
        String id = read(result).get("id").asString();
        mvc.perform(auth(get(BASE + "/" + id)))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void foreignLinkIs404() throws Exception {
        User other = data.admin("other");
        Link foreign = data.link(data.domain("other.example.com", other), "theirs", "https://example.org");
        mvc.perform(auth(get(BASE + "/" + foreign.getId())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(jsonBody(patch(BASE + "/" + foreign.getId()), "{\"enabled\":false}"))
                .andExpect(status().isNotFound());
        mvc.perform(auth(delete(BASE + "/" + foreign.getId()))).andExpect(status().isNotFound());
        mvc.perform(auth(post(BASE + "/" + foreign.getId() + "/reset"))).andExpect(status().isNotFound());
        mvc.perform(auth(get(BASE + "/" + foreign.getId() + "/qr.svg"))).andExpect(status().isNotFound());
        assertThat(links.findById(foreign.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void creatingOnForeignDomainIsRejected() throws Exception {
        Domain foreign = data.domain("other.example.com", data.admin("other"));
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"https://example.org/x"}
                        """.formatted(foreign.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.domainId").exists());
    }

    @Test
    void invalidTargetUrlIs400WithFieldError() throws Exception {
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"ftp://x"}
                        """.formatted(domain.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.targetUrl").value("URL http(s) absolue requise"));
    }

    @Test
    void beanValidationErrorsUseApiFieldNames() throws Exception {
        mvc.perform(jsonBody(post(BASE), """
                        {"targetUrl":"","maxUses":0,"notifications":{"threshold":0}}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.domainId").value("Choisissez un domaine"))
                .andExpect(jsonPath("$.errors.targetUrl").exists())
                .andExpect(jsonPath("$.errors.maxUses").exists())
                .andExpect(jsonPath("$.errors['notifications.threshold']").exists());
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"https://example.org","password":"abc"}
                        """.formatted(domain.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("4 caractères minimum"));
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"https://example.org","notifications":{"channelIds":["%s"]}}
                        """.formatted(domain.getId(), UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['notifications.channelIds']").exists());
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"https://example.org","tags":["a,b"]}
                        """.formatted(domain.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.tags").exists());
        assertThat(links.count()).isZero();
    }

    @Test
    void takenSlugIsRejectedOnSlugField() throws Exception {
        data.link(domain, "taken", "https://example.org");
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","slug":"taken","targetUrl":"https://example.org/2"}
                        """.formatted(domain.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.slug").value("Ce slug existe déjà sur ce domaine"));
    }

    @Test
    void patchEnabledKeepsEverythingElse() throws Exception {
        NotificationChannel channel = data.channel(admin, "hook", ChannelType.WEBHOOK, "https://example.com/h");
        Device device = data.device(admin, "phone");
        String id = createAndGetId("""
                {"domainId":"%s","slug":"keep","targetUrl":"https://example.org/k","password":"s3cret-pass",
                 "tags":["alpha","beta"],"deviceIds":["%s"],"ipAllowlist":["10.0.0.1"],"maxUses":3,
                 "notBefore":"2020-01-01T00:00:00Z",
                 "notifications":{"exhausted":true,"threshold":7,"channelIds":["%s"]}}
                """.formatted(domain.getId(), device.getId(), channel.getId()));
        String passwordHash = links.findById(UUID.fromString(id)).orElseThrow().getPasswordHash();

        mvc.perform(jsonBody(patch(BASE + "/" + id), "{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.slug").value("keep"))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.tags", contains("alpha", "beta")))
                .andExpect(jsonPath("$.deviceIds", contains(device.getId().toString())))
                .andExpect(jsonPath("$.deviceProtected").value(true))
                .andExpect(jsonPath("$.ipAllowlist", contains("10.0.0.1")))
                .andExpect(jsonPath("$.maxUses").value(3))
                .andExpect(jsonPath("$.notBefore").isString())
                .andExpect(jsonPath("$.notifications.exhausted").value(true))
                .andExpect(jsonPath("$.notifications.threshold").value(7))
                .andExpect(jsonPath("$.notifications.channelIds", contains(channel.getId().toString())));
        assertThat(links.findById(UUID.fromString(id)).orElseThrow().getPasswordHash()).isEqualTo(passwordHash);

        // Fusion de l'objet imbriqué : seuls les champs présents changent.
        mvc.perform(jsonBody(patch(BASE + "/" + id), "{\"notifications\": {\"threshold\": 9}, \"maxUses\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.exhausted").value(true))
                .andExpect(jsonPath("$.notifications.threshold").value(9))
                .andExpect(jsonPath("$.maxUses").doesNotExist())
                .andExpect(jsonPath("$.tags", contains("alpha", "beta")));
        LinkNotificationSettings settings = notifications.settingsFor(UUID.fromString(id)).orElseThrow();
        assertThat(settings.getVisitThreshold()).isEqualTo(9);
    }

    @Test
    void patchRemovePassword() throws Exception {
        String id = createAndGetId("""
                {"domainId":"%s","targetUrl":"https://example.org/k","password":"s3cret-pass"}
                """.formatted(domain.getId()));
        mvc.perform(jsonBody(patch(BASE + "/" + id), "{\"removePassword\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(false));
        assertThat(links.findById(UUID.fromString(id)).orElseThrow().getPasswordHash()).isNull();
    }

    @Test
    void patchCanChangeSlugAndIgnoresReadOnlyFields() throws Exception {
        String id = createAndGetId("""
                {"domainId":"%s","slug":"old","targetUrl":"https://example.org/k"}
                """.formatted(domain.getId()));
        mvc.perform(jsonBody(patch(BASE + "/" + id),
                        "{\"slug\":\"new\",\"useCount\":42,\"hasPassword\":true,\"status\":\"EXPIRED\",\"url\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("new"))
                .andExpect(jsonPath("$.useCount").value(0))
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void patchWithWrongTypeIs400() throws Exception {
        String id = createAndGetId("""
                {"domainId":"%s","targetUrl":"https://example.org/k"}
                """.formatted(domain.getId()));
        mvc.perform(jsonBody(patch(BASE + "/" + id), "{\"maxUses\": \"lots\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(jsonBody(patch(BASE + "/" + id), "[1, 2]"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void patchRejectsUnknownFields() throws Exception {
        String id = createAndGetId("""
                {"domainId":"%s","targetUrl":"https://example.org/k"}
                """.formatted(domain.getId()));
        mvc.perform(jsonBody(patch(BASE + "/" + id), "{\"enable\": false}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.enable").value("Champ inconnu"));
        mvc.perform(jsonBody(patch(BASE + "/" + id), "{\"notifications\":{\"exhuasted\":true}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['notifications.exhuasted']").value("Champ inconnu"));
        mvc.perform(auth(get(BASE + "/" + id)))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void postAndPutRejectUnknownFields() throws Exception {
        mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","targetUrl":"https://example.org/k","expiresat":"2030-01-01T00:00:00Z"}
                        """.formatted(domain.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.expiresat").value("Champ inconnu"));
        String id = createAndGetId("""
                {"domainId":"%s","targetUrl":"https://example.org/k"}
                """.formatted(domain.getId()));
        mvc.perform(jsonBody(put(BASE + "/" + id), """
                        {"domainId":"%s","targetUrl":"https://example.org/k","maxuses":3}
                        """.formatted(domain.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.maxuses").value("Champ inconnu"));
    }

    @Test
    void putAcceptsTheRepresentationReturnedByGet() throws Exception {
        String id = createAndGetId("""
                {"domainId":"%s","targetUrl":"https://example.org/k","tags":["x"],"maxUses":4}
                """.formatted(domain.getId()));
        String representation = mvc.perform(auth(get(BASE + "/" + id)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(jsonBody(put(BASE + "/" + id), representation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxUses").value(4))
                .andExpect(jsonPath("$.tags[0]").value("x"));
    }

    @Test
    void putReplacesAndChecksVersion() throws Exception {
        MvcResult created = mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","slug":"put","targetUrl":"https://example.org/1","tags":["x"],"maxUses":4}
                        """.formatted(domain.getId())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode node = read(created);
        String id = node.get("id").asString();
        long version = node.get("version").asLong();

        mvc.perform(jsonBody(put(BASE + "/" + id), """
                        {"domainId":"%s","slug":"put","targetUrl":"https://example.org/2","version":%d}
                        """.formatted(domain.getId(), version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetUrl").value("https://example.org/2"))
                .andExpect(jsonPath("$.tags", hasSize(0)))
                .andExpect(jsonPath("$.maxUses").doesNotExist());

        mvc.perform(jsonBody(put(BASE + "/" + id), """
                        {"domainId":"%s","slug":"put","targetUrl":"https://example.org/3","version":%d}
                        """.formatted(domain.getId(), version)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(links.findById(UUID.fromString(id)).orElseThrow().getTargetUrl()).isEqualTo("https://example.org/2");
    }

    @Test
    void notificationChangeBumpsVersionSoConcurrentPatchConflicts() throws Exception {
        MvcResult created = mvc.perform(jsonBody(post(BASE), """
                        {"domainId":"%s","slug":"race","targetUrl":"https://example.org/1"}
                        """.formatted(domain.getId())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode node = read(created);
        String id = node.get("id").asString();
        long version = node.get("version").asLong();

        mvc.perform(jsonBody(patch(BASE + "/" + id), """
                        {"notifications":{"exhausted":true},"version":%d}
                        """.formatted(version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.exhausted").value(true))
                .andExpect(jsonPath("$.version").value(org.hamcrest.Matchers.greaterThan((int) version)));

        mvc.perform(jsonBody(patch(BASE + "/" + id), """
                        {"targetUrl":"https://example.org/2","version":%d}
                        """.formatted(version)))
                .andExpect(status().isConflict());
        assertThat(links.findById(UUID.fromString(id)).orElseThrow().getTargetUrl()).isEqualTo("https://example.org/1");
        assertThat(notifications.settingsFor(UUID.fromString(id)).orElseThrow().isOnExhausted()).isTrue();
    }

    @Test
    void unchangedNotificationsKeepVersion() throws Exception {
        String id = createAndGetId("""
                {"domainId":"%s","slug":"same","targetUrl":"https://example.org/1",
                 "notifications":{"exhausted":true}}
                """.formatted(domain.getId()));
        long version = links.findById(UUID.fromString(id)).orElseThrow().getVersion();
        mvc.perform(jsonBody(patch(BASE + "/" + id), "{\"notifications\":{\"exhausted\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value((int) version));
    }

    @Test
    void listIsPaginatedNewestFirst() throws Exception {
        for (int i = 0; i < 5; i++) {
            createAndGetId("""
                    {"domainId":"%s","slug":"s%d","targetUrl":"https://example.org/%d"}
                    """.formatted(domain.getId(), i, i));
        }
        mvc.perform(auth(get(BASE).param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items[0].slug").value("s4"))
                .andExpect(jsonPath("$.items[1].slug").value("s3"));
        mvc.perform(auth(get(BASE).param("size", "2").param("page", "2")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].slug").value("s0"))
                .andExpect(jsonPath("$.page").value(2));
        mvc.perform(auth(get(BASE).param("size", "500")))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.items", hasSize(5)));
        mvc.perform(auth(get(BASE)))
                .andExpect(jsonPath("$.size").value(50));
        mvc.perform(auth(get(BASE).param("page", "-1"))).andExpect(status().isBadRequest());
        mvc.perform(auth(get(BASE).param("size", "0"))).andExpect(status().isBadRequest());
    }

    @Test
    void listFiltersByDomainTagAndQuery() throws Exception {
        Domain second = data.domain("second.example.com", admin);
        Link a = data.link(domain, "alpha", "https://example.org/100%25-off");
        Link b = data.link(domain, "beta", "https://example.org/b");
        Link c = data.link(second, "gamma_1", "https://example.org/c");
        data.link(data.domain("other.example.com", data.admin("other")), "alpha2", "https://example.org/x");
        Tag tag = data.tag(admin, "promo");
        data.tagLink(a, tag);
        data.tagLink(c, tag);

        mvc.perform(auth(get(BASE)))
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(auth(get(BASE).param("domain", second.getId().toString())))
                .andExpect(jsonPath("$.items[*].slug", contains("gamma_1")));
        mvc.perform(auth(get(BASE).param("tag", tag.getId().toString())))
                .andExpect(jsonPath("$.items[*].slug", containsInAnyOrder("alpha", "gamma_1")));
        mvc.perform(auth(get(BASE).param("tag", tag.getId().toString()).param("domain", domain.getId().toString())))
                .andExpect(jsonPath("$.items[*].slug", contains("alpha")));
        mvc.perform(auth(get(BASE).param("q", "ALP")))
                .andExpect(jsonPath("$.items[*].slug", contains("alpha")));
        mvc.perform(auth(get(BASE).param("q", "example.org/B")))
                .andExpect(jsonPath("$.items[*].slug", contains("beta")));
        // `%` et `_` sont pris littéralement.
        mvc.perform(auth(get(BASE).param("q", "%")))
                .andExpect(jsonPath("$.items[*].slug", contains("alpha")));
        mvc.perform(auth(get(BASE).param("q", "_")))
                .andExpect(jsonPath("$.items[*].slug", contains("gamma_1")));
        mvc.perform(auth(get(BASE).param("tag", data.tag(data.admin("third"), "x").getId().toString())))
                .andExpect(status().isNotFound());
        mvc.perform(auth(get(BASE).param("domain", UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
        assertThat(b).isNotNull();
    }

    @Test
    void deleteThenNotFound() throws Exception {
        String id = createAndGetId("""
                {"domainId":"%s","targetUrl":"https://example.org/d","tags":["t"]}
                """.formatted(domain.getId()));
        mvc.perform(auth(delete(BASE + "/" + id))).andExpect(status().isNoContent());
        mvc.perform(auth(get(BASE + "/" + id))).andExpect(status().isNotFound());
        mvc.perform(auth(delete(BASE + "/" + id))).andExpect(status().isNotFound());
    }

    @Test
    void resetClearsUseCount() throws Exception {
        Link link = data.link(domain, "used", "https://example.org/u");
        transactions.executeWithoutResult(s -> links.consume(link.getId(), java.time.Instant.now()));
        transactions.executeWithoutResult(s -> links.consume(link.getId(), java.time.Instant.now()));
        mvc.perform(auth(get(BASE + "/" + link.getId()))).andExpect(jsonPath("$.useCount").value(2));
        mvc.perform(auth(post(BASE + "/" + link.getId() + "/reset"))).andExpect(status().isNoContent());
        mvc.perform(auth(get(BASE + "/" + link.getId()))).andExpect(jsonPath("$.useCount").value(0));
    }

    @Test
    void qrCodes() throws Exception {
        Link link = data.link(domain, "qr", "https://example.org/q");
        mvc.perform(auth(get(BASE + "/" + link.getId() + "/qr.svg")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(content().string(containsString("<svg")));
        byte[] png = mvc.perform(auth(get(BASE + "/" + link.getId() + "/qr.png").param("size", "200")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(200);
    }

    @Test
    void unreadableBodyIs400Problem() throws Exception {
        mvc.perform(jsonBody(post(BASE), "{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Corps de requête illisible"));
        mvc.perform(jsonBody(post(BASE), "{\"domainId\":\"not-a-uuid\",\"targetUrl\":\"https://example.org\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void requiresToken() throws Exception {
        mvc.perform(get(BASE).with(r -> {
                    r.setRemoteAddr("10.66.0.1");
                    return r;
                }))
                .andExpect(status().isUnauthorized());
    }

    private String createAndGetId(String body) throws Exception {
        MvcResult result = mvc.perform(jsonBody(post(BASE), body)).andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asString();
    }
}
