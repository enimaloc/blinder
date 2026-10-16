package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.device.Device;
import fr.enimaloc.blinder.device.DeviceEnrollmentRepository;
import fr.enimaloc.blinder.device.DeviceRepository;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class DeviceAdminTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    DeviceRepository devices;
    @Autowired
    DeviceEnrollmentRepository enrollments;
    @Autowired
    DomainRepository domains;

    User admin;
    Domain domain;
    RequestPostProcessor asAdmin;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        domain = data.domain("go.example.com", admin);
        asAdmin = user(new BlinderUserDetails(admin));
    }

    @Test
    void listsOwnDevicesWithDomainCount() throws Exception {
        Device laptop = data.device(admin, "Laptop");
        data.credential(laptop, domain);
        data.device(data.admin("other"), "Intruder");
        mvc.perform(get("/_/devices").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Laptop")))
                .andExpect(content().string(not(containsString("Intruder"))));
    }

    @Test
    void createsEnrollmentAndShowsUrlOnce() throws Exception {
        mvc.perform(post("/_/devices").with(asAdmin).with(csrf())
                        .param("name", "Laptop").param("startDomainId", domain.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http://go.example.com/_/enroll/")));
        assertThat(enrollments.findPending(admin, Instant.now())).hasSize(1);
    }

    @Test
    void rejectsBlankNameAndForeignOrDisabledDomain() throws Exception {
        mvc.perform(post("/_/devices").with(asAdmin).with(csrf())
                        .param("name", " ").param("startDomainId", domain.getId().toString()))
                .andExpect(model().attributeHasFieldErrors("form", "name"));
        Domain foreign = data.domain("other.example.com", data.admin("other"));
        mvc.perform(post("/_/devices").with(asAdmin).with(csrf())
                        .param("name", "Laptop").param("startDomainId", foreign.getId().toString()))
                .andExpect(model().attributeHasFieldErrors("form", "startDomainId"));
        Domain off = domains.findById(domain.getId()).orElseThrow();
        off.setEnabled(false);
        domains.save(off);
        mvc.perform(post("/_/devices").with(asAdmin).with(csrf())
                        .param("name", "Laptop").param("startDomainId", domain.getId().toString()))
                .andExpect(model().attributeHasFieldErrors("form", "startDomainId"));
    }

    @Test
    void renamesAndRevokesOwnDevicesOnly() throws Exception {
        Device laptop = data.device(admin, "Laptop");
        Device foreign = data.device(data.admin("other"), "Foreign");

        mvc.perform(post("/_/devices/{id}/rename", laptop.getId()).with(asAdmin).with(csrf()).param("name", "Work"))
                .andExpect(redirectedUrl("/_/devices"));
        assertThat(devices.findById(laptop.getId()).orElseThrow().getName()).isEqualTo("Work");

        mvc.perform(post("/_/devices/{id}/rename", foreign.getId()).with(asAdmin).with(csrf()).param("name", "x"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/_/devices/{id}/revoke", foreign.getId()).with(asAdmin).with(csrf()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/_/devices/{id}/revoke", laptop.getId()).with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/devices"));
        assertThat(devices.findById(laptop.getId())).isEmpty();
        assertThat(devices.findById(foreign.getId())).isPresent();
    }

    @Test
    void cancelsPendingEnrollment() throws Exception {
        data.enrollment(admin, domain, "Tablet", Instant.now().plusSeconds(600));
        var pending = enrollments.findPending(admin, Instant.now()).getFirst();
        mvc.perform(post("/_/devices/enrollments/{id}/cancel", pending.getId()).with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/devices"));
        assertThat(enrollments.findById(pending.getId())).isEmpty();
    }

    @Test
    void domainWithPendingEnrollmentCannotBeDeleted() throws Exception {
        data.enrollment(admin, domain, "Tablet", Instant.now().plusSeconds(600));
        mvc.perform(post("/_/domains/{id}/delete", domain.getId()).with(asAdmin).with(csrf()))
                .andExpect(redirectedUrl("/_/domains"))
                .andExpect(flash().attribute("error", containsString("enrôlement")));
        assertThat(domains.findById(domain.getId())).isPresent();
    }

    @Test
    void creatingDomainRemindsToResyncDevices() throws Exception {
        data.device(admin, "Laptop");
        mvc.perform(post("/_/domains").with(asAdmin).with(csrf())
                        .param("host", "new.example.com").param("enabled", "true"))
                .andExpect(redirectedUrl("/_/domains"))
                .andExpect(flash().attribute("info", containsString("resynchroniser")));
    }

    @Test
    void navigationLinksToDevices() throws Exception {
        mvc.perform(get("/_/devices").with(asAdmin))
                .andExpect(content().string(containsString("href=\"/_/devices\"")));
    }

    @Test
    void renameDoesNotResetTicketSetAtomically() throws Exception {
        Device laptop = data.device(admin, "Laptop");
        Instant expires = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MILLIS);
        devices.setTicket(laptop.getId(), "ticket-hash", expires);

        mvc.perform(post("/_/devices/{id}/rename", laptop.getId()).with(asAdmin).with(csrf()).param("name", "Work"))
                .andExpect(redirectedUrl("/_/devices"));

        Device reloaded = devices.findById(laptop.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Work");
        assertThat(reloaded.getSyncTicketHash()).isEqualTo("ticket-hash");
        assertThat(reloaded.getSyncExpiresAt()).isNotNull();
    }
}
