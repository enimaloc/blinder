package fr.enimaloc.blinder.support;

import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mapper des tests unitaires hors contexte Spring : même forme JSON que celui de l'application pour
 * {@link ProblemDetail} (mixin de Spring, qui met les propriétés d'extension à plat), au lieu d'un mapper nu.
 */
public final class TestJson {

    private TestJson() {
    }

    public static JsonMapper mapper() {
        return JsonMapper.builder().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();
    }
}
