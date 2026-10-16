package fr.enimaloc.blinder.notification;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

public final class WebhookSignatures {

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignatures() {
    }

    /** {@code sha256=hex(HMAC-SHA256(secret, timestamp + "." + body))}. */
    public static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
