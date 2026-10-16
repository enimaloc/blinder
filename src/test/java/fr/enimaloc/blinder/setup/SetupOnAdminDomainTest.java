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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
@TestPropertySource(properties = "blinder.admin.domain=admin.example.com")
class SetupOnAdminDomainTest {

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

    @Test
    void setupFormIsNotPrefilledWithAdminHost() throws Exception {
        mvc.perform(get("http://admin.example.com/_/setup"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("form", org.hamcrest.Matchers.hasProperty("host",
                        org.hamcrest.Matchers.emptyOrNullString())));
    }

    @Test
    void setupFormStillPrefillsPublicHost() throws Exception {
        mvc.perform(get("http://go.example.com/_/setup"))
                .andExpect(model().attribute("form", org.hamcrest.Matchers.hasProperty("host",
                        org.hamcrest.Matchers.equalTo("go.example.com"))));
    }

    @Test
    void adminHostIsRejectedAsFirstDomain() throws Exception {
        mvc.perform(post("http://admin.example.com/_/setup").with(csrf())
                        .param("token", token)
                        .param("username", "admin")
                        .param("password", "a-long-enough-pass")
                        .param("passwordConfirm", "a-long-enough-pass")
                        .param("host", "Admin.Example.com."))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "host"));
        assertThat(users.count()).isZero();
        assertThat(domains.count()).isZero();
    }
}
