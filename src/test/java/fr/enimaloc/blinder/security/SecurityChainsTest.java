package fr.enimaloc.blinder.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityChainsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void adminPrefixRequiresLogin() throws Exception {
        mvc.perform(get("http://go.example.com/_/"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/**/_/login"));
    }

    @Test
    void dataRestIsUnderAdminPrefix() throws Exception {
        mvc.perform(get("http://go.example.com/_/api"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/**/_/login"));
    }

    @Test
    void publicChainNeitherRedirectsToLoginNorCreatesSession() throws Exception {
        MvcResult result = mvc.perform(get("http://go.example.com/anything")).andReturn();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(302);
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void encodedUnderscorePrefixCannotBypassAdminChain() throws Exception {
        mvc.perform(get(java.net.URI.create("http://go.example.com/%5F/")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/**/_/login"));
        mvc.perform(get(java.net.URI.create("http://go.example.com/%5f/links/1")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/**/_/login"));
    }
}
