package fr.enimaloc.blinder.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UrlPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "http://example.com/a?b=c#d", "HTTPS://Example.com:8443/x"})
    void acceptsAbsoluteHttpUrls(String url) {
        assertThat(UrlPolicy.isHttpUrl(url)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"javascript:alert(1)", "//evil.com", "/relative", "ftp://example.com",
            "https://exa mple.com", "data:text/html,hi", "https://", "  "})
    void rejectsEverythingElse(String url) {
        assertThat(UrlPolicy.isHttpUrl(url)).isFalse();
    }
}
