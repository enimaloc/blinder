package fr.enimaloc.blinder.access.rules;

import fr.enimaloc.blinder.access.AccessDecision;
import fr.enimaloc.blinder.access.AccessRule;
import fr.enimaloc.blinder.access.Allow;
import fr.enimaloc.blinder.access.Challenge;
import fr.enimaloc.blinder.access.ChallengeType;
import fr.enimaloc.blinder.access.Deny;
import fr.enimaloc.blinder.access.DenyReason;
import fr.enimaloc.blinder.access.IpAllowlist;
import fr.enimaloc.blinder.access.LinkDeviceLookup;
import fr.enimaloc.blinder.access.VisitContext;
import fr.enimaloc.blinder.link.Link;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Protections combinées en OU : appareil autorisé, IP autorisée ou mot de passe correct.
 * Une règle unique, car la chaîne « première décision non-Allow gagne » exprime un ET.
 */
@Component
@Order(1000)
public class ProtectionRule implements AccessRule {

    private static final int BCRYPT_MAX_BYTES = 72;

    private final PasswordEncoder encoder;
    private final LinkDeviceLookup devices;

    public ProtectionRule(PasswordEncoder encoder, LinkDeviceLookup devices) {
        this.encoder = encoder;
        this.devices = devices;
    }

    @Override
    public AccessDecision evaluate(Link link, VisitContext context) {
        boolean hasPassword = link.getPasswordHash() != null;
        IpAllowlist allowlist = IpAllowlist.parse(link.getIpAllowlist());
        boolean hasDevices = devices.hasDevices(link.getId());
        if (!hasPassword && allowlist.isEmpty() && !hasDevices) {
            return new Allow();
        }
        if (hasDevices && context.deviceId() != null && devices.isAllowed(link.getId(), context.deviceId())) {
            return new Allow();
        }
        if (!allowlist.isEmpty() && allowlist.matches(context.clientIp())) {
            return new Allow();
        }
        if (hasPassword) {
            String submitted = context.submittedPassword();
            if (submitted == null) {
                return new Challenge(ChallengeType.PASSWORD, false);
            }
            boolean fits = submitted.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
            if (!submitted.isEmpty() && fits && encoder.matches(submitted, link.getPasswordHash())) {
                return new Allow();
            }
            return new Challenge(ChallengeType.PASSWORD, true);
        }
        return new Deny(DenyReason.FORBIDDEN);
    }
}
