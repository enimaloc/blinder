package fr.enimaloc.blinder.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Désactivable ({@code blinder.scheduling.enabled=false}) pour que les tests ne subissent pas les tâches planifiées. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "blinder.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
