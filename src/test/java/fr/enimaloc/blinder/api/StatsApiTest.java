package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.stats.VisitDailyStat;
import fr.enimaloc.blinder.stats.VisitDailyStatRepository;
import fr.enimaloc.blinder.stats.VisitOutcome;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.tag.Tag;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestData.class, StatsApiTest.FixedClock.class})
class StatsApiTest {

    /** Horloge figée à midi UTC : le « jour courant » ne bascule pas pendant le test. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    static final String URL = "/_/api/v1/stats";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    VisitDailyStatRepository dailyStats;
    @Autowired
    BlinderProperties blinder;
    @Autowired
    Clock clock;

    User admin;
    String token;
    Link tagged;
    Link plain;
    Tag tag;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        token = data.apiToken(admin, "ci");
        var domain = data.domain("go.example.com", admin);
        tagged = data.link(domain, "tagged", "https://example.com");
        plain = data.link(domain, "plain", "https://example.com");
        tag = data.tag(admin, "promo");
        data.tagLink(tagged, tag);
        Instant now = clock.instant().minusSeconds(60);
        data.visit(tagged, VisitOutcome.REDIRECTED, now, "203.0.113.1");
        data.visit(tagged, VisitOutcome.DENIED, now, "203.0.113.2");
        data.visit(plain, VisitOutcome.REDIRECTED, now, "203.0.113.3");
        data.visit(plain, VisitOutcome.REDIRECTED, now, "203.0.113.3");
        data.visit(plain, VisitOutcome.REDIRECTED, now, "203.0.113.4");
        LocalDate today = LocalDate.now(clock.withZone(blinder.admin().timeZone()));
        VisitDailyStat stat = new VisitDailyStat(tagged, admin, today, VisitOutcome.REDIRECTED);
        stat.increment(1);
        VisitDailyStat plainStat = new VisitDailyStat(plain, admin, today, VisitOutcome.REDIRECTED);
        plainStat.increment(3);
        dailyStats.saveAll(java.util.List.of(stat, plainStat));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    @Test
    void globalStatsWithTopLinksAndDefaultPeriod() throws Exception {
        mvc.perform(auth(get(URL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.totals.total").value(5))
                .andExpect(jsonPath("$.totals.byOutcome.REDIRECTED").value(4))
                .andExpect(jsonPath("$.totals.byOutcome.DENIED").value(1))
                .andExpect(jsonPath("$.totals.uniqueIps").value(4))
                .andExpect(jsonPath("$.totals.bots").value(0))
                .andExpect(jsonPath("$.daily", hasSize(30)))
                .andExpect(jsonPath("$.daily[29].redirected").value(4))
                .andExpect(jsonPath("$.daily[0].day").exists())
                .andExpect(jsonPath("$.countries").isArray())
                .andExpect(jsonPath("$.referers").isArray())
                .andExpect(jsonPath("$.browsers").isArray())
                .andExpect(jsonPath("$.os").isArray())
                .andExpect(jsonPath("$.topLinks").isArray())
                .andExpect(jsonPath("$.topLinks[0].count").value(3));
    }

    @Test
    void daysIsNormalisedAndReturned() throws Exception {
        mvc.perform(auth(get(URL + "?days=7")))
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.daily", hasSize(7)));
        mvc.perform(auth(get(URL + "?days=12")))
                .andExpect(jsonPath("$.days").value(30));
    }

    @Test
    void perLinkHasNoTopLinks() throws Exception {
        mvc.perform(auth(get(URL + "?link=" + plain.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.total").value(3))
                .andExpect(jsonPath("$.topLinks").doesNotExist());
    }

    @Test
    void perTagCountsOnlyTaggedLinks() throws Exception {
        mvc.perform(auth(get(URL + "?tag=" + tag.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.total").value(2))
                .andExpect(jsonPath("$.daily[29].redirected").value(1))
                .andExpect(jsonPath("$.topLinks", hasSize(1)));
    }

    @Test
    void linkAndTagTogetherIs400() throws Exception {
        mvc.perform(auth(get(URL + "?link=" + plain.getId() + "&tag=" + tag.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.tag").exists());
    }

    @Test
    void foreignOrUnknownIdsAre404() throws Exception {
        User other = data.admin("other");
        Link foreignLink = data.link(data.domain("o.example.com", other), "x", "https://example.com");
        Tag foreignTag = data.tag(other, "t");
        mvc.perform(auth(get(URL + "?link=" + foreignLink.getId()))).andExpect(status().isNotFound());
        mvc.perform(auth(get(URL + "?tag=" + foreignTag.getId()))).andExpect(status().isNotFound());
        mvc.perform(auth(get(URL + "?tag=" + UUID.randomUUID()))).andExpect(status().isNotFound());
    }
}
