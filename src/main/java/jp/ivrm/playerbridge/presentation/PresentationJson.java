package jp.ivrm.playerbridge.presentation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.AchievementNext;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.Achievements;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.Daily;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.DailyItem;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerEntry;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerRef;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.Snapshot;

/**
 * JSON codec for the Presentation Snapshot contract (#1584 S2).
 *
 * <p>Requests use the exact key set the API validates
 * ({@code schemaVersion}, {@code serverRole}, {@code players} of
 * {@code uuid}/{@code name}). Responses are parsed strictly on
 * the fields the bridge renders: a malformed or type-mismatched
 * body raises {@link IllegalArgumentException} so the caller can
 * treat it as a failed fetch and keep the previous snapshot
 * instead of rendering garbage. Unknown fields are ignored so
 * the API can extend the snapshot without breaking the bridge.</p>
 */
public final class PresentationJson {
    private PresentationJson() {
    }

    public static String buildRequestBody(String serverRole, List<PlayerRef> players) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("serverRole", serverRole);
        JsonArray playerArray = new JsonArray();
        for (PlayerRef player : players) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", player.uuid().toString());
            entry.addProperty("name", player.name());
            playerArray.add(entry);
        }
        root.add("players", playerArray);
        return root.toString();
    }

    public static Snapshot parseSnapshot(String body) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Presentation snapshot is not valid JSON", exception);
        }
        JsonObject root = requireObject(parsed, "snapshot");
        Instant generatedAt = requireInstant(root, "generatedAt");
        JsonObject server = requireObject(root.get("server"), "server");
        String serverRole = requireString(server, "role");
        Instant dailyResetsAt = requireInstant(server, "dailyResetsAt");

        List<PlayerEntry> players = new ArrayList<>();
        for (JsonElement element : requireArray(root, "players")) {
            players.add(parsePlayer(requireObject(element, "player")));
        }
        return new Snapshot(generatedAt, serverRole, dailyResetsAt, players);
    }

    private static PlayerEntry parsePlayer(JsonObject object) {
        UUID uuid = requireUuid(object, "uuid");
        boolean linked = requireBoolean(object, "linked");
        Daily daily = null;
        JsonElement dailyElement = object.get("daily");
        if (dailyElement != null && !dailyElement.isJsonNull()) {
            daily = parseDaily(requireObject(dailyElement, "daily"));
        }
        Achievements achievements = null;
        JsonElement achievementsElement = object.get("achievements");
        if (achievementsElement != null && !achievementsElement.isJsonNull()) {
            achievements = parseAchievements(requireObject(achievementsElement, "achievements"));
        }
        return new PlayerEntry(uuid, linked, daily, achievements);
    }

    private static Daily parseDaily(JsonObject object) {
        List<DailyItem> items = new ArrayList<>();
        for (JsonElement element : requireArray(object, "items")) {
            JsonObject item = requireObject(element, "daily item");
            items.add(new DailyItem(
                    requireString(item, "questKey"),
                    requireString(item, "title"),
                    requireInt(item, "progress"),
                    requireInt(item, "target"),
                    requireBoolean(item, "completed"),
                    requireInt(item, "xpReward")));
        }
        return new Daily(
                requireString(object, "periodKey"),
                requireInt(object, "completed"),
                requireInt(object, "total"),
                items);
    }

    private static Achievements parseAchievements(JsonObject object) {
        List<AchievementNext> next = new ArrayList<>();
        for (JsonElement element : requireArray(object, "next")) {
            JsonObject entry = requireObject(element, "achievement next");
            String rank = null;
            JsonElement rankElement = entry.get("rank");
            if (rankElement != null && !rankElement.isJsonNull()) {
                if (!rankElement.isJsonPrimitive() || !rankElement.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("achievement next rank must be a string or null");
                }
                rank = rankElement.getAsString();
            }
            next.add(new AchievementNext(
                    requireString(entry, "code"),
                    requireString(entry, "name"),
                    requireInt(entry, "progress"),
                    requireInt(entry, "target"),
                    rank));
        }
        return new Achievements(
                requireInt(object, "unlocked"),
                requireInt(object, "total"),
                next);
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

    private static int requireInt(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        return element.getAsInt();
    }

    private static boolean requireBoolean(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static Instant requireInstant(JsonObject object, String field) {
        String value = requireString(object, field);
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " must be an ISO 8601 instant", exception);
        }
    }

    private static UUID requireUuid(JsonObject object, String field) {
        String value = requireString(object, field);
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }
}
