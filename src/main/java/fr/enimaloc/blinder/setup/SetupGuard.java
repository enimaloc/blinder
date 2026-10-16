package fr.enimaloc.blinder.setup;

import fr.enimaloc.blinder.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SetupGuard {

    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();
    private volatile String token;

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void refresh() {
        if (users.count() == 0 && token == null) {
            byte[] bytes = new byte[24];
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            log.warn("Blinder n'est pas configuré. Ouvrez /_/setup avec le jeton : {}", token);
        }
    }

    /** Vrai tant qu'aucun utilisateur n'existe et qu'un jeton a été émis. */
    public boolean isSetupPending() {
        if (token == null) {
            return false;
        }
        if (users.count() > 0) {
            invalidate();
            return false;
        }
        return true;
    }

    public boolean tokenMatches(String candidate) {
        String current = token;
        return current != null && candidate != null
                && MessageDigest.isEqual(current.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void invalidate() {
        token = null;
    }

    /** Exposé pour les tests d'intégration ; ne jamais afficher dans une vue. */
    public Optional<String> currentToken() {
        return Optional.ofNullable(token);
    }
}
