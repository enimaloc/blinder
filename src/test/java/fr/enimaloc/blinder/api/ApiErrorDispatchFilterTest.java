package fr.enimaloc.blinder.api;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import fr.enimaloc.blinder.support.TestJson;

import static org.assertj.core.api.Assertions.assertThat;

/** Erreurs du conteneur (re-dispatch vers /error) : problème JSON sous /_/api/, page d'erreur habituelle ailleurs. */
class ApiErrorDispatchFilterTest {

    private final ApiErrorDispatchFilter filter = new ApiErrorDispatchFilter(new ProblemWriter(TestJson.mapper()));

    private MockHttpServletRequest errorRequest(String originalUri, int status) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, originalUri);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        return request;
    }

    @Test
    void containerErrorUnderApiIsAProblem() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(errorRequest("/_/api/v1/links", 500), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"status\":500").contains("Erreur interne");
    }

    @Test
    void encodedApiPrefixIsRecognised() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(errorRequest("/%5F/api/v1/x", 404), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }

    @Test
    void errorsOutsideTheApiAreLeftAlone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(errorRequest("/abc", 404), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getContentAsString()).isEmpty();
    }
}
