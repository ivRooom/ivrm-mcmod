package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ActivitySignerTest {
    @Test
    void signsCanonicalPayloadAsLowercaseHexHmacSha256() {
        String signature = ActivitySigner.sign(
                "test-secret",
                "POST",
                "/v1/minecraft/activity-events",
                "2026-09-02T00:00:00Z",
                "018f4b20-8a6f-7a2a-8f4b-1234567890ab",
                "{}");

        assertEquals(
                "63fcefb100ea83ba06a8c0d01d583cccc0915b4a8f02bdf205092871599dbbe4",
                signature);
    }
}
