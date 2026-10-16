package fr.enimaloc.blinder.access;

import fr.enimaloc.blinder.common.BlinderProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
public class BotDetector {

    private final List<String> needles;

    public BotDetector(BlinderProperties properties) {
        this.needles = properties.bots().userAgents().stream()
                .filter(Objects::nonNull)
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public boolean isBot(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return false;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        return needles.stream().anyMatch(ua::contains);
    }
}
