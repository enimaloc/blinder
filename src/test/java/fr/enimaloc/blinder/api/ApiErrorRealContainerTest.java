package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.support.TestData;
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

/**
 * MockMvc n'exécute jamais de dispatch ERROR : ici une vraie requête rejetée par le pare-feu HTTP de Spring Security
 * ({@code sendError(400)}) passe par {@code /error} dans le conteneur, et doit ressortir en problème JSON sous l'API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestData.class)
class ApiErrorRealContainerTest {

    @LocalServerPort
    int port;
    @Autowired
    TestData data;

    HttpClient client;

    @BeforeEach
    void setUp() {
        data.reset();
        data.admin("admin"); // setup terminé : sinon tout est redirigé vers /_/setup
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "*/*").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void firewallRejectionUnderTheApiIsAProblemThroughTheErrorDispatch() throws Exception {
        HttpResponse<String> response = get("/_/api/v1/links;x=1");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body())
                .contains("\"status\":400")
                .contains("\"detail\"")
                .doesNotContain("Exception", "exception", "trace", "at org.", "at java.", "RequestRejected");
    }

    @Test
    void firewallRejectionOutsideTheApiIsNotAProblem() throws Exception {
        HttpResponse<String> response = get("/abc;x=1");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).doesNotStartWith("application/problem+json"));
    }
}
