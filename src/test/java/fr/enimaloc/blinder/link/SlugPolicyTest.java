package fr.enimaloc.blinder.link;

import fr.enimaloc.blinder.common.BlinderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SlugPolicyTest {

    final SlugPolicy policy = new SlugPolicy(new BlinderProperties(
            new BlinderProperties.Admin(null, ZoneOffset.UTC),
            new BlinderProperties.Slug(7, List.of("favicon.ico", "robots.txt", ".well-known", "error")),
            new BlinderProperties.Bots(List.of())));

    @Test
    void normalizesToTrimmedLowercase() {
        assertThat(policy.normalize("  PrOmO ")).isEqualTo("promo");
        assertThat(policy.normalize(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"promo", "a", "0", "my-link", "v1.2_final", "profile"})
    void acceptsValidSlugs(String slug) {
        assertThat(policy.validate(slug)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "_admin", "-x", ".hidden", "a/b", "é", "with space"})
    void rejectsInvalidFormat(String slug) {
        assertThat(policy.validate(slug)).contains(SlugError.INVALID_FORMAT);
    }

    @Test
    void rejectsTooLong() {
        assertThat(policy.validate("a".repeat(65))).contains(SlugError.INVALID_FORMAT);
        assertThat(policy.validate("a".repeat(64))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"favicon.ico", "robots.txt", "error"})
    void rejectsReserved(String slug) {
        assertThat(policy.validate(slug)).contains(SlugError.RESERVED);
    }

    @Test
    void generatesValidBase36SlugsOfConfiguredLength() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String slug = policy.generate();
            assertThat(slug).hasSize(7).matches("[a-z0-9]{7}");
            assertThat(policy.validate(slug)).isEmpty();
            seen.add(slug);
        }
        assertThat(seen).hasSizeGreaterThan(195);
    }
}
