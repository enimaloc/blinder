package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.security.RateLimiters;
import fr.enimaloc.blinder.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class AdminLoginRateLimitTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    RateLimiters rateLimiters;

    @BeforeEach
    void setUp() {
        data.reset();
        data.admin("admin");
    }

    @AfterEach
    void clear() {
        rateLimiters.adminLogin().clear();
    }

    MockHttpServletRequestBuilder login(String ip, String password) {
        return post("/_/login").with(csrf()).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        }).param("username", "admin").param("password", password);
    }

    @Test
    void locksAfterTenFailuresPerIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(login("198.51.100.20", "wrong")).andExpect(redirectedUrl("/_/login?error"));
        }
        mvc.perform(login("198.51.100.20", TestData.PASSWORD))
                .andExpect(redirectedUrl("/_/login?locked"))
                .andExpect(unauthenticated());
        mvc.perform(login("198.51.100.21", TestData.PASSWORD))
                .andExpect(redirectedUrl("/_/"))
                .andExpect(authenticated());
    }

    @Test
    void successResetsTheCounter() throws Exception {
        for (int i = 0; i < 9; i++) {
            mvc.perform(login("198.51.100.30", "wrong"));
        }
        mvc.perform(login("198.51.100.30", TestData.PASSWORD)).andExpect(redirectedUrl("/_/"));
        for (int i = 0; i < 9; i++) {
            mvc.perform(login("198.51.100.30", "wrong"));
        }
        mvc.perform(login("198.51.100.30", TestData.PASSWORD)).andExpect(redirectedUrl("/_/"));
    }

    @Test
    void concurrentWrongPasswordsAreCountedAtomically() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<String> task = () -> {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    return mvc.perform(login("198.51.100.40", "wrong")).andReturn().getResponse().getRedirectedUrl();
                };
                results.add(pool.submit(task));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<String> urls = new ArrayList<>();
            for (Future<String> f : results) {
                urls.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(urls.stream().filter("/_/login?error"::equals).count()).isEqualTo(10);
            assertThat(urls.stream().filter("/_/login?locked"::equals).count()).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void loginPageExplainsLock() throws Exception {
        mvc.perform(get("/_/login").param("locked", ""))
                .andExpect(content().string(containsString("Trop de tentatives")));
    }
}
