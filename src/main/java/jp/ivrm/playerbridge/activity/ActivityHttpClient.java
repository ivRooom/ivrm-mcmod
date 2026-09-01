package jp.ivrm.playerbridge.activity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;

/** Sends one already-persisted Activity event with a fresh transport signature. */
public final class ActivityHttpClient {
    public static final String METHOD = "POST";
    public static final String PATH = "/v1/minecraft/activity-events";

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

    public int send(DurableActivityQueue.QueueEntry entry) throws IOException, InterruptedException {
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

        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
