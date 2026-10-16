package fr.enimaloc.blinder.security;

import fr.enimaloc.blinder.common.BlinderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminRequestMatcherTest {

    static AdminRequestMatcher matcher(String adminDomain) {
        return new AdminRequestMatcher(new BlinderProperties(
                new BlinderProperties.Admin(adminDomain, ZoneOffset.UTC),
                new BlinderProperties.Slug(7, List.of()),
                new BlinderProperties.Bots(List.of())));
    }

    static MockHttpServletRequest request(String host, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServerName(host);
        return request;
    }

    @Test
    void prefixMatchesOnAnyDomain() {
        AdminRequestMatcher m = matcher(null);
        assertThat(m.matches(request("go.example.com", "/_/"))).isTrue();
        assertThat(m.matches(request("go.example.com", "/_/links/1"))).isTrue();
        assertThat(m.matches(request("go.example.com", "/promo"))).isFalse();
        assertThat(m.matches(request("go.example.com", "/_promo"))).isFalse();
    }

    @Test
    void adminDomainMatchesEveryPathCaseInsensitively() {
        AdminRequestMatcher m = matcher("Admin.Example.com");
        assertThat(m.matches(request("admin.example.com", "/promo"))).isTrue();
        assertThat(m.matches(request("ADMIN.example.com", "/"))).isTrue();
        assertThat(m.isAdminDomain(request("go.example.com", "/"))).isFalse();
    }

    @Test
    void blankAdminDomainIsIgnored() {
        assertThat(matcher("  ").isAdminDomain(request("localhost", "/"))).isFalse();
    }
}
