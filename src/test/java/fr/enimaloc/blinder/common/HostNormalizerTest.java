package fr.enimaloc.blinder.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HostNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "go.example.com, go.example.com",
            "Go.Example.COM, go.example.com",
            "go.example.com., go.example.com",
            "go.example.com:8080, go.example.com",
            "'  GO.example.com.:443  ', go.example.com",
            "localhost, localhost",
            "[::1]:8080, [::1]"
    })
    void normalizes(String raw, String expected) {
        assertThat(HostNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @Test
    void nullBecomesEmpty() {
        assertThat(HostNormalizer.normalize(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"go.example.com", "localhost", "a-b.c0.io", "xn--bcher-kva.example"})
    void acceptsValidHosts(String host) {
        assertThat(HostNormalizer.isValid(host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-bad.com", "bad-.com", "a..b", "exa mple.com", "http://x.com", "x.com/path"})
    void rejectsInvalidHosts(String host) {
        assertThat(HostNormalizer.isValid(host)).isFalse();
    }
}
