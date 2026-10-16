package fr.enimaloc.blinder.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    private static final int MAX_KEYS = 10_000;

    @Bean
    RateLimiters rateLimiters(RateLimitProperties properties, Clock clock) {
        return new RateLimiters(
                new RateLimiter(properties.linkPassword().maxFailures(), properties.linkPassword().window(),
                        clock, MAX_KEYS),
                new RateLimiter(properties.adminLogin().maxFailures(), properties.adminLogin().window(),
                        clock, MAX_KEYS));
    }
}
