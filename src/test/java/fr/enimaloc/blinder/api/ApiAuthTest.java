package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.api.token.ApiToken;
import fr.enimaloc.blinder.api.token.ApiTokenRepository;
import fr.enimaloc.blinder.device.DeviceTokens;
import fr.enimaloc.blinder.security.RateLimiters;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class ApiAuthTest {

    private static final String DOMAINS = "/_/api/v1/domains";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    ApiTokenRepository tokens;
    @Autowired
    RateLimiters rateLimiters;

    User admin;
    String token;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        data.domain("go.example.com", admin);
        data.domain("other.example.com", data.admin("other"));
        token = data.apiToken(admin, "ci");
    }

    @AfterEach
    void clear() {
        rateLimiters.apiAuth().clear();
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, String raw) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + raw);
    }

    @Test
    void missingHeaderIs401ProblemWithBearerChallenge() throws Exception {
        mvc.perform(from(get(DOMAINS), "10.9.0.1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void unknownOrMalformedTokenIs401() throws Exception {
        mvc.perform(bearer(from(get(DOMAINS), "10.9.0.2"), "bl_" + DeviceTokens.newToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(bearer(from(get(DOMAINS), "10.9.0.2"), "nope"))
                .andExpect(status().isUnauthorized());
        mvc.perform(from(get(DOMAINS), "10.9.0.2").header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46eA=="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenListsOnlyOwnersDomains() throws Exception {
        mvc.perform(bearer(from(get(DOMAINS), "10.9.0.3"), token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].host").value("go.example.com"));
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        mvc.perform(from(get(DOMAINS), "10.9.0.4").header(HttpHeaders.AUTHORIZATION, "bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void revokedTokenIs401() throws Exception {
        tokens.deleteAll();
        mvc.perform(bearer(from(get(DOMAINS), "10.9.0.5"), token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminSessionDoesNotOpenTheApi() throws Exception {
        MvcResult login = mvc.perform(formLogin("/_/login").user("admin").password(TestData.PASSWORD))
                .andExpect(authenticated())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/_/").session(session)).andExpect(status().isOk());
        mvc.perform(from(get(DOMAINS), "10.9.0.6").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void apiCallsNeverCreateASession() throws Exception {
        MvcResult result = mvc.perform(bearer(from(get(DOMAINS), "10.9.0.7"), token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void postWithTokenNeedsNoCsrf() throws Exception {
        MvcResult result = mvc.perform(bearer(from(post(DOMAINS), "10.9.0.8"), token)).andReturn();
        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    void encodedPrefixIsStillTheApiChain() throws Exception {
        mvc.perform(from(get(URI.create("http://go.example.com/%5F/api/v1/domains")), "10.9.0.9"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void afterTwentyFailuresEvenAGoodTokenIs429() throws Exception {
        String ip = "10.9.1.1";
        for (int i = 0; i < 20; i++) {
            mvc.perform(bearer(from(get(DOMAINS), ip), "bl_" + DeviceTokens.newToken()))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(bearer(from(get(DOMAINS), ip), "bl_" + DeviceTokens.newToken()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.detail").value("Trop de jetons refusés depuis cette adresse, réessayez plus tard"));
        mvc.perform(bearer(from(get(DOMAINS), ip), token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        // Une autre IP n'est pas concernée.
        mvc.perform(bearer(from(get(DOMAINS), "10.9.1.2"), token)).andExpect(status().isOk());
    }

    @Test
    void requestsWithoutHeaderAreNotCounted() throws Exception {
        String ip = "10.9.2.1";
        for (int i = 0; i < 25; i++) {
            mvc.perform(from(get(DOMAINS), ip)).andExpect(status().isUnauthorized());
        }
        mvc.perform(bearer(from(get(DOMAINS), ip), token)).andExpect(status().isOk());
    }

    @Test
    void lastUsedAtIsRecorded() throws Exception {
        assertThat(tokens.findAll()).singleElement().extracting(ApiToken::getLastUsedAt).isNull();
        mvc.perform(bearer(from(get(DOMAINS), "10.9.3.1"), token)).andExpect(status().isOk());
        assertThat(tokens.findAll()).singleElement().extracting(ApiToken::getLastUsedAt).isNotNull();
    }

    @Test
    void springDataRestIsGone() throws Exception {
        mvc.perform(from(get("/_/api/profile"), "10.9.4.1")).andExpect(status().isUnauthorized());
        MvcResult result = mvc.perform(bearer(from(get("/_/api/profile"), "10.9.4.1"), token)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }
}
