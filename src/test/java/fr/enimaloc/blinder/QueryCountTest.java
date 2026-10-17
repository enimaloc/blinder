package fr.enimaloc.blinder;

import fr.enimaloc.blinder.device.Device;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.link.check.LinkHeaderCheckService;
import fr.enimaloc.blinder.notification.ChannelType;
import fr.enimaloc.blinder.notification.LinkNotificationSettings;
import fr.enimaloc.blinder.notification.LinkNotificationSettingsRepository;
import fr.enimaloc.blinder.notification.NotificationChannel;
import fr.enimaloc.blinder.notification.NotificationDelivery;
import fr.enimaloc.blinder.notification.NotificationDeliveryRepository;
import fr.enimaloc.blinder.notification.TriggerType;
import fr.enimaloc.blinder.stats.VisitOutcome;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.tag.Tag;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nombre de requêtes SQL des pages de liste : il ne doit pas dépendre du nombre de lignes affichées (pas de N+1).
 * Statistiques Hibernate activées pour ce seul contexte de test. Le même scénario tourne sur H2, SQLite et PostgreSQL
 * (sous-classes), car le plan de requêtes peut différer selon le dialecte.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Import(TestData.class)
abstract class QueryCountTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @Autowired
    LinkNotificationSettingsRepository settings;
    @Autowired
    NotificationDeliveryRepository deliveries;
    @Autowired
    LinkHeaderCheckService headerChecks;
    @Autowired
    EntityManagerFactory emf;

    private record Seeded(User admin, String token, UUID firstLinkId) {
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/_/", "/_/api/v1/links", "/_/api/v1/links?q=exemple", "/_/stats",
            "/_/links/{first}/stats", "/_/notifications", "/_/tags", "/_/devices", "/_/domains",
            "/_/api/v1/tags", "/_/api/v1/domains", "/_/api/v1/stats"})
    void queryCountDoesNotDependOnRowCount(String page) throws Exception {
        long few = statementsFor(page, 3);
        long many = statementsFor(page, 20);
        assertThat(many).as("requêtes pour %s : 3 lignes → %d, 20 lignes → %d", page, few, many).isEqualTo(few);
    }

    private long statementsFor(String page, int rows) throws Exception {
        Seeded seeded = seed(rows);
        MockHttpServletRequestBuilder request = get(page.replace("{first}", seeded.firstLinkId().toString()));
        request = page.startsWith("/_/api/")
                ? request.header(HttpHeaders.AUTHORIZATION, "Bearer " + seeded.token())
                : request.with(user(new BlinderUserDetails(seeded.admin())));
        // Requête d'échauffement : écarte les lectures faites une seule fois par contexte.
        mvc.perform(request).andExpect(status().isOk());
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mvc.perform(request).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    /** {@code rows} liens (chacun sur son domaine, avec tags, appareil, réglages de notification, contrôle d'en-tête, visite), canaux, envois. */
    private Seeded seed(int rows) {
        data.reset();
        User admin = data.admin("admin");
        String token = data.apiToken(admin, "ci");
        Tag shared = data.tag(admin, "commun");
        UUID first = null;
        for (int i = 0; i < rows; i++) {
            Domain domain = data.domain("d" + i + ".example.com", admin);
            Link link = new Link(domain, "s" + i, "https://exemple.fr/" + i, admin);
            if (i % 3 == 1) {
                link.setPasswordHash("{noop}secret");
            }
            if (i % 3 == 2) {
                link.setMaxUses(1);
                link.setUseCount(1);
            }
            link = data.save(link);
            if (first == null) {
                first = link.getId();
            }
            if (i % 2 == 0) {
                headerChecks.apply(link, "X-Token", "valeur" + i);
            }
            data.tagLink(link, shared);
            data.tagLink(link, data.tag(admin, "tag" + i));
            Device device = data.device(admin, "appareil" + i);
            data.allowDevice(link, device);
            data.credential(device, domain);
            NotificationChannel channel = data.channel(admin, "canal" + i, ChannelType.WEBHOOK,
                    "https://hooks.example/" + i);
            LinkNotificationSettings s = new LinkNotificationSettings(links.getReferenceById(link.getId()));
            s.setOnExhausted(true);
            s.setChannels(new HashSet<>(Set.of(channel)));
            settings.saveAndFlush(s);
            deliveries.saveAndFlush(new NotificationDelivery(admin, channel, link.getId(), "d" + i + ".example.com/s" + i,
                    TriggerType.EXHAUSTED, "{}", Instant.now()));
            data.visit(link, VisitOutcome.REDIRECTED, Instant.now(), "203.0.113." + i);
            data.visit(links.findById(first).orElseThrow(), VisitOutcome.REDIRECTED, Instant.now(), "198.51.100." + i);
        }
        return new Seeded(admin, token, first);
    }
}
