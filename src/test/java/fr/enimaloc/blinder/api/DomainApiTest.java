package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestData.class)
class DomainApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;

    User admin;
    String token;

    @BeforeEach
    void setUp() {
        data.reset();
        admin = data.admin("admin");
        token = data.apiToken(admin, "ci");
    }

    @Test
    void listsOwnDomainsSortedByHostAsIdAndHost() throws Exception {
        Domain b = data.domain("b.example.com", admin);
        Domain a = data.domain("a.example.com", admin);
        data.domain("c.example.com", data.admin("other"));
        mvc.perform(get("/_/api/v1/domains").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(a.getId().toString()))
                .andExpect(jsonPath("$[0].host").value("a.example.com"))
                .andExpect(jsonPath("$[1].id").value(b.getId().toString()))
                .andExpect(jsonPath("$[1].host").value("b.example.com"))
                .andExpect(jsonPath("$[0].owner").doesNotExist());
    }

    @Test
    void emptyListWhenNoDomain() throws Exception {
        mvc.perform(get("/_/api/v1/domains").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
