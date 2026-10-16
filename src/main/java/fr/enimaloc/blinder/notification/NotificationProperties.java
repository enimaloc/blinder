package fr.enimaloc.blinder.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("blinder.notifications")
public record NotificationProperties(
        @DefaultValue("blinder@localhost") String mailFrom,
        @DefaultValue @Valid Denials denials) {

    /**
     * @param threshold nombre de refus déclenchant l'alerte
     * @param window    fenêtre glissante de comptage
     * @param cooldown  silence après une alerte
     */
    public record Denials(
            @DefaultValue("10") @Min(1) int threshold,
            @DefaultValue("10m") Duration window,
            @DefaultValue("1h") Duration cooldown) {
    }
}
