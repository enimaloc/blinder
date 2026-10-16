package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.common.StaleEditException;
import fr.enimaloc.blinder.support.TestData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestData.class, ApiProblemsTest.ProblemController.class})
class ApiProblemsTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;

    String token;

    @BeforeEach
    void setUp() {
        data.reset();
        token = data.apiToken(data.admin("admin"), "ci");
    }

    private ResultActions perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void fieldErrorIs400WithErrors() throws Exception {
        perform(get("/_/api/test/field"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.slug").value("Slug invalide"));
    }

    @Test
    void beanValidationIs400WithErrors() throws Exception {
        perform(post("/_/api/test/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Nom requis"));
    }

    @Test
    void unreadableBodyIs400() throws Exception {
        perform(post("/_/api/test/valid").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Corps de requête illisible"));
    }

    @Test
    void responseStatusKeepsItsStatus() throws Exception {
        perform(get("/_/api/test/missing")).andExpect(status().isNotFound());
    }

    @Test
    void staleAndOptimisticLockAre409() throws Exception {
        perform(get("/_/api/test/stale")).andExpect(status().isConflict());
        perform(get("/_/api/test/optimistic")).andExpect(status().isConflict());
    }

    @Test
    void integrityViolationIsGeneric409() throws Exception {
        perform(get("/_/api/test/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void unknownRouteIs404Problem() throws Exception {
        perform(get("/_/api/v1/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Introuvable"));
    }

    @Test
    void methodNotAllowedIs405ProblemWithAllowHeader() throws Exception {
        perform(post("/_/api/test/missing"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void unexpectedExceptionIs500WithoutInternalDetail() throws Exception {
        perform(get("/_/api/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(content().string(not(containsString("secret internal detail"))))
                .andExpect(jsonPath("$.detail").value("Erreur interne"));
    }

    @Test
    void unparsableQueryParametersAre400Problems() throws Exception {
        perform(get("/_/api/v1/stats").param("link", "not-a-uuid")).andExpect(status().isBadRequest());
        perform(get("/_/api/v1/stats").param("tag", "not-a-uuid")).andExpect(status().isBadRequest());
        perform(get("/_/api/v1/stats").param("days", "abc")).andExpect(status().isBadRequest());
        perform(get("/_/api/v1/links").param("domain", "x")).andExpect(status().isBadRequest());
        perform(get("/_/api/v1/links").param("page", "x")).andExpect(status().isBadRequest());
    }

    record Body(@NotBlank(message = "Nom requis") String name) {
    }

    @RestController
    @RequestMapping("/_/api/test")
    static class ProblemController {

        @GetMapping("/field")
        void field() {
            throw new FieldErrorException("slug", "Slug invalide");
        }

        @PostMapping("/valid")
        void valid(@Valid @RequestBody Body body) {
        }

        @GetMapping("/missing")
        void missing() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        @GetMapping("/stale")
        void stale() {
            throw new StaleEditException();
        }

        @GetMapping("/optimistic")
        void optimistic() {
            throw new ObjectOptimisticLockingFailureException(Object.class, "id");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/integrity")
        void integrity() {
            throw new DataIntegrityViolationException("duplicate key");
        }
    }
}
