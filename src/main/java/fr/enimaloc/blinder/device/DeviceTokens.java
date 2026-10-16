package fr.enimaloc.blinder.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Jetons aléatoires et empreintes SHA-256 : seuls les hash sont stockés en base. */
public final class DeviceTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceTokens() {
    }

    /** 32 octets aléatoires, base64url sans padding. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 en hexadécimal minuscule (64 caractères). */
    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Comparaison en temps constant de {@code sha256(raw)} avec une empreinte stockée. */
    public static boolean matches(String raw, String expectedHash) {
        if (raw == null || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(raw).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
