package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.access.DenyReason;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.link.MetaMode;
import fr.enimaloc.blinder.link.check.HeaderCheckClient;
import fr.enimaloc.blinder.link.check.LinkHeaderCheck;
import fr.enimaloc.blinder.link.check.LinkHeaderCheckRepository;
import fr.enimaloc.blinder.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
@RecordApplicationEvents
class HeaderCheckRedirectTest {

    static final String BOT = "Mozilla/5.0 (compatible; Discordbot/2.0)";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    LinkRepository links;
    @Autowired
    LinkHeaderCheckRepository checks;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    ApplicationEvents events;
    @Autowired
    DomainRepository domains;
    @MockitoBean
    HeaderCheckClient client;

    Domain domain;

    @BeforeEach
    void setUp() {
        data.reset();
        domain = data.domain("go.example.com", data.admin("admin"));
    }

    Link checked(Link link) {
        Link saved = data.save(link);
        checks.save(new LinkHeaderCheck(saved.getId(), "X-Blinder-Check", "sesame"));
        return saved;
    }

    Link link(String slug) {
        return new Link(domain, slug, "https://dest.example.org/t", domain.getOwner());
    }

    int useCount(Link link) {
        return links.findById(link.getId()).orElseThrow().getUseCount();
    }

    void destinationAnswers(boolean ok) {
        when(client.passes(anyString(), anyString(), anyString())).thenReturn(ok);
    }

    @Test
    void acceptedCheckRedirectsTemporarilyEvenForPermanentLinks() throws Exception {
        destinationAnswers(true);
        Link moved = link("moved");
        moved.setRedirectStatus(301);
        checked(moved);
        Link perm = link("perm");
        perm.setRedirectStatus(308);
        checked(perm);

        mvc.perform(get("http://go.example.com/moved"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://dest.example.org/t"));
        mvc.perform(get("http://go.example.com/perm"))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(redirectedUrl("https://dest.example.org/t"));
        verify(client, times(2)).passes("https://dest.example.org/t", "X-Blinder-Check", "sesame");
        assertThat(useCount(moved)).isEqualTo(1);
    }

    @Test
    void checkTargetsTheExactRedirectUrlWithForwardedParameters() throws Exception {
        destinationAnswers(true);
        Link link = link("fwd");
        link.setForwardQuery(true);
        checked(link);
        mvc.perform(get("http://go.example.com/fwd").param("utm_source", "news"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://dest.example.org/t?utm_source=news"));
        verify(client).passes("https://dest.example.org/t?utm_source=news", "X-Blinder-Check", "sesame");
    }

    @Test
    void refusedCheckLooksLikeAnUnknownLinkAndConsumesNothing() throws Exception {
        destinationAnswers(false);
        Link link = link("limited");
        link.setMaxUses(3);
        checked(link);
        mvc.perform(get("http://go.example.com/limited"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(useCount(link)).isZero();
        assertThat(events.stream(AccessDenied.class).toList())
                .singleElement().satisfies(e -> assertThat(e.reason()).isEqualTo(DenyReason.HEADER_CHECK_FAILED));
        assertThat(events.stream(LinkResolved.class).toList()).isEmpty();
    }

    @Test
    void refusedCheckUsesTheDeniedUrlThenTheDomainNotFoundUrl() throws Exception {
        destinationAnswers(false);
        Link denied = link("denied");
        denied.setDeniedUrl("https://example.com/refus");
        checked(denied);
        mvc.perform(get("http://go.example.com/denied"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/refus"));

        domain.setNotFoundUrl("https://example.com/introuvable");
        domains.save(domain);
        checked(link("plain"));
        mvc.perform(get("http://go.example.com/plain"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/introuvable"));
    }

    @Test
    void linkWithoutCheckNeverCallsTheDestinationAndKeepsItsStatus() throws Exception {
        Link link = link("bare");
        link.setRedirectStatus(301);
        data.save(link);
        mvc.perform(get("http://go.example.com/bare")).andExpect(status().isMovedPermanently());
        mvc.perform(head("http://go.example.com/bare")).andExpect(status().isMovedPermanently());
        verifyNoInteractions(client);
    }

    @Test
    void noCallForAVisitorAlreadyRefusedByTheProtection() throws Exception {
        Link password = link("secret");
        password.setPasswordHash(encoder.encode("open-sesame"));
        checked(password);
        Link ip = link("ip");
        ip.setIpAllowlist("10.0.0.0/8");
        checked(ip);
        Link off = link("off");
        off.setEnabled(false);
        checked(off);

        mvc.perform(get("http://go.example.com/secret")).andExpect(status().isOk());
        mvc.perform(get("http://go.example.com/secret").param("pw", "faux")).andExpect(status().isOk());
        mvc.perform(get("http://go.example.com/ip")).andExpect(status().isNotFound());
        mvc.perform(get("http://go.example.com/off")).andExpect(status().isNotFound());
        verifyNoInteractions(client);
        assertThat(events.stream(AccessDenied.class).map(AccessDenied::reason).toList())
                .doesNotContain(DenyReason.HEADER_CHECK_FAILED);
    }

    @Test
    void rightPasswordTriggersTheCheck() throws Exception {
        destinationAnswers(false);
        Link password = link("secret");
        password.setPasswordHash(encoder.encode("open-sesame"));
        checked(password);
        mvc.perform(post("http://go.example.com/secret").with(csrf()).param("password", "open-sesame"))
                .andExpect(status().isNotFound());
        verify(client).passes("https://dest.example.org/t", "X-Blinder-Check", "sesame");
    }

    @Test
    void noCallForTheMetaPageNorTheInterstitial() throws Exception {
        Link meta = link("meta");
        meta.setMetaMode(MetaMode.CUSTOM);
        meta.setMetaTitle("Titre");
        checked(meta);
        Link inter = link("inter");
        inter.setInterstitial(true);
        checked(inter);
        Link limited = link("limited");
        limited.setMaxUses(2);
        checked(limited);

        mvc.perform(get("http://go.example.com/meta").header("User-Agent", BOT)).andExpect(status().isOk());
        mvc.perform(get("http://go.example.com/inter")).andExpect(status().isOk());
        mvc.perform(head("http://go.example.com/limited")).andExpect(status().isOk());
        verify(client, never()).passes(anyString(), anyString(), anyString());
    }

    @Test
    void interstitialConfirmationTriggersTheCheck() throws Exception {
        destinationAnswers(true);
        Link inter = link("inter");
        inter.setInterstitial(true);
        checked(inter);
        mvc.perform(post("http://go.example.com/inter").with(csrf()))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("https://dest.example.org/t"));
        verify(client).passes("https://dest.example.org/t", "X-Blinder-Check", "sesame");
    }

    @Test
    void nonCountableRedirectsOfUnlimitedLinksAreCheckedToo() throws Exception {
        destinationAnswers(false);
        checked(link("open"));
        mvc.perform(head("http://go.example.com/open")).andExpect(status().isNotFound());
        mvc.perform(get("http://go.example.com/open").header("User-Agent", BOT)).andExpect(status().isNotFound());
        assertThat(events.stream(AccessDenied.class).map(AccessDenied::reason).toList())
                .containsExactly(DenyReason.HEADER_CHECK_FAILED, DenyReason.HEADER_CHECK_FAILED);
    }
}
