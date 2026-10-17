package fr.enimaloc.blinder.link.check;

import fr.enimaloc.blinder.common.FieldErrorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeaderCheckRulesTest {

    @Test
    void bothBlankMeansNoCheck() {
        assertThat(HeaderCheckRules.normalize(null, null)).isEmpty();
        assertThat(HeaderCheckRules.normalize("  ", "")).isEmpty();
    }

    @Test
    void trimsNameAndValue() {
        HeaderCheckRules.Normalized n = HeaderCheckRules.normalize("  X-Token ", " abc  ").orElseThrow();
        assertThat(n.name()).isEqualTo("X-Token");
        assertThat(n.value()).isEqualTo("abc");
    }

    @Test
    void acceptsEveryTokenCharacter() {
        assertThat(HeaderCheckRules.normalize("!#$%&'*+-.^_`|~aZ09", "v")).isPresent();
    }

    @Test
    void oneFieldOnlyIsAnErrorOnTheEmptyOne() {
        assertThatThrownBy(() -> HeaderCheckRules.normalize("X-Token", " "))
                .isInstanceOfSatisfying(FieldErrorException.class, e -> {
                    assertThat(e.field()).isEqualTo("headerValue");
                    assertThat(e.getMessage()).startsWith("Indiquez aussi");
                });
        assertThatThrownBy(() -> HeaderCheckRules.normalize(null, "abc"))
                .isInstanceOfSatisfying(FieldErrorException.class, e -> {
                    assertThat(e.field()).isEqualTo("headerName");
                    assertThat(e.getMessage()).startsWith("Indiquez aussi");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"X Token", "X:Token", "Été", "a,b", "a(b)", "x/y", "a\"b", "a@b", "a\r\nb"})
    void rejectsNonTokenNames(String name) {
        assertThatThrownBy(() -> HeaderCheckRules.normalize(name, "v"))
                .isInstanceOfSatisfying(FieldErrorException.class, e -> {
                    assertThat(e.field()).isEqualTo("headerName");
                    assertThat(e.getMessage()).isEqualTo("Nom d'en-tête invalide");
                });
    }

    @Test
    void enforcesNameLength() {
        assertThat(HeaderCheckRules.normalize("a".repeat(100), "v")).isPresent();
        assertThatThrownBy(() -> HeaderCheckRules.normalize("a".repeat(101), "v"))
                .isInstanceOf(FieldErrorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\rb", "a\nb", "a\r\nX-Evil: 1", "a\u0000b", "a\tb", "a\u007fb", "abc\n"})
    void rejectsControlCharactersInValue(String value) {
        assertThatThrownBy(() -> HeaderCheckRules.normalize("X-Token", value))
                .isInstanceOfSatisfying(FieldErrorException.class, e -> {
                    assertThat(e.field()).isEqualTo("headerValue");
                    assertThat(e.getMessage()).isEqualTo("Valeur invalide");
                });
    }

    @Test
    void enforcesValueLength() {
        assertThat(HeaderCheckRules.normalize("X", "v".repeat(500))).isPresent();
        assertThatThrownBy(() -> HeaderCheckRules.normalize("X", "v".repeat(501)))
                .isInstanceOf(FieldErrorException.class);
    }
}
