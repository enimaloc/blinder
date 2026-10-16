package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.device.Device;
import fr.enimaloc.blinder.device.DeviceCookieService;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.support.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class DeviceProtectionTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    PasswordEncoder encoder;

    Domain domain;
    Domain other;
    Device laptop;
    Device phone;
    Link link;

    @BeforeEach
    void setUp() {
        data.reset();
        domain = data.domain("go.example.com", data.admin("admin"));
        other = data.domain("other.example.com", domain.getOwner());
        laptop = data.device(domain.getOwner(), "Laptop");
        phone = data.device(domain.getOwner(), "Phone");
        link = data.link(domain, "private", "https://example.com/private");
        data.allowDevice(link, laptop);
    }

    static Cookie cookie(String value) {
        return new Cookie(DeviceCookieService.COOKIE, value);
    }

    @Test
    void allowedDeviceIsRedirected() throws Exception {
        mvc.perform(get("http://go.example.com/private").cookie(cookie(data.credential(laptop, domain))))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/private"));
    }

    @Test
    void othersLookUnknown() throws Exception {
        mvc.perform(get("http://go.example.com/private")).andExpect(status().isNotFound());
        mvc.perform(get("http://go.example.com/private").cookie(cookie(data.credential(phone, domain))))
                .andExpect(status().isNotFound());
        mvc.perform(get("http://go.example.com/private").cookie(cookie(data.credential(laptop, other))))
                .andExpect(status().isNotFound());
    }

    @Test
    void allowedDeviceSkipsPassword() throws Exception {
        Link vault = new Link(domain, "vault", "https://example.com/vault", domain.getOwner());
        vault.setPasswordHash(encoder.encode("open-sesame"));
        vault = data.save(vault);
        data.allowDevice(vault, laptop);
        mvc.perform(get("http://go.example.com/vault").cookie(cookie(data.credential(laptop, domain))))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/vault"));
        mvc.perform(get("http://go.example.com/vault"))
                .andExpect(status().isOk());
    }
}
