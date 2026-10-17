package fr.enimaloc.blinder.deploy;

import fr.enimaloc.blinder.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Santé sur le port de management interne, avant et après le setup admin ; rien d'autre n'est exposé. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestData.class)
class ActuatorHealthTest {

    @LocalServerPort
    int port;
    @LocalManagementPort
    int managementPort;
    @Autowired
    TestData data;

    HttpClient client;

    @BeforeEach
    void setUp() {
        data.reset();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> get(int onPort, String path, String host) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + onPort + path));
        if (host != null) {
            request.header("Host", host);
        }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void managementPortIsDistinctFromPublicPort() {
        assertThat(managementPort).isNotEqualTo(port);
    }

    @Test
    void healthIsUpAnonymouslyBeforeSetup() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/health", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components");
    }

    @Test
    void healthIsUpAnonymouslyAfterSetupWhateverTheHost() throws Exception {
        data.admin("admin");
        assertThat(get(managementPort, "/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health", "go.example.com").statusCode()).isEqualTo(200);
    }

    @Test
    void probesAreEnabled() throws Exception {
        assertThat(get(managementPort, "/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
    }

    @Test
    void healthIsNotServedOnPublicPort() throws Exception {
        assertThat(get(port, "/actuator/health", null).statusCode()).isEqualTo(404);
        data.admin("admin");
        assertThat(get(port, "/actuator/health", null).statusCode()).isEqualTo(404);
    }

    @Test
    void otherEndpointsAreNotExposedOnManagementPort() throws Exception {
        assertThat(get(managementPort, "/actuator/env", null).statusCode()).isEqualTo(404);
        assertThat(get(managementPort, "/actuator/beans", null).statusCode()).isEqualTo(404);
    }
}
