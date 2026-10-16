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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Le client de test se connecte depuis 127.0.0.1 : il est un proxy de confiance (défaut loopback). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestData.class)
class TrustedProxyTest {

    @LocalServerPort
    int port;
    @Autowired
    TestData data;

    HttpClient client;

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
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> get(String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/office"))
                .header("Host", "go.example.com");
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void forwardedForIsHonouredFromTrustedProxy() throws Exception {
        HttpResponse<String> response = get("203.0.113.9");
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains("https://example.com/office");
    }

    @Test
    void proxyItselfIsNotInAllowlist() throws Exception {
        assertThat(get(null).statusCode()).isEqualTo(404);
    }
}
