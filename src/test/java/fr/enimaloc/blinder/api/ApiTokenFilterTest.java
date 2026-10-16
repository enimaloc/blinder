package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.api.token.ApiTokenService;
import fr.enimaloc.blinder.security.RateLimiter;
import fr.enimaloc.blinder.support.TestJson;
import fr.enimaloc.blinder.user.Role;
import fr.enimaloc.blinder.user.User;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiTokenFilterTest {

    private final ApiTokenService tokens = mock(ApiTokenService.class);
    private final RateLimiter failures = new RateLimiter(2, Duration.ofMinutes(15), Clock.systemUTC(), 100);
    private final JsonMapper json = TestJson.mapper();
    private final SecurityContextRepository repository = new RequestAttributeSecurityContextRepository();
    private final ApiTokenFilter filter = new ApiTokenFilter(tokens, failures,
            ApiProblems.unauthorized(new ProblemWriter(json)), new ProblemWriter(json), repository);

    private MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/_/api/v1/domains");
        request.setRemoteAddr("10.77.0.1");
        request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        return request;
    }

    @Test
    void validTokenSavesTheContextInTheRepositoryForRedispatch() throws Exception {
        when(tokens.authenticate("good")).thenReturn(Optional.of(new User("admin", "{noop}x", Role.ADMIN)));
        MockHttpServletRequest request = request("Bearer good");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        SecurityContext saved = repository.loadDeferredContext(request).get();
        assertThat(saved.getAuthentication()).isNotNull();
        assertThat(saved.getAuthentication().isAuthenticated()).isTrue();
        SecurityContextHolder.clearContext();
    }

    @Test
    void blockedAddressGetsAProblemBodyBuiltByTheMapper() throws Exception {
        when(tokens.authenticate(any())).thenReturn(Optional.empty());
        for (int i = 0; i < 2; i++) {
            filter.doFilter(request("Bearer bad"), new MockHttpServletResponse(), mock(FilterChain.class));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("Bearer bad"), response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        var body = json.readTree(response.getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("title").asString()).isEqualTo("Too Many Requests");
        assertThat(body.get("detail").asString()).contains("Trop de jetons refusés");
    }
}
