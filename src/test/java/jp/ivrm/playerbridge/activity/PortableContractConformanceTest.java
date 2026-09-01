package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PortableContractConformanceTest {
    private static final Path BUNDLE = Path.of("contracts/ivrm/minecraft-activity.v1.bundle.json");
    private static final String PINNED_SHA256 =
            "7560e6a41d729f27aeadbeb5bd07a7072255a60f8830e93b45ca7863944337aa";
    private static final List<String> EVENT_TYPES = List.of(
            "player.login",
            "player.logout",
            "player.heartbeat",
            "player.afk_changed",
            "player.stat_delta");
    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "schemaVersion",
            "eventId",
            "type",
            "playerUuid",
            "playerName",
            "serverRole",
            "occurredAt",
            "sequence",
            "attributes");

    @Test
    void pinsTheExactCanonicalPortableBundleSnapshot() throws Exception {
        byte[] bytes = Files.readAllBytes(BUNDLE);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(PINNED_SHA256, digest);

        JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, root.get("bundleVersion").getAsInt());
        assertEquals("minecraft-activity", root.getAsJsonObject("contract").get("id").getAsString());
        assertEquals("1.0.0", root.getAsJsonObject("contract").get("version").getAsString());
        assertEquals(
                "ivRooom/ivrm-contracts",
                root.getAsJsonObject("contract").get("sourceRepository").getAsString());

        JsonObject ingest = root.getAsJsonObject("ingestActivityEvent");
        assertEquals(ActivityHttpClient.METHOD, ingest.get("method").getAsString());
        assertEquals(ActivityHttpClient.PATH, ingest.get("path").getAsString());
        assertEquals("application/json", ingest.get("contentType").getAsString());

        JsonObject authentication = ingest.getAsJsonObject("authentication");
        assertEquals("hmac-sha256", authentication.get("type").getAsString());
        assertEquals("METHOD\\nPATH\\nTIMESTAMP\\nEVENT_ID\\nBODY", authentication.get("canonicalPayload").getAsString());
        assertTrue(authentication.get("pathExcludesQuery").getAsBoolean());
        assertEquals(
                List.of("X-IVRM-Server-Id", "X-IVRM-Timestamp", "X-IVRM-Event-Id", "X-IVRM-Signature"),
                strings(authentication.getAsJsonArray("headers")));

        JsonObject timestamp = authentication.getAsJsonObject("timestamp");
        assertEquals("request-attempt-time", timestamp.get("meaning").getAsString());
        assertEquals(300, timestamp.get("maxSkewSeconds").getAsInt());
        assertTrue(timestamp.get("retryResigns").getAsBoolean());
        assertEquals(
                List.of("eventId", "occurredAt"),
                strings(timestamp.getAsJsonArray("preserveEventFieldsOnRetry")));

        JsonObject event = ingest.getAsJsonObject("event");
        assertEquals(1, event.get("schemaVersion").getAsInt());
        assertFalse(event.get("additionalProperties").getAsBoolean());
        assertEquals(EVENT_TYPES, strings(event.getAsJsonArray("types")));
        assertEquals(List.of("main", "resource"), strings(event.getAsJsonArray("serverRoles")));
        assertEquals(TOP_LEVEL_KEYS, Set.copyOf(strings(event.getAsJsonArray("required"))));

        JsonObject schema = event.getAsJsonObject("schema");
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        JsonObject properties = schema.getAsJsonObject("properties");
        assertEquals("uuid", properties.getAsJsonObject("eventId").get("format").getAsString());
        assertEquals("uuid", properties.getAsJsonObject("playerUuid").get("format").getAsString());
        assertEquals(1, properties.getAsJsonObject("playerName").get("minLength").getAsInt());
        assertEquals("date-time", properties.getAsJsonObject("occurredAt").get("format").getAsString());
        assertTrue(properties.getAsJsonObject("occurredAt").get("pattern").getAsString().contains("[0-5]\\d"));
        assertEquals(0, properties.getAsJsonObject("sequence").get("minimum").getAsInt());
        assertEquals(
                "string",
                properties.getAsJsonObject("attributes")
                        .getAsJsonObject("additionalProperties")
                        .get("type")
                        .getAsString());

        JsonObject response = ingest.getAsJsonObject("response");
        assertEquals(202, response.get("newStatusCode").getAsInt());
        assertEquals(200, response.get("replayStatusCode").getAsInt());
        assertTrue(response.getAsJsonObject("replayedByStatus").get("200").getAsBoolean());
        assertFalse(response.getAsJsonObject("replayedByStatus").get("202").getAsBoolean());
    }

    @Test
    void producerEmitsOnlyCanonicalTopLevelFieldsForEveryEventType() {
        for (String type : EVENT_TYPES) {
            ActivityEvent event = ActivityEvent.create(
                    type,
                    UUID.fromString("018f4b20-8a6f-7a2a-8f4b-1234567890ac"),
                    "IvrmPlayer",
                    "main",
                    Instant.parse("2026-09-02T00:00:00Z"),
                    7,
                    Map.of("source", "test"));
            JsonObject json = JsonParser.parseString(event.toJson()).getAsJsonObject();

            assertEquals(TOP_LEVEL_KEYS, json.keySet());
            assertEquals(type, json.get("type").getAsString());
            assertEquals(event.eventId(), json.get("eventId").getAsString());
            assertEquals(event.occurredAt(), json.get("occurredAt").getAsString());
            assertEquals("test", json.getAsJsonObject("attributes").get("source").getAsString());
        }
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.getAsString()));
        return values;
    }
}
