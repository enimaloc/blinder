package fr.enimaloc.blinder.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("blinder.rate-limit")
public record RateLimitProperties(@DefaultValue @Valid LinkPassword linkPassword,
                                  @DefaultValue @Valid AdminLogin adminLogin) {

    public record LinkPassword(@DefaultValue("5") @Min(1) int maxFailures,
                               @DefaultValue("15m") Duration window) {
    }

    public record AdminLogin(@DefaultValue("10") @Min(1) int maxFailures,
                             @DefaultValue("15m") Duration window) {
    }
}
