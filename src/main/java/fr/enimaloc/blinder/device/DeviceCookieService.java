package fr.enimaloc.blinder.device;

import fr.enimaloc.blinder.common.HostNormalizer;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Cookie {@code blinder_device} : {@code <deviceId>.<secret>}, un secret par (appareil, domaine), seul le hash est stocké. */
@Service
@RequiredArgsConstructor
public class DeviceCookieService {

    public static final String COOKIE = "blinder_device";
    static final Duration MAX_AGE = Duration.ofDays(400);
    private static final Duration TOUCH_INTERVAL = Duration.ofHours(1);

    private final DomainRepository domains;
    private final DeviceRepository devices;
    private final DeviceCredentialRepository credentials;
    private final Clock clock;

    /** Appareil reconnu pour le host de la requête, sinon vide. */
    @Transactional
    public Optional<UUID> authenticate(HttpServletRequest request) {
        String value = cookieValue(request);
        if (value == null) {
            return Optional.empty();
        }
        int dot = value.indexOf('.');
        if (dot <= 0 || dot == value.length() - 1) {
            return Optional.empty();
        }
        UUID deviceId;
        try {
            deviceId = UUID.fromString(value.substring(0, dot));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String secret = value.substring(dot + 1);
        Optional<Domain> domain = domains.findByHost(HostNormalizer.normalize(request.getServerName()))
                .filter(Domain::isEnabled);
        if (domain.isEmpty()) {
            return Optional.empty();
        }
        boolean valid = credentials.find(deviceId, domain.get().getId())
                .filter(credential -> DeviceTokens.matches(secret, credential.getSecretHash()))
                .isPresent();
        if (!valid) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        devices.touch(deviceId, now, now.minus(TOUCH_INTERVAL));
        return Optional.of(deviceId);
    }

    /** Émet un nouveau secret pour (appareil, domaine), remplace le précédent et pose le cookie. */
    @Transactional
    public void issue(Device device, Domain domain, HttpServletRequest request, HttpServletResponse response) {
        String secret = DeviceTokens.newToken();
        DeviceCredential credential = credentials.find(device.getId(), domain.getId())
                .orElseGet(() -> new DeviceCredential(device, domain, null));
        credential.setSecretHash(DeviceTokens.sha256(secret));
        credentials.save(credential);
        ResponseCookie cookie = ResponseCookie.from(COOKIE, device.getId() + "." + secret)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(MAX_AGE)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
