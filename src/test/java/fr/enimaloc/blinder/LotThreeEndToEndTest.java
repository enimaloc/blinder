package fr.enimaloc.blinder;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.link.preview.PreviewData;
import fr.enimaloc.blinder.link.preview.PreviewFetcher;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class LotThreeEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @MockitoBean
    PreviewFetcher fetcher;

    @Test
    void configureAndFollowAnAdvancedLink() throws Exception {
        data.reset();
        User admin = data.admin("admin");
        Domain domain = data.domain("go.example.com", admin);
        when(fetcher.fetch(anyString())).thenReturn(Optional.of(new PreviewData("Article", "Résumé", null)));

        mvc.perform(post("/_/links").with(user(new BlinderUserDetails(admin))).with(csrf())
                        .param("domainId", domain.getId().toString())
                        .param("slug", "article")
                        .param("targetUrl", "https://news.example.org/article?src=blinder")
                        .param("enabled", "true")
                        .param("redirectStatus", "307")
                        .param("forwardQuery", "true")
                        .param("interstitial", "true")
                        .param("interstitialSeconds", "3")
                        .param("metaMode", "PASSTHROUGH"))
                .andExpect(redirectedUrl("/_/"));

        mvc.perform(get("http://go.example.com/article").header("User-Agent", "Slackbot-LinkExpanding 1.0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("content=\"Article\"")))
                .andExpect(content().string(not(containsString("news.example.org/article"))));

        mvc.perform(get("http://go.example.com/article?utm_campaign=launch"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("news.example.org")))
                .andExpect(content().string(containsString("id=\"countdown\"")))
                .andExpect(content().string(containsString("action=\"/article?utm_campaign=launch\"")));
        verify(fetcher, times(1)).fetch("https://news.example.org/article?src=blinder");
        assertThat(links.findByDomainAndSlug(domain, "article").orElseThrow().getUseCount()).isZero();

        mvc.perform(post("http://go.example.com/article?utm_campaign=launch").with(csrf()))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location",
                        "https://news.example.org/article?src=blinder&utm_campaign=launch"));
        assertThat(links.findByDomainAndSlug(domain, "article").orElseThrow().getUseCount()).isEqualTo(1);
    }
}
