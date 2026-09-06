package jp.ivrm.playerbridge.activity;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Canonical Minecraft Activity Event v1 payload. */
public record ActivityEvent(
        int schemaVersion,
        String eventId,
        String type,
        String playerUuid,
        String playerName,
        String serverRole,
        String occurredAt,
        long sequence,
        Map<String, String> attributes) {

    public static final int SCHEMA_VERSION = 1;

    public ActivityEvent {
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static ActivityEvent create(
            String type,
            UUID playerUuid,
            String playerName,
            String serverRole,
            Instant occurredAt,
            long sequence,
            Map<String, String> attributes) {
        return new ActivityEvent(
                SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                type,
                playerUuid.toString(),
                playerName,
                serverRole,
                occurredAt.toString(),
                sequence,
                attributes);
    }

    /**
     * Serializes only canonical top-level fields and preserves a deterministic
     * top-level field order for the exact body that is queued and signed.
     */
    public String toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", schemaVersion);
        object.addProperty("eventId", eventId);
        object.addProperty("type", type);
        object.addProperty("playerUuid", playerUuid);
        object.addProperty("playerName", playerName);
        object.addProperty("serverRole", serverRole);
        object.addProperty("occurredAt", occurredAt);
        object.addProperty("sequence", sequence);
        JsonObject metadata = new JsonObject();
        attributes.forEach(metadata::addProperty);
        object.add("attributes", metadata);
        return object.toString();
    }
}
