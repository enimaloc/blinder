package fr.enimaloc.blinder.notification;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Validation de l'attente du verrou de déclencheur : zéro (pas d'attente) accepté, négatif refusé. */
class NotificationPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private NotificationProperties withLockWait(Duration wait) {
        return new NotificationProperties("blinder@localhost", new NotificationProperties.Denials(10,
                Duration.ofMinutes(10), Duration.ofHours(1)), wait);
    }

    @Test
    void zeroAndPositiveLockWaitsAreAccepted() {
        assertThat(validator.validate(withLockWait(Duration.ZERO))).isEmpty();
        assertThat(validator.validate(withLockWait(Duration.ofSeconds(2)))).isEmpty();
    }

    @Test
    void negativeLockWaitIsRejected() {
        assertThat(validator.validate(withLockWait(Duration.ofMillis(-1))))
                .singleElement().satisfies(v -> assertThat(v.getPropertyPath().toString())
                        .isEqualTo("triggerLockWait"));
    }
}
