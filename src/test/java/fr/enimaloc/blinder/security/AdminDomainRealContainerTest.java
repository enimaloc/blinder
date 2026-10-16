package fr.enimaloc.blinder.security;

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

/**
 * MockMvc ne peut pas exécuter les forwards : ici de vraies requêtes HTTP traversent le conteneur
 * pour prouver que le chemin forwardé vers /_/ est bien soumis à la chaîne de sécurité admin.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestData.class)
@TestPropertySource(properties = "blinder.admin.domain=admin.example.com")
class AdminDomainRealContainerTest {

    @LocalServerPort
    int port;
    @Autowired
    TestData data;

    HttpClient client;

    @BeforeAll
    static void allowHostHeader() {
        // À positionner avant la première création d'un HttpClient dans la JVM.
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    @BeforeEach
    void setUp() {
        data.reset();
        data.admin("admin"); // setup terminé : sinon tout est redirigé vers /_/setup
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> get(String host, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Host", host).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anonymousRequestsOnAdminDomainAreRedirectedToLogin() throws Exception {
        for (String path : new String[]{"/", "/promo", "/links/new", "/api"}) {
            HttpResponse<String> response = get("admin.example.com", path);
            assertThat(response.statusCode()).as(path).isEqualTo(302);
            assertThat(response.headers().firstValue("Location")).as(path).hasValueSatisfying(
                    location -> assertThat(location).endsWith("/_/login"));
        }
    }

    @Test
    void loginPageOnAdminDomainIsPublic() throws Exception {
        assertThat(get("admin.example.com", "/login").statusCode()).isEqualTo(200);
    }

    @Test
    void unknownSlugOnPublicDomainIsNotFound() throws Exception {
        assertThat(get("go.example.com", "/unknown").statusCode()).isEqualTo(404);
    }
}
