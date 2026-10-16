package fr.enimaloc.blinder.security;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.support.TestData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Ici le loopback n'est PAS un proxy déclaré : X-Forwarded-For doit être ignoré. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestData.class)
@TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=10\\.255\\.255\\.255")
class UntrustedProxyTest {

    @LocalServerPort
    int port;
    @Autowired
    TestData data;

    @BeforeAll
    static void allowHostHeader() {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    @BeforeEach
    void setUp() {
        data.reset();
        Domain domain = data.domain("go.example.com", data.admin("admin"));
        Link link = new Link(domain, "office", "https://example.com/office", domain.getOwner());
        link.setIpAllowlist("203.0.113.9");
        data.save(link);
    }

    @Test
    void spoofedForwardedForCannotPassAllowlist() throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/office"))
                        .header("Host", "go.example.com")
                        .header("X-Forwarded-For", "203.0.113.9")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("example.com/office");
    }
}
