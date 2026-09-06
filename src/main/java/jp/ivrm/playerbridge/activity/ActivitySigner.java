package jp.ivrm.playerbridge.activity;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 signer for the canonical Activity ingest request. */
public final class ActivitySigner {
    private ActivitySigner() {
    }

    public static String sign(
            String secret,
            String method,
            String path,
            String timestamp,
            String eventId,
            String body) {
        try {
            String canonical = String.join("\n", method, path, timestamp, eventId, body);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign Minecraft Activity request", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] output = new char[bytes.length * 2];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            output[index * 2] = hex[value >>> 4];
            output[index * 2 + 1] = hex[value & 0x0f];
        }
        return new String(output);
    }
}
