package fr.enimaloc.blinder.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Duration;

/** Stockage portable d'une Duration (Postgres mapperait sinon vers INTERVAL). */
@Converter
public class DurationSecondsConverter implements AttributeConverter<Duration, Long> {

    @Override
    public Long convertToDatabaseColumn(Duration duration) {
        return duration == null ? null : duration.toSeconds();
    }

    @Override
    public Duration convertToEntityAttribute(Long seconds) {
        return seconds == null ? null : Duration.ofSeconds(seconds);
    }
}
