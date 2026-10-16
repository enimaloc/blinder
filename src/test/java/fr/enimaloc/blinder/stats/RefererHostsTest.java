package fr.enimaloc.blinder.stats;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefererHostsTest {

    @Test
    void lowercasesHostOfHttpUrls() {
        assertThat(RefererHosts.hostOf("https://News.Example/x?y=1")).isEqualTo("news.example");
        assertThat(RefererHosts.hostOf("http://a.b:8080/")).isEqualTo("a.b");
    }

    @Test
    void rejectsOtherSchemesAndGarbage() {
        assertThat(RefererHosts.hostOf("android-app://com.google.android.gm")).isNull();
        assertThat(RefererHosts.hostOf("not a url")).isNull();
        assertThat(RefererHosts.hostOf("https://")).isNull();
        assertThat(RefererHosts.hostOf("")).isNull();
        assertThat(RefererHosts.hostOf(null)).isNull();
    }
}
