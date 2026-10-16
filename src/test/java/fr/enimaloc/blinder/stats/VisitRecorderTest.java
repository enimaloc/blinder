package fr.enimaloc.blinder.stats;

import fr.enimaloc.blinder.access.DenyReason;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class VisitRecorderTest {

    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    VisitWriter writer;
    @Autowired
    VisitRepository visits;
    @Autowired
    PasswordEncoder encoder;

    Domain domain;

    @BeforeEach
    void setUp() {
        data.reset();
        domain = data.domain("go.example.com", data.admin("admin"));
        Link secret = new Link(domain, "secret", "https://example.com/t", domain.getOwner());
        secret.setPasswordHash(encoder.encode("open-sesame"));
        data.save(secret);
        data.save(new Link(domain, "plain", "https://example.com/p", domain.getOwner()));
        Link off = new Link(domain, "off", "https://example.com/o", domain.getOwner());
        off.setEnabled(false);
        data.save(off);
    }

    static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    List<Visit> flushed() {
        writer.flush();
        return visits.findAll();
    }

    @Test
    void redirectIsRecordedWithClassification() throws Exception {
        mvc.perform(get("http://go.example.com/plain").with(from("198.51.100.7"))
                .header("User-Agent", UA).header("Referer", "https://News.Example/a")).andExpect(status().isFound());
        assertThat(flushed()).singleElement().satisfies(v -> {
            assertThat(v.getOutcome()).isEqualTo(VisitOutcome.REDIRECTED);
            assertThat(v.getIp()).isEqualTo("198.51.100.7");
            assertThat(v.getUserAgent()).isEqualTo(UA);
            assertThat(v.getBrowser()).isEqualTo("Chrome");
            assertThat(v.getOs()).isEqualTo("Windows");
            assertThat(v.getReferer()).isEqualTo("https://News.Example/a");
            assertThat(v.getRefererHost()).isEqualTo("news.example");
            assertThat(v.getOwner().getId()).isEqualTo(domain.getOwner().getId());
        });
    }

    @Test
    void botIsPreviewed() throws Exception {
        mvc.perform(get("http://go.example.com/plain").header("User-Agent", "Mozilla/5.0 (compatible; Discordbot/2.0)")).andExpect(status().isFound());
        assertThat(flushed()).singleElement().satisfies(v -> {
            assertThat(v.getOutcome()).isEqualTo(VisitOutcome.PREVIEWED);
            assertThat(v.isBot()).isTrue();
        });
    }

    @Test
    void wrongPasswordIsRejectedAndNeverStored() throws Exception {
        mvc.perform(get("http://go.example.com/secret").param("pw", "nope-secret-value")).andExpect(status().isOk());
        List<Visit> all = flushed();
        assertThat(all).singleElement().satisfies(v -> assertThat(v.getOutcome()).isEqualTo(VisitOutcome.PASSWORD_REJECTED));
        for (Visit v : all) {
            for (Field f : Visit.class.getDeclaredFields()) {
                f.setAccessible(true);
                Object value = f.get(v);
                if (value instanceof String s) {
                    assertThat(s).doesNotContain("nope-secret-value");
                }
            }
        }
    }

    @Test
    void missingPasswordIsChallenged() throws Exception {
        mvc.perform(get("http://go.example.com/secret")).andExpect(status().isOk());
        assertThat(flushed()).singleElement().satisfies(v -> assertThat(v.getOutcome()).isEqualTo(VisitOutcome.CHALLENGED));
    }

    @Test
    void disabledLinkIsDenied() throws Exception {
        mvc.perform(get("http://go.example.com/off"));
        assertThat(flushed()).singleElement().satisfies(v -> {
            assertThat(v.getOutcome()).isEqualTo(VisitOutcome.DENIED);
            assertThat(v.getDenyReason()).isEqualTo(DenyReason.DISABLED);
        });
    }

    @Test
    void throttledAttemptIsRecorded() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("http://go.example.com/secret").with(from("198.51.100.9")).param("pw", "w" + i));
        }
        mvc.perform(get("http://go.example.com/secret").with(from("198.51.100.9")).param("pw", "open-sesame"))
                .andExpect(status().isTooManyRequests());
        assertThat(flushed()).extracting(Visit::getOutcome)
                .containsOnlyOnce(VisitOutcome.THROTTLED)
                .contains(VisitOutcome.PASSWORD_REJECTED);
    }

    @Test
    void userAgentAndRefererAreTruncated() throws Exception {
        mvc.perform(get("http://go.example.com/plain").header("User-Agent", "U".repeat(900))
                .header("Referer", "https://x.example/" + "a".repeat(3000))).andExpect(status().isFound());
        assertThat(flushed()).singleElement().satisfies(v -> {
            assertThat(v.getUserAgent()).hasSize(512);
            assertThat(v.getReferer()).hasSize(1024);
        });
    }
}
