package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import fr.enimaloc.blinder.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class AuthAndAccountTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    User admin;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
    }

    @Test
    void loginPageRenders() throws Exception {
        mvc.perform(get("/_/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"username\"")));
    }

    @Test
    void loginWithDatabaseUser() throws Exception {
        mvc.perform(formLogin("/_/login").user("admin").password(TestData.PASSWORD))
                .andExpect(authenticated().withRoles("ADMIN"))
                .andExpect(redirectedUrl("/_/"));
        mvc.perform(formLogin("/_/login").user("admin").password("wrong-password"))
                .andExpect(unauthenticated());
    }

    @Test
    void changesPassword() throws Exception {
        mvc.perform(post("/_/account/password").with(user(new BlinderUserDetails(admin))).with(csrf())
                        .param("currentPassword", TestData.PASSWORD)
                        .param("newPassword", "a-brand-new-passphrase")
                        .param("confirmPassword", "a-brand-new-passphrase"))
                .andExpect(redirectedUrl("/_/account?changed"));
        String hash = users.findById(admin.getId()).orElseThrow().getPasswordHash();
        assertThat(encoder.matches("a-brand-new-passphrase", hash)).isTrue();
    }

    @Test
    void rejectsWrongCurrentPasswordShortOrMismatch() throws Exception {
        mvc.perform(post("/_/account/password").with(user(new BlinderUserDetails(admin))).with(csrf())
                        .param("currentPassword", "nope")
                        .param("newPassword", "a-brand-new-passphrase")
                        .param("confirmPassword", "a-brand-new-passphrase"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "currentPassword"));
        mvc.perform(post("/_/account/password").with(user(new BlinderUserDetails(admin))).with(csrf())
                        .param("currentPassword", TestData.PASSWORD)
                        .param("newPassword", "short")
                        .param("confirmPassword", "short"))
                .andExpect(model().attributeHasFieldErrors("form", "newPassword"));
        mvc.perform(post("/_/account/password").with(user(new BlinderUserDetails(admin))).with(csrf())
                        .param("currentPassword", TestData.PASSWORD)
                        .param("newPassword", "a-brand-new-passphrase")
                        .param("confirmPassword", "another-passphrase!!"))
                .andExpect(model().attributeHasFieldErrors("form", "confirmPassword"));
    }

    @Test
    void adminFormsRequireCsrf() throws Exception {
        mvc.perform(post("/_/account/password").with(user(new BlinderUserDetails(admin)))
                        .param("currentPassword", TestData.PASSWORD))
                .andExpect(status().isForbidden());
    }
}
