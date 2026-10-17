package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.link.Link;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectPolicyTest {

    static Link link(String target) {
        return new Link(null, "x", target, null);
    }

    static Map<String, String[]> params(String... pairs) {
        Map<String, String[]> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.merge(pairs[i], new String[]{pairs[i + 1]}, (a, b) -> {
                String[] merged = new String[a.length + b.length];
                System.arraycopy(a, 0, merged, 0, a.length);
                System.arraycopy(b, 0, merged, a.length, b.length);
                return merged;
            });
        }
        return map;
    }

    @Test
    void bareLinkHasNoLimitDateNorProtection() {
        assertThat(RedirectPolicy.isBare(link("https://e.com"))).isTrue();
        Link limited = link("https://e.com");
        limited.setMaxUses(1);
        assertThat(RedirectPolicy.isBare(limited)).isFalse();
        Link ttl = link("https://e.com");
        ttl.setTtlAfterFirstUse(Duration.ofHours(1));
        assertThat(RedirectPolicy.isBare(ttl)).isFalse();
        Link dated = link("https://e.com");
        dated.setExpiresAt(Instant.now());
        assertThat(RedirectPolicy.isBare(dated)).isFalse();
        Link scheduled = link("https://e.com");
        scheduled.setNotBefore(Instant.now());
        assertThat(RedirectPolicy.isBare(scheduled)).isFalse();
        Link password = link("https://e.com");
        password.setPasswordHash("{bcrypt}x");
        assertThat(RedirectPolicy.isBare(password)).isFalse();
        Link allowlist = link("https://e.com");
        allowlist.setIpAllowlist("10.0.0.0/8");
        assertThat(RedirectPolicy.isBare(allowlist)).isFalse();
        Link devices = link("https://e.com");
        devices.setDeviceProtected(true);
        assertThat(RedirectPolicy.isBare(devices)).isFalse();
    }

    @Test
    void effectiveStatusFallsBackToFoundUnlessBare() {
        Link bare = link("https://e.com");
        bare.setRedirectStatus(301);
        assertThat(RedirectPolicy.effectiveStatus(bare, "GET")).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
        bare.setRedirectStatus(308);
        assertThat(RedirectPolicy.effectiveStatus(bare, "GET")).isEqualTo(HttpStatus.PERMANENT_REDIRECT);
        bare.setRedirectStatus(307);
        assertThat(RedirectPolicy.effectiveStatus(bare, "GET")).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
        bare.setRedirectStatus(302);
        assertThat(RedirectPolicy.effectiveStatus(bare, "GET")).isEqualTo(HttpStatus.FOUND);
        bare.setRedirectStatus(999);
        assertThat(RedirectPolicy.effectiveStatus(bare, "GET")).isEqualTo(HttpStatus.FOUND);

        Link limited = link("https://e.com");
        limited.setMaxUses(3);
        limited.setRedirectStatus(301);
        assertThat(RedirectPolicy.effectiveStatus(limited, "GET")).isEqualTo(HttpStatus.FOUND);
        limited.setRedirectStatus(308);
        assertThat(RedirectPolicy.effectiveStatus(limited, "GET")).isEqualTo(HttpStatus.FOUND);
        limited.setRedirectStatus(307);
        assertThat(RedirectPolicy.effectiveStatus(limited, "GET")).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
    }

    @Test
    void headerCheckedLinkAlwaysRedirectsTemporarily() {
        Link bare = link("https://e.com");
        bare.setRedirectStatus(301);
        assertThat(RedirectPolicy.effectiveStatus(bare, "GET", true)).isEqualTo(HttpStatus.FOUND);
        assertThat(RedirectPolicy.effectiveStatus(bare, "GET", false)).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
        bare.setRedirectStatus(308);
        assertThat(RedirectPolicy.effectiveStatus(bare, "GET", true)).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(RedirectPolicy.effectiveStatus(bare, "GET", false)).isEqualTo(HttpStatus.PERMANENT_REDIRECT);
        bare.setRedirectStatus(307);
        assertThat(RedirectPolicy.effectiveStatus(bare, "GET", true)).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
        bare.setRedirectStatus(302);
        assertThat(RedirectPolicy.effectiveStatus(bare, "GET", true)).isEqualTo(HttpStatus.FOUND);
        assertThat(RedirectPolicy.effectiveStatus(bare, "POST", true)).isEqualTo(HttpStatus.SEE_OTHER);

        Link limited = link("https://e.com");
        limited.setMaxUses(3);
        limited.setRedirectStatus(308);
        assertThat(RedirectPolicy.effectiveStatus(limited, "GET", true)).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
    }

    @Test
    void postIsAlwaysAnsweredWithSeeOther() {
        Link bare = link("https://e.com");
        for (int status : new int[]{301, 302, 307, 308}) {
            bare.setRedirectStatus(status);
            assertThat(RedirectPolicy.effectiveStatus(bare, "POST")).isEqualTo(HttpStatus.SEE_OTHER);
        }
    }

    @Test
    void queryIsNotForwardedUnlessEnabled() {
        assertThat(RedirectPolicy.target(link("https://e.com/p"), params("a", "1"))).isEqualTo("https://e.com/p");
    }

    @Test
    void forwardsQueryTargetWinsAndSecretsAreDropped() {
        Link link = link("https://e.com/p?ref=keep");
        link.setForwardQuery(true);
        assertThat(RedirectPolicy.target(link,
                params("ref", "override", "utm_source", "news", "pw", "s", "password", "s", "_csrf", "t")))
                .isEqualTo("https://e.com/p?ref=keep&utm_source=news");
    }

    @Test
    void forwardsBeforeFragmentAndEncodes() {
        Link link = link("https://e.com/p#section");
        link.setForwardQuery(true);
        assertThat(RedirectPolicy.target(link, params("q", "a b{é}#", "multi", "1", "multi", "2")))
                .isEqualTo("https://e.com/p?q=a+b%7B%C3%A9%7D%23&multi=1&multi=2#section");
    }

    @Test
    void encodedTargetParameterNamesAreRecognised() {
        Link link = link("https://e.com/p?utm%5Fsource=keep");
        link.setForwardQuery(true);
        assertThat(RedirectPolicy.target(link, params("utm_source", "x")))
                .isEqualTo("https://e.com/p?utm%5Fsource=keep");
    }
}
