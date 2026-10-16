package fr.enimaloc.blinder.redirect;

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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
@RecordApplicationEvents
class VisitEventsTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    ApplicationEvents events;

    Domain domain;

    @BeforeEach
    void setUp() {
        data.reset();
        domain = data.domain("go.example.com", data.admin("admin"));
        Link link = new Link(domain, "secret", "https://example.com/t", domain.getOwner());
        link.setPasswordHash(encoder.encode("open-sesame"));
        data.save(link);
        data.save(new Link(domain, "plain", "https://example.com/p", domain.getOwner()));
    }

    static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    void missingPasswordPublishesAnUnrejectedChallenge() throws Exception {
        mvc.perform(get("http://go.example.com/secret")).andExpect(status().isOk());
        assertThat(events.stream(LinkChallenged.class).toList())
                .singleElement().satisfies(e -> assertThat(e.rejected()).isFalse());
    }

    @Test
    void wrongPasswordPublishesARejectedChallenge() throws Exception {
        mvc.perform(get("http://go.example.com/secret").param("pw", "nope")).andExpect(status().isOk());
        assertThat(events.stream(LinkChallenged.class).toList())
                .singleElement().satisfies(e -> {
                    assertThat(e.rejected()).isTrue();
                    assertThat(e.toString()).doesNotContain("nope");
                });
    }

    @Test
    void rightPasswordResolvesWithoutChallenge() throws Exception {
        mvc.perform(get("http://go.example.com/secret").param("pw", "open-sesame")).andExpect(status().isFound());
        assertThat(events.stream(LinkChallenged.class).toList()).isEmpty();
        assertThat(events.stream(LinkResolved.class).toList())
                .singleElement().satisfies(e -> assertThat(e.counted()).isTrue());
    }

    @Test
    void exceedingThePasswordLimiterPublishesAThrottle() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("http://go.example.com/secret").with(from("198.51.100.7")).param("pw", "w" + i))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("http://go.example.com/secret").with(from("198.51.100.7")).param("pw", "open-sesame"))
                .andExpect(status().isTooManyRequests());
        assertThat(events.stream(LinkThrottled.class).toList())
                .singleElement().satisfies(e -> assertThat(e.context().clientIp()).isEqualTo("198.51.100.7"));
    }

    @Test
    void refererIsCapturedWhenPresent() throws Exception {
        mvc.perform(get("http://go.example.com/plain").header("Referer", "https://news.example/a"))
                .andExpect(status().isFound());
        assertThat(events.stream(LinkResolved.class).toList())
                .singleElement().satisfies(e -> assertThat(e.context().referer()).isEqualTo("https://news.example/a"));
    }

    @Test
    void refererIsNullWhenAbsentAndTruncatedWhenHuge() throws Exception {
        mvc.perform(get("http://go.example.com/plain")).andExpect(status().isFound());
        mvc.perform(get("http://go.example.com/plain").header("Referer", "https://x.example/" + "a".repeat(3000)))
                .andExpect(status().isFound());
        var resolved = events.stream(LinkResolved.class).toList();
        assertThat(resolved.get(0).context().referer()).isNull();
        assertThat(resolved.get(1).context().referer()).hasSize(1024);
    }
}
