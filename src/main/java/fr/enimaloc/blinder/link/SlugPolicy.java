package fr.enimaloc.blinder.link;

import fr.enimaloc.blinder.common.BlinderProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class SlugPolicy {

    private static final Pattern FORMAT = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private final SecureRandom random = new SecureRandom();
    private final int generatedLength;
    private final Set<String> reserved;

    public SlugPolicy(BlinderProperties properties) {
        this.generatedLength = properties.slug().generatedLength();
        this.reserved = properties.slug().reserved().stream()
                .map(this::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    public String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public Optional<SlugError> validate(String normalized) {
        if (normalized == null || !FORMAT.matcher(normalized).matches()) {
            return Optional.of(SlugError.INVALID_FORMAT);
        }
        if (reserved.contains(normalized)) {
            return Optional.of(SlugError.RESERVED);
        }
        return Optional.empty();
    }

    public String generate() {
        char[] chars = new char[generatedLength];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(chars);
    }
}
