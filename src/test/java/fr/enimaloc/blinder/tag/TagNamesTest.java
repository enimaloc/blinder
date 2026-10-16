package fr.enimaloc.blinder.tag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TagNamesTest {

    @Test
    void normalizeTrimsAndCollapsesSpaces() {
        assertThat(TagNames.normalize("  Été   2026 ")).isEqualTo("Été 2026");
        assertThat(TagNames.normalize(null)).isEmpty();
    }

    @Test
    void validateRules() {
        assertThat(TagNames.validate("promo")).isEmpty();
        assertThat(TagNames.validate("a".repeat(40))).isEmpty();
        assertThat(TagNames.validate("a".repeat(41))).contains("40 caractères maximum");
        assertThat(TagNames.validate("   ")).contains("Nom requis");
        assertThat(TagNames.validate("a,b")).contains("Virgule interdite");
    }

    @Test
    void validateRejectsNamesWhoseLowercaseKeyIsTooLong() {
        // "İ".toLowerCase(Locale.ROOT) a deux caractères
        assertThat(TagNames.validate("İ".repeat(21))).contains("40 caractères maximum");
        assertThat(TagNames.validate("İ".repeat(20))).isEmpty();
    }

    @Test
    void parseListNormalizesDropsEmptiesAndDedupesIgnoringCase() {
        assertThat(TagNames.parseList(" promo , Été 2026 ,promo,,Promo ")).containsExactly("promo", "Été 2026");
        assertThat(TagNames.parseList("Promo, promo")).containsExactly("Promo");
        assertThat(TagNames.parseList(null)).isEmpty();
        assertThat(TagNames.parseList("")).isEqualTo(List.of());
    }

    @Test
    void normalizeColor() {
        assertThat(TagNames.normalizeColor("#ABCDEF")).isEqualTo("#abcdef");
        assertThat(TagNames.normalizeColor("")).isNull();
        assertThat(TagNames.normalizeColor("  ")).isNull();
        assertThat(TagNames.normalizeColor(null)).isNull();
        assertThat(TagNames.normalizeColor("#123456")).isEqualTo("#123456");
        assertThatThrownBy(() -> TagNames.normalizeColor("red")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TagNames.normalizeColor("#abc")).isInstanceOf(IllegalArgumentException.class);
    }
}
