package fr.enimaloc.blinder.api.token;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.device.DeviceTokens;
import fr.enimaloc.blinder.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ApiTokenService {

    public static final String PREFIX = "bl_";
    private static final Pattern FORMAT = Pattern.compile("bl_[A-Za-z0-9_-]{43}");
    private static final int DISPLAY_PREFIX_LENGTH = 10;
    private static final int MAX_NAME_LENGTH = 100;
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final ApiTokenRepository tokens;
    private final Clock clock;

    @Transactional
    public CreatedApiToken create(User owner, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new FieldErrorException("name", "1 à 100 caractères");
        }
        String raw = PREFIX + DeviceTokens.newToken();
        ApiToken token = tokens.save(new ApiToken(owner, trimmed, raw.substring(0, DISPLAY_PREFIX_LENGTH),
                DeviceTokens.sha256(raw)));
        return new CreatedApiToken(token, raw);
    }

    @Transactional(readOnly = true)
    public List<ApiToken> list(User owner) {
        return tokens.findByOwnerOrderByCreatedAtDesc(owner);
    }

    @Transactional
    public void revoke(User owner, UUID id) {
        ApiToken token = tokens.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        tokens.delete(token);
    }

    /** Propriétaire du jeton brut, vide si le format est invalide ou le jeton inconnu (révoqué). */
    @Transactional
    public Optional<User> authenticate(String raw) {
        if (raw == null || !FORMAT.matcher(raw).matches()) {
            return Optional.empty();
        }
        return tokens.findByTokenHash(DeviceTokens.sha256(raw)).map(token -> {
            User owner = token.getOwner();
            Instant now = clock.instant();
            Instant notAfter = now.minus(TOUCH_INTERVAL);
            if (token.getLastUsedAt() == null || token.getLastUsedAt().isBefore(notAfter)) {
                tokens.touch(token.getId(), now, notAfter);
            }
            return owner;
        });
    }
}
