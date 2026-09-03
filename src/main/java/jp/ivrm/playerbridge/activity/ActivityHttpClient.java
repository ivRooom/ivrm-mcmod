package jp.ivrm.playerbridge.activity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

/** Sends one already-persisted Activity event with a fresh transport signature. */
public final class ActivityHttpClient {
    public static final String METHOD = "POST";
    public static final String PATH = "/v1/minecraft/activity-events";

    public record SendResult(int statusCode, boolean validAcknowledgement) {}

    private final ActivityConfig config;
    private final HttpClient client;
    private final Clock clock;

    public ActivityHttpClient(ActivityConfig config) {
        this(config, Clock.systemUTC());
    }

    ActivityHttpClient(ActivityConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
        this.client = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .build();
    }

    public SendResult send(DurableActivityQueue.QueueEntry entry) throws IOException, InterruptedException {
        String timestamp = Instant.now(clock).toString();
        String signature = ActivitySigner.sign(
                config.secret(), METHOD, PATH, timestamp, entry.eventId(), entry.body());
        URI endpoint = config.baseUri().resolve(PATH);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(config.requestTimeout())
                .header("Content-Type", "application/json")
                .header("X-IVRM-Server-Id", config.serverId())
                .header("X-IVRM-Timestamp", timestamp)
                .header("X-IVRM-Event-Id", entry.eventId())
                .header("X-IVRM-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(entry.body()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int statusCode = response.statusCode();
        return new SendResult(
                statusCode,
                isValidAcknowledgement(statusCode, response.body(), entry.eventId()));
    }

    private static boolean isValidAcknowledgement(int statusCode, String body, String expectedEventId) {
        if (statusCode != 200 && statusCode != 202) {
            return false;
        }
        try {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            JsonElement status = object.get("status");
            JsonElement eventId = object.get("eventId");
            JsonElement replayed = object.get("replayed");
            if (!isString(status) || !isString(eventId) || !isBoolean(replayed)) {
                return false;
            }
            boolean expectedReplayed = statusCode == 200;
            return "accepted".equals(status.getAsString())
                    && expectedEventId.equals(eventId.getAsString())
                    && replayed.getAsBoolean() == expectedReplayed;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString();
    }

    private static boolean isBoolean(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isBoolean();
    }
}
