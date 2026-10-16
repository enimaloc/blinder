package fr.enimaloc.blinder.stats;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param retention     durée de conservation des visites détaillées ; zéro = illimitée
 * @param queueCapacity taille de la file d'écriture des visites
 * @param flushInterval délai maximal avant écriture d'un lot de visites
 */
@Validated
@ConfigurationProperties("blinder.stats")
public record StatsProperties(
        @DefaultValue("90d") Duration retention,
        @DefaultValue("10000") @Min(1) int queueCapacity,
        @DefaultValue("1s") Duration flushInterval) {
}
