package fr.enimaloc.blinder.setup;

import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.Role;
import fr.enimaloc.blinder.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class SetupFlowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    SetupGuard guard;
    @Autowired
    UserRepository users;
    @Autowired
    DomainRepository domains;

    String token;

    @BeforeEach
    void setUp() {
        data.reset();
        guard.refresh();
        token = guard.currentToken().orElseThrow();
    }

    MockHttpServletRequestBuilder submit(String token, String password, String confirm) {
        return post("http://go.example.com/_/setup").with(csrf())
                .param("token", token)
                .param("username", "admin")
                .param("password", password)
                .param("passwordConfirm", confirm)
                .param("host", "Go.Example.com");
    }

    @Test
    void pendingSetupRedirectsLoginToSetup() throws Exception {
        mvc.perform(get("http://go.example.com/_/login"))
                .andExpect(redirectedUrl("/_/setup"));
    }

    @Test
    void setupFormPrefillsCurrentHost() throws Exception {
        mvc.perform(get("http://go.example.com/_/setup"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("form", org.hamcrest.Matchers.hasProperty("host",
                        org.hamcrest.Matchers.equalTo("go.example.com"))));
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        mvc.perform(submit("not-the-token", "a-long-enough-pass", "a-long-enough-pass"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "token"));
        assertThat(users.count()).isZero();
    }

    @Test
    void passwordRulesAreEnforced() throws Exception {
        mvc.perform(submit(token, "short", "short"))
                .andExpect(model().attributeHasFieldErrors("form", "password"));
        mvc.perform(submit(token, "a-long-enough-pass", "another-long-pass"))
                .andExpect(model().attributeHasFieldErrors("form", "passwordConfirm"));
        assertThat(users.count()).isZero();
    }

    @Test
    void completesSetupThenDisappears() throws Exception {
        mvc.perform(submit(token, "a-long-enough-pass", "a-long-enough-pass"))
                .andExpect(redirectedUrl("/_/login?setup"));

        assertThat(users.findByUsername("admin").orElseThrow().getRole()).isEqualTo(Role.ADMIN);
        assertThat(domains.findByHost("go.example.com")).isPresent();
        assertThat(guard.isSetupPending()).isFalse();

        mvc.perform(get("http://go.example.com/_/setup")).andExpect(status().isNotFound());
        mvc.perform(submit(token, "a-long-enough-pass", "a-long-enough-pass")).andExpect(status().isNotFound());
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void notPendingWhenUsersExist() {
        data.admin("someone");
        assertThat(guard.isSetupPending()).isFalse();
    }
}
