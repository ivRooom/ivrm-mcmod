package jp.ivrm.playerbridge.periodquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON codec for the period-quests contract (ivrm-web #1752 S2).
 *
 * <p>The request uses the exact key set the API validates
 * ({@code schemaVersion}, {@code serverRole}) — the same shape
 * as the presentation events poll, because the endpoint shares
 * the presentation HMAC transport. The response is parsed
 * strictly on the envelope the sync acts on ({@code generatedAt},
 * {@code hash}, {@code files}, {@code deleteFiles}): a malformed
 * or type-mismatched body raises {@link IllegalArgumentException}
 * so the caller treats it as a failed fetch and keeps the
 * current book files. Unknown fields are ignored so the API can
 * extend the payload without breaking the bridge.</p>
 */
public final class PeriodQuestsJson {
    private PeriodQuestsJson() {
    }

    public static String buildRequestBody(String serverRole) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("serverRole", serverRole);
        return root.toString();
    }

    public static PeriodQuestsPayload parsePayload(String body) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Period quests payload is not valid JSON", exception);
        }
        JsonObject root = requireObject(parsed, "period quests payload");
        Instant generatedAt = requireInstant(root, "generatedAt");
        String hash = requireString(root, "hash");
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("hash must be a lowercase SHA-256 hex string");
        }

        Map<String, String> files = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : requireObject(root.get("files"), "files").entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("files values must be strings");
            }
            files.put(entry.getKey(), value.getAsString());
        }

        List<String> deleteFiles = new ArrayList<>();
        for (JsonElement element : requireArray(root, "deleteFiles")) {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("deleteFiles entries must be strings");
            }
            deleteFiles.add(element.getAsString());
        }
        return new PeriodQuestsPayload(generatedAt, hash, files, deleteFiles);
    }

    private static JsonObject requireObject(JsonElement element, String what) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(what + " must be a JSON object");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be a JSON array");
        }
        return element.getAsJsonArray();
    }

    private static String requireString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return element.getAsString();
    }

    private static Instant requireInstant(JsonObject object, String field) {
        String value = requireString(object, field);
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " must be an ISO 8601 instant", exception);
        }
    }
}
