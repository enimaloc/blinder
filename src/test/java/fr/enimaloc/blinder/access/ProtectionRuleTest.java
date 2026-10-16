package fr.enimaloc.blinder.access;

import fr.enimaloc.blinder.access.rules.ProtectionRule;
import fr.enimaloc.blinder.link.Link;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectionRuleTest {

    static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    static final String HASH = ENCODER.encode("s3cret");
    static final UUID ALLOWED_DEVICE = UUID.randomUUID();
    static final UUID OTHER_DEVICE = UUID.randomUUID();
    static final Allow ALLOW = new Allow();
    static final Challenge ASK = new Challenge(ChallengeType.PASSWORD, false);
    static final Challenge WRONG = new Challenge(ChallengeType.PASSWORD, true);
    static final Deny FORBIDDEN = new Deny(DenyReason.FORBIDDEN);

    static LinkDeviceLookup devices(Set<UUID> allowed) {
        return new LinkDeviceLookup() {
            @Override
            public boolean isAllowed(UUID linkId, UUID deviceId) {
                return allowed.contains(deviceId);
            }
        };
    }

    static Link link(String passwordHash, String allowlist) {
        Link link = new Link(null, "x", "https://example.com", null);
        link.setPasswordHash(passwordHash);
        link.setIpAllowlist(allowlist);
        return link;
    }

    static VisitContext ctx(String ip, String password, UUID device) {
        return new VisitContext(ip, "Mozilla/5.0", false, "GET", Instant.EPOCH, password, device);
    }

    static AccessDecision evaluate(Link link, Set<UUID> allowedDevices, VisitContext ctx) {
        return new ProtectionRule(ENCODER, devices(allowedDevices)).evaluate(link, ctx);
    }

    @Test
    void unprotectedLinkIsAllowed() {
        assertThat(evaluate(link(null, null), Set.of(), ctx("1.2.3.4", null, null))).isEqualTo(ALLOW);
    }

    @Test
    void devicesOnly() {
        Link link = link(null, null);
        link.setDeviceProtected(true);
        assertThat(evaluate(link, Set.of(ALLOWED_DEVICE), ctx("1.2.3.4", null, null))).isEqualTo(FORBIDDEN);
        assertThat(evaluate(link, Set.of(ALLOWED_DEVICE), ctx("1.2.3.4", null, ALLOWED_DEVICE))).isEqualTo(ALLOW);
        assertThat(evaluate(link, Set.of(ALLOWED_DEVICE), ctx("1.2.3.4", null, OTHER_DEVICE))).isEqualTo(FORBIDDEN);
    }

    @Test
    void deviceProtectedLinkWithoutRemainingDevicesIsForbidden() {
        Link link = link(null, null);
        link.setDeviceProtected(true);
        assertThat(evaluate(link, Set.of(), ctx("1.2.3.4", null, null))).isEqualTo(FORBIDDEN);
        assertThat(evaluate(link, Set.of(), ctx("1.2.3.4", null, OTHER_DEVICE))).isEqualTo(FORBIDDEN);
    }

    @Test
    void deviceProtectedLinkWithoutRemainingDevicesFallsBackToPassword() {
        Link link = link(HASH, null);
        link.setDeviceProtected(true);
        assertThat(evaluate(link, Set.of(), ctx("1.2.3.4", null, null))).isEqualTo(ASK);
        assertThat(evaluate(link, Set.of(), ctx("1.2.3.4", "s3cret", null))).isEqualTo(ALLOW);
    }

    @Test
    void allowlistOnly() {
        Link link = link(null, "203.0.113.0/24");
        assertThat(evaluate(link, Set.of(), ctx("203.0.113.9", null, null))).isEqualTo(ALLOW);
        assertThat(evaluate(link, Set.of(), ctx("198.51.100.1", null, null))).isEqualTo(FORBIDDEN);
    }

    @Test
    void passwordOnly() {
        Link link = link(HASH, null);
        assertThat(evaluate(link, Set.of(), ctx("1.2.3.4", null, null))).isEqualTo(ASK);
        assertThat(evaluate(link, Set.of(), ctx("1.2.3.4", "nope", null))).isEqualTo(WRONG);
        assertThat(evaluate(link, Set.of(), ctx("1.2.3.4", "", null))).isEqualTo(WRONG);
        assertThat(evaluate(link, Set.of(), ctx("1.2.3.4", "a".repeat(73), null))).isEqualTo(WRONG);
        assertThat(evaluate(link, Set.of(), ctx("1.2.3.4", "s3cret", null))).isEqualTo(ALLOW);
    }

    @Test
    void passwordOrAllowlist() {
        Link link = link(HASH, "203.0.113.9");
        assertThat(evaluate(link, Set.of(), ctx("203.0.113.9", null, null))).isEqualTo(ALLOW);
        assertThat(evaluate(link, Set.of(), ctx("198.51.100.1", "s3cret", null))).isEqualTo(ALLOW);
        assertThat(evaluate(link, Set.of(), ctx("198.51.100.1", null, null))).isEqualTo(ASK);
    }

    @Test
    void allowedDeviceSkipsPassword() {
        Link link = link(HASH, null);
        link.setDeviceProtected(true);
        assertThat(evaluate(link, Set.of(ALLOWED_DEVICE), ctx("1.2.3.4", null, ALLOWED_DEVICE))).isEqualTo(ALLOW);
        assertThat(evaluate(link, Set.of(ALLOWED_DEVICE), ctx("1.2.3.4", null, OTHER_DEVICE))).isEqualTo(ASK);
    }

    @Test
    void botsGetTheChallengeLikeAnyone() {
        Link link = link(HASH, null);
        VisitContext bot = new VisitContext("1.2.3.4", "Discordbot", true, "GET", Instant.EPOCH, null, null);
        assertThat(new ProtectionRule(ENCODER, devices(Set.of())).evaluate(link, bot)).isEqualTo(ASK);
    }
}
