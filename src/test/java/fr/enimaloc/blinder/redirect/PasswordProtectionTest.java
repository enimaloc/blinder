package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class PasswordProtectionTest {

    static final String TARGET = "https://example.com/secret-target";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @Autowired
    PasswordEncoder encoder;

    Domain domain;
    Link link;

    @BeforeEach
    void setUp() {
        data.reset();
        domain = data.domain("go.example.com", data.admin("admin"));
        link = new Link(domain, "secret", TARGET, domain.getOwner());
        link.setPasswordHash(encoder.encode("open-sesame"));
        link = data.save(link);
    }

    int useCount() {
        return links.findById(link.getId()).orElseThrow().getUseCount();
    }

    static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    void getWithoutPasswordShowsFormWithoutTarget() throws Exception {
        mvc.perform(get("http://go.example.com/secret"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("name=\"password\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("secret-target"))));
        assertThat(useCount()).isZero();
    }

    @Test
    void wrongPasswordInUrlShowsErrorWithoutTarget() throws Exception {
        mvc.perform(get("http://go.example.com/secret").param("pw", "nope"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(containsString("Mot de passe incorrect")))
                .andExpect(content().string(not(containsString("secret-target"))));
    }

    @Test
    void rightPasswordInUrlRedirectsAndCounts() throws Exception {
        mvc.perform(get("http://go.example.com/secret").param("pw", "open-sesame"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(TARGET));
        assertThat(useCount()).isEqualTo(1);
    }

    @Test
    void rightPasswordInFormRedirectsAndCounts() throws Exception {
        mvc.perform(post("http://go.example.com/secret").with(csrf()).param("password", "open-sesame"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl(TARGET));
        assertThat(useCount()).isEqualTo(1);
    }

    @Test
    void passwordPostOnTemporaryLinkIsSeeOtherWithoutPasswordInLocation() throws Exception {
        link.setRedirectStatus(307);
        link.setForwardQuery(true);
        data.save(link);
        mvc.perform(post("http://go.example.com/secret").with(csrf()).param("password", "open-sesame"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", not(containsString("password"))));
    }

    @Test
    void formWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("http://go.example.com/secret").param("password", "open-sesame"))
                .andExpect(status().isForbidden());
        assertThat(useCount()).isZero();
    }

    @Test
    void tooManyFailuresAreBlockedEvenWithTheRightPassword() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("http://go.example.com/secret").with(csrf()).with(from("198.51.100.7"))
                            .param("password", "wrong-" + i))
                    .andExpect(status().isOk());
        }
        MvcResult blocked = mvc.perform(post("http://go.example.com/secret").with(csrf()).with(from("198.51.100.7"))
                        .param("password", "open-sesame"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(not(containsString("secret-target"))))
                .andReturn();
        assertThat(Long.parseLong(blocked.getResponse().getHeader("Retry-After"))).isPositive();
        assertThat(useCount()).isZero();

        mvc.perform(get("http://go.example.com/secret").with(from("198.51.100.8")).param("pw", "open-sesame"))
                .andExpect(status().isFound());
    }

    @Test
    void successResetsFailures() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("http://go.example.com/secret").with(from("198.51.100.9")).param("pw", "bad"));
        }
        mvc.perform(get("http://go.example.com/secret").with(from("198.51.100.9")).param("pw", "open-sesame"))
                .andExpect(status().isFound());
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("http://go.example.com/secret").with(from("198.51.100.9")).param("pw", "bad"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void botsAndHeadGetTheFormNeverTheTarget() throws Exception {
        mvc.perform(get("http://go.example.com/secret").header("User-Agent", "Discordbot/2.0"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("secret-target"))));
        mvc.perform(head("http://go.example.com/secret"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void limitedLinkWithRightPasswordStillHidesTargetFromHead() throws Exception {
        Link limited = new Link(domain, "limited", TARGET, domain.getOwner());
        limited.setPasswordHash(encoder.encode("open-sesame"));
        limited.setMaxUses(1);
        data.save(limited);
        mvc.perform(head("http://go.example.com/limited").param("pw", "open-sesame"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void expiredLinkIsGoneWithoutAskingForPassword() throws Exception {
        Link expired = new Link(domain, "old", TARGET, domain.getOwner());
        expired.setPasswordHash(encoder.encode("open-sesame"));
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        data.save(expired);
        mvc.perform(get("http://go.example.com/old"))
                .andExpect(status().isGone());
    }

    @Test
    void ipOnlyLinkLooksUnknownOutsideAllowlist() throws Exception {
        Link office = new Link(domain, "office", TARGET, domain.getOwner());
        office.setIpAllowlist("203.0.113.9");
        data.save(office);

        String unknown = mvc.perform(get("http://go.example.com/nope")).andReturn().getResponse().getContentAsString();
        mvc.perform(get("http://go.example.com/office"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(unknown));
        mvc.perform(get("http://go.example.com/office").with(from("203.0.113.9")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(TARGET));
    }

    @Test
    void failuresSpreadOverManyClientsTriggerTheAggregateBlock() throws Exception {
        Link shared = new Link(domain, "shared", TARGET, domain.getOwner());
        shared.setPasswordHash(encoder.encode("open-sesame"));
        shared = data.save(shared);
        for (int i = 1; i <= 50; i++) {
            mvc.perform(get("http://go.example.com/shared").with(from("198.51.100." + i)).param("pw", "wrong"))
                    .andExpect(status().isOk());
        }
        MvcResult blocked = mvc.perform(get("http://go.example.com/shared").with(from("203.0.113.200"))
                        .param("pw", "open-sesame"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().doesNotExist("Location"))
                .andReturn();
        assertThat(Long.parseLong(blocked.getResponse().getHeader("Retry-After"))).isPositive();
        assertThat(links.findById(shared.getId()).orElseThrow().getUseCount()).isZero();
    }

    @Test
    void concurrentWrongPasswordsNeverExceedTheClientLimit() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return mvc.perform(post("http://go.example.com/secret").with(csrf()).with(from("198.51.100.77"))
                                .param("password", "wrong-" + n))
                        .andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        go.countDown();
        int ok = 0;
        int limited = 0;
        for (Future<Integer> f : results) {
            int status = f.get();
            if (status == 200) {
                ok++;
            } else if (status == 429) {
                limited++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(5);
        assertThat(limited).isEqualTo(15);
    }

    @Test
    void publicResponsesCarryNoReferrerPolicy() throws Exception {
        mvc.perform(get("http://go.example.com/secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("http://go.example.com/secret").param("pw", "open-sesame"))
                .andExpect(status().isFound())
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void redirectsDoNotSetCookies() throws Exception {
        mvc.perform(get("http://go.example.com/secret").param("pw", "open-sesame"))
                .andExpect(status().isFound())
                .andExpect(header().doesNotExist("Set-Cookie"));
        data.save(new Link(domain, "plain", TARGET, domain.getOwner()));
        mvc.perform(get("http://go.example.com/plain"))
                .andExpect(status().isFound())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
