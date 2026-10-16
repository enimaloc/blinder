package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.stats.Visit;
import fr.enimaloc.blinder.stats.VisitOutcome;
import fr.enimaloc.blinder.stats.VisitRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.tag.Tag;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class StatsAdminTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    VisitRepository visits;
    @Autowired
    LinkRepository links;
    @MockitoSpyBean
    fr.enimaloc.blinder.stats.VisitWriter visitWriter;

    User admin;
    Link link;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        link = data.link(data.domain("go.example.com", admin), "abc", "https://example.com");
    }

    private RequestPostProcessor login(User u) {
        return user(new BlinderUserDetails(u));
    }

    @Test
    void globalPageShowsTotals() throws Exception {
        Instant now = Instant.now().minusSeconds(30);
        data.visit(link, VisitOutcome.REDIRECTED, now, "203.0.113.1");
        data.visit(link, VisitOutcome.DENIED, now, "203.0.113.2");

        mvc.perform(get("/_/stats").with(login(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("go.example.com/abc")))
                .andExpect(content().string(containsString("/_/vendor/chart.js/")))
                .andExpect(content().string(containsString("Refusée")))
                .andExpect(content().string(containsString("<strong>2</strong>")))
                .andExpect(content().string(containsString("<strong>1</strong>")))
                .andExpect(content().string(containsString("<strong>0 %</strong>")))
                .andExpect(content().string(containsString("href=\"/_/stats.csv?days=30\"")));
        mvc.perform(get("/_/links/{id}/stats", link.getId()).with(login(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/_/links/" + link.getId() + "/stats.csv?days=30\"")));
    }

    @Test
    void retentionNoticeShownOnlyWhenPeriodExceedsRetention() throws Exception {
        mvc.perform(get("/_/stats").param("days", "365").with(login(admin)))
                .andExpect(content().string(containsString("conservées moins longtemps")));
        mvc.perform(get("/_/stats").param("days", "30").with(login(admin)))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("conservées moins longtemps"))));
    }

    @Test
    void droppedVisitsNoticeShownOnlyWhenSomeWereDropped() throws Exception {
        mvc.perform(get("/_/stats").with(login(admin)))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("visite(s) perdue(s)"))));
        org.mockito.Mockito.doReturn(3L).when(visitWriter).dropped();
        mvc.perform(get("/_/stats").with(login(admin)))
                .andExpect(content().string(containsString("3 visite(s) perdue(s)")));
    }

    @Test
    void anotherOwnersLinkIs404() throws Exception {
        User other = data.admin("other");
        mvc.perform(get("/_/links/{id}/stats", link.getId()).with(login(other))).andExpect(status().isNotFound());
        mvc.perform(get("/_/links/{id}/stats.csv", link.getId()).with(login(other))).andExpect(status().isNotFound());
    }

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        mvc.perform(get("/_/stats")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/_/stats.csv")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/_/vendor/chart.js/dist/chart.umd.js")).andExpect(status().is3xxRedirection());
    }

    @Test
    void csvNeutralisesHostileUserAgentAndKeepsOnlyPeriod() throws Exception {
        Instant now = Instant.now().minusSeconds(30);
        visits.saveAndFlush(new Visit(links.getReferenceById(link.getId()), admin, now, VisitOutcome.REDIRECTED, null,
                "GET", false, "203.0.113.1", "=HYPERLINK(\"http://evil\"),x", "Chrome", "Linux", null, null, "FR", "Paris"));
        data.visit(link, VisitOutcome.DENIED, now.minus(Duration.ofDays(3)), "203.0.113.2");
        data.visit(link, VisitOutcome.DENIED, now.minus(Duration.ofDays(60)), "203.0.113.3");
        User other = data.admin("other");
        Link otherLink = data.link(data.domain("other.example.com", other), "zzz", "https://example.com");
        data.visit(otherLink, VisitOutcome.REDIRECTED, now, "198.51.100.77");

        String body = mvc.perform(get("/_/stats.csv").param("days", "7").with(login(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("blinder-stats-all-")))
                .andReturn().getResponse().getContentAsString();

        String[] lines = body.split("\r\n");
        assertThat(lines[0]).isEqualTo("date,domaine,slug,issue,raison,methode,bot,ip,pays,ville,navigateur,os,referer,user_agent");
        assertThat(lines).hasSize(3);
        assertThat(body).contains("\"'=HYPERLINK(\"\"http://evil\"\"),x\"");
        assertThat(body).doesNotContain("198.51.100.77");
        assertThat(lines[2]).contains("go.example.com,abc,REDIRECTED");
    }

    @Test
    void unknownPeriodFallsBackToThirtyDays() throws Exception {
        mvc.perform(get("/_/stats").param("days", "9999").with(login(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<strong>30 j</strong>")));
    }

    @Test
    void tagFilterRestrictsGlobalPageAndCsv() throws Exception {
        Link tagged = data.link(link.getDomain(), "tgd", "https://example.com");
        Tag promo = data.tag(admin, "promo");
        data.tagLink(tagged, promo);
        Instant now = Instant.now().minusSeconds(30);
        data.visit(link, VisitOutcome.REDIRECTED, now, "203.0.113.1");
        data.visit(tagged, VisitOutcome.REDIRECTED, now, "203.0.113.2");

        mvc.perform(get("/_/stats").param("tag", promo.getId().toString()).with(login(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("go.example.com/tgd")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("go.example.com/abc"))))
                .andExpect(content().string(containsString("tag=" + promo.getId())));
        String body = mvc.perform(get("/_/stats.csv").param("tag", promo.getId().toString()).with(login(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body.split("\r\n")).hasSize(2);
        assertThat(body).contains(",tgd,").doesNotContain(",abc,");
    }

    @Test
    void tagCsvFileNameIsTransliteratedAndCarriesTheUtf8Name() throws Exception {
        Tag ete = data.tag(admin, "Été 2026");
        String header = mvc.perform(get("/_/stats.csv").param("tag", ete.getId().toString()).with(login(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Content-Disposition");
        assertThat(header).matches("attachment; filename=\"blinder-stats-tag-Ete_2026-\\d{4}-\\d{2}-\\d{2}\\.csv\";"
                + " filename\\*=UTF-8''blinder-stats-tag-%C3%89t%C3%A9%202026-\\d{4}-\\d{2}-\\d{2}\\.csv");
    }

    @Test
    void chartScriptIsServedWithoutVersionToAdminOnlyAndWebjarsPathIsGone() throws Exception {
        String asset = "chart.js/dist/chart.umd.js";
        mvc.perform(get("/webjars/" + asset)).andExpect(status().isNotFound());
        mvc.perform(get("/webjars/" + asset).with(login(admin))).andExpect(status().isNotFound());
        mvc.perform(get("/_/vendor/" + asset)).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/_/login")));
        mvc.perform(get("/_/vendor/" + asset).with(login(admin))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")));
    }

    @Test
    void foreignOrUnknownTagIs404() throws Exception {
        Tag theirs = data.tag(data.admin("other"), "promo");
        mvc.perform(get("/_/stats").param("tag", theirs.getId().toString()).with(login(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/_/stats.csv").param("tag", theirs.getId().toString()).with(login(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/_/stats").param("tag", UUID.randomUUID().toString()).with(login(admin)))
                .andExpect(status().isNotFound());
    }
}
