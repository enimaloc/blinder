package fr.enimaloc.blinder.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc n'exécute pas réellement les forwards : on vérifie l'URL forwardée.
 * La sécurité du chemin forwardé (/_/…) est couverte par SecurityChainsTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "blinder.admin.domain=admin.example.com")
class AdminDomainSecurityTest {

    @Autowired
    MockMvc mvc;

    @Test
    void rootOfAdminDomainIsForwardedToPanel() throws Exception {
        mvc.perform(get("http://admin.example.com/"))
                .andExpect(forwardedUrl("/_/"));
    }

    @Test
    void slugOnAdminDomainNeverReachesPublicRedirect() throws Exception {
        mvc.perform(get("http://admin.example.com/promo"))
                .andExpect(forwardedUrl("/_/promo"));
    }

    @Test
    void prefixedPathOnAdminDomainIsNotForwardedTwice() throws Exception {
        mvc.perform(get("http://admin.example.com/_/"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/**/_/login"));
    }

    @Test
    void apiOnAdminDomainUsesTheApiChain() throws Exception {
        mvc.perform(get("http://admin.example.com/_/api/v1/domains"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void prefixStillWorksOnPublicDomains() throws Exception {
        mvc.perform(get("http://go.example.com/_/"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/**/_/login"));
    }
}
