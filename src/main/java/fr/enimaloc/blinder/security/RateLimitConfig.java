package fr.enimaloc.blinder.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    private static final int MAX_KEYS = 10_000;

    @Bean
    RateLimiters rateLimiters(RateLimitProperties properties, Clock clock) {
        // Valider que les fenêtres sont strictement positives
        validateWindow("blinder.rate-limit.link-password.window", properties.linkPassword().window());
        validateWindow("blinder.rate-limit.admin-login.window", properties.adminLogin().window());
        validateWindow("blinder.rate-limit.api-auth.window", properties.apiAuth().window());

        return new RateLimiters(
                new RateLimiter(properties.linkPassword().maxFailures(), properties.linkPassword().window(),
                        clock, MAX_KEYS),
                new RateLimiter(properties.linkPassword().aggregateMaxFailures(), properties.linkPassword().window(),
                        clock, MAX_KEYS),
                new RateLimiter(properties.adminLogin().maxFailures(), properties.adminLogin().window(),
                        clock, MAX_KEYS),
                new RateLimiter(properties.apiAuth().maxFailures(), properties.apiAuth().window(),
                        clock, MAX_KEYS));
    }

    private void validateWindow(String propertyName, Duration window) {
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalStateException(propertyName + " must be strictly positive, got " + window);
        }
    }
}
