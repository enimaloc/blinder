package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.QrCodes;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class QrAdminTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @MockitoSpyBean
    QrCodes qr;

    User admin;
    Link link;
    RequestPostProcessor asAdmin;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        Domain domain = data.domain("go.example.com", admin);
        link = data.link(domain, "promo", "https://example.com/");
        asAdmin = user(new BlinderUserDetails(admin));
        Mockito.clearInvocations(qr);
    }

    @Test
    void svgEndpointEncodesPublicUrl() throws Exception {
        mvc.perform(get("/_/links/{id}/qr.svg", link.getId()).with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(content().string(containsString("<svg")));
        Mockito.verify(qr).svg("http://go.example.com/promo");
    }

    @Test
    void pngEndpointHonoursSizeAndEncodesPublicUrl() throws Exception {
        byte[] body = mvc.perform(get("/_/links/{id}/qr.png", link.getId()).param("size", "256").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/png"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(body)).getWidth()).isEqualTo(256);
        Mockito.verify(qr).png("http://go.example.com/promo", 256);
    }

    @Test
    void pngWithoutSizeDefaultsTo512() throws Exception {
        byte[] body = mvc.perform(get("/_/links/{id}/qr.png", link.getId()).with(asAdmin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(body)).getWidth()).isEqualTo(512);
        Mockito.verify(qr).png("http://go.example.com/promo", 512);
    }

    @Test
    void pngMinimumSizeIs128Exactly() throws Exception {
        byte[] body = mvc.perform(get("/_/links/{id}/qr.png", link.getId()).param("size", "128").with(asAdmin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(body)).getWidth()).isEqualTo(128);
    }

    @Test
    void nonStandardPortAndSchemeAppearInTheEncodedUrl() throws Exception {
        mvc.perform(get("/_/links/{id}/qr.svg", link.getId()).with(asAdmin).with(request -> {
                    request.setScheme("https");
                    request.setServerPort(8443);
                    return request;
                }))
                .andExpect(status().isOk());
        Mockito.verify(qr).svg("https://go.example.com:8443/promo");
    }

    @Test
    void pngDefaultsAndClamps() throws Exception {
        mvc.perform(get("/_/links/{id}/qr.png", link.getId()).param("size", "9999").with(asAdmin))
                .andExpect(status().isOk());
        Mockito.verify(qr).png("http://go.example.com/promo", 2048);
    }

    @Test
    void downloadSetsAttachment() throws Exception {
        mvc.perform(get("/_/links/{id}/qr.svg", link.getId()).param("download", "").with(asAdmin))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"promo.svg\""));
        mvc.perform(get("/_/links/{id}/qr.png", link.getId()).param("download", "").with(asAdmin))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"promo.png\""));
    }

    @Test
    void otherOwnerGets404() throws Exception {
        User other = data.admin("other");
        RequestPostProcessor asOther = user(new BlinderUserDetails(other));
        mvc.perform(get("/_/links/{id}/qr.svg", link.getId()).with(asOther)).andExpect(status().isNotFound());
        mvc.perform(get("/_/links/{id}/qr.png", link.getId()).with(asOther)).andExpect(status().isNotFound());
    }

    @Test
    void editPageShowsQrAndDownloadLinks() throws Exception {
        mvc.perform(get("/_/links/{id}", link.getId()).with(asAdmin))
                .andExpect(content().string(containsString("/_/links/" + link.getId() + "/qr.svg")))
                .andExpect(content().string(containsString("/_/links/" + link.getId() + "/qr.png?download")));
        mvc.perform(get("/_/links/new").with(asAdmin))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("qr.svg"))));
    }
}
