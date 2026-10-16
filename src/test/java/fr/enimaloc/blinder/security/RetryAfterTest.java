package fr.enimaloc.blinder.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryAfterTest {

    @Test
    void roundsUpAndNeverBelowOneSecond() {
        assertThat(RetryAfter.seconds(Duration.ZERO)).isEqualTo(1);
        assertThat(RetryAfter.seconds(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(RetryAfter.seconds(Duration.ofMillis(1000))).isEqualTo(1);
        assertThat(RetryAfter.seconds(Duration.ofMillis(1001))).isEqualTo(2);
        assertThat(RetryAfter.seconds(Duration.ofMinutes(15))).isEqualTo(900);
    }

    @Test
    void setsTheHeader() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RetryAfter.set(response, Duration.ofMillis(2500));
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
    }
}
