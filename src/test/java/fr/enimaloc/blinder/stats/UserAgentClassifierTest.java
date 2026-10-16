package fr.enimaloc.blinder.stats;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserAgentClassifierTest {

    private static UserAgentClassifier.Result of(String ua) {
        return UserAgentClassifier.classify(ua);
    }

    @Test
    void chromeOnWindows() {
        assertThat(of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"))
                .isEqualTo(new UserAgentClassifier.Result("Chrome", "Windows"));
    }

    @Test
    void safariOnIphone() {
        assertThat(of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"))
                .isEqualTo(new UserAgentClassifier.Result("Safari", "iOS"));
    }

    @Test
    void firefoxOnLinux() {
        assertThat(of("Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0"))
                .isEqualTo(new UserAgentClassifier.Result("Firefox", "Linux"));
    }

    @Test
    void edgeOnWindows() {
        assertThat(of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"))
                .isEqualTo(new UserAgentClassifier.Result("Edge", "Windows"));
    }

    @Test
    void chromeOnAndroid() {
        assertThat(of("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"))
                .isEqualTo(new UserAgentClassifier.Result("Chrome", "Android"));
    }

    @Test
    void operaOnMac() {
        assertThat(of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 OPR/111.0.0.0"))
                .isEqualTo(new UserAgentClassifier.Result("Opera", "macOS"));
    }

    @Test
    void chromeOnChromeOs() {
        assertThat(of("Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"))
                .isEqualTo(new UserAgentClassifier.Result("Chrome", "ChromeOS"));
    }

    @Test
    void firefoxOnIosAndChromeOnIos() {
        assertThat(of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 FxiOS/127.0 Mobile/15E148 Safari/605.1.15").browser())
                .isEqualTo("Firefox");
        assertThat(of("Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 CriOS/126.0 Mobile/15E148 Safari/604.1"))
                .isEqualTo(new UserAgentClassifier.Result("Chrome", "iOS"));
    }

    @Test
    void unknownAgents() {
        assertThat(of("curl/8.5.0")).isEqualTo(new UserAgentClassifier.Result("Autre", "Autre"));
        assertThat(of(null)).isEqualTo(new UserAgentClassifier.Result("Autre", "Autre"));
        assertThat(of("  ")).isEqualTo(new UserAgentClassifier.Result("Autre", "Autre"));
    }
}
