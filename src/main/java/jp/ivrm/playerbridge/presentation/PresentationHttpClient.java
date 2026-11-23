package jp.ivrm.playerbridge.presentation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jp.ivrm.playerbridge.activity.ActivityConfig;
import jp.ivrm.playerbridge.activity.ActivitySigner;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerRef;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.Snapshot;

/**
 * Fetches one Presentation Snapshot from the API.
 *
 * <p>The wire format intentionally mirrors the activity ingest:
 * the same per-server secret, the same headers and the same
 * canonical string ({@code METHOD\nPATH\nTIMESTAMP\nEVENT_ID\nBODY})
 * signed with HMAC-SHA256 via {@link ActivitySigner}. The event
 * id is a fresh per-request UUID because this read-only endpoint
 * stores no event identity. Any failure — transport error,
 * non-200 status (including the API's inert 503 while its flag
 * is off), or an unparsable body — is reported as a
 * {@link FetchResult.Failure} so the caller can keep rendering
 * the previous snapshot; this client never throws for an
 * unsuccessful fetch.</p>
 */
public final class PresentationHttpClient {
    public static final String METHOD = "POST";
    public static final String PATH = "/v1/minecraft/presentation/snapshot";
    public static final String EVENTS_PATH = "/v1/minecraft/presentation/events";
    public static final String EVENTS_ACK_PATH = "/v1/minecraft/presentation/events/ack";

    public sealed interface FetchResult {
        record Success(Snapshot snapshot) implements FetchResult {
        }

        /** @param statusCode HTTP status, or -1 when no response was received */
        record Failure(int statusCode, String reason) implements FetchResult {
        }
    }

    public sealed interface EventsResult {
        record Success(List<PresentationEvent> events) implements EventsResult {
        }

        /** @param statusCode HTTP status, or -1 when no response was received */
        record Failure(int statusCode, String reason) implements EventsResult {
        }
    }

    public sealed interface AckResult {
        record Success(int acknowledged) implements AckResult {
        }

        /** @param statusCode HTTP status, or -1 when no response was received */
        record Failure(int statusCode, String reason) implements AckResult {
        }
    }

    private final ActivityConfig identity;
    private final HttpClient client;
    private final Clock clock;

    public PresentationHttpClient(ActivityConfig identity) {
        this(identity, Clock.systemUTC());
    }

    PresentationHttpClient(ActivityConfig identity, Clock clock) {
        this.identity = identity;
        this.clock = clock;
        this.client = HttpClient.newBuilder()
                .connectTimeout(identity.connectTimeout())
                .build();
    }

    public FetchResult fetch(List<PlayerRef> players) {
        String body = PresentationJson.buildRequestBody(identity.serverRole(), players);
        HttpResult result = post(PATH, body);
        if (result instanceof HttpResult.Failure failure) {
            return new FetchResult.Failure(failure.statusCode(), failure.reason());
        }
        String responseBody = ((HttpResult.Success) result).body();
        try {
            return new FetchResult.Success(PresentationJson.parseSnapshot(responseBody));
        } catch (IllegalArgumentException exception) {
            return new FetchResult.Failure(200, "unparsable snapshot body");
        }
    }

    /** Polls the Presentation Outbox for pending effect events (S4). */
    public EventsResult fetchEvents() {
        String body = PresentationJson.buildEventsRequestBody(identity.serverRole());
        HttpResult result = post(EVENTS_PATH, body);
        if (result instanceof HttpResult.Failure failure) {
            return new EventsResult.Failure(failure.statusCode(), failure.reason());
        }
        String responseBody = ((HttpResult.Success) result).body();
        try {
            return new EventsResult.Success(PresentationJson.parseEvents(responseBody));
        } catch (IllegalArgumentException exception) {
            return new EventsResult.Failure(200, "unparsable events body");
        }
    }

    /** Acks rendered events so the API marks them delivered (S4). */
    public AckResult ackEvents(List<String> eventIds) {
        String body = PresentationJson.buildAckRequestBody(identity.serverRole(), eventIds);
        HttpResult result = post(EVENTS_ACK_PATH, body);
        if (result instanceof HttpResult.Failure failure) {
            return new AckResult.Failure(failure.statusCode(), failure.reason());
        }
        String responseBody = ((HttpResult.Success) result).body();
        try {
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(responseBody).getAsJsonObject();
            int acknowledged = root.has("acknowledged")
                    ? root.get("acknowledged").getAsInt()
                    : eventIds.size();
            return new AckResult.Success(acknowledged);
        } catch (RuntimeException exception) {
            return new AckResult.Failure(200, "unparsable ack body");
        }
    }

    private sealed interface HttpResult {
        record Success(String body) implements HttpResult {
        }

        record Failure(int statusCode, String reason) implements HttpResult {
        }
    }

    /**
     * One signed POST in the activity ingest's wire format. The
     * event id is a fresh per-request UUID because these
     * endpoints store no request identity (delivery identity
     * lives in the outbox event ids themselves).
     */
    private HttpResult post(String path, String body) {
        String timestamp = Instant.now(clock).toString();
        String eventId = UUID.randomUUID().toString();
        String signature = ActivitySigner.sign(identity.secret(), METHOD, path, timestamp, eventId, body);
        URI endpoint = identity.baseUri().resolve(path);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(identity.requestTimeout())
                .header("Content-Type", "application/json")
                .header("X-IVRM-Server-Id", identity.serverId())
                .header("X-IVRM-Timestamp", timestamp)
                .header("X-IVRM-Event-Id", eventId)
                .header("X-IVRM-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return new HttpResult.Failure(-1, "transport error: " + exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new HttpResult.Failure(-1, "interrupted");
        }

        int statusCode = response.statusCode();
        if (statusCode != 200) {
            // The body is an error code only; never log or surface it.
            return new HttpResult.Failure(statusCode, "unexpected HTTP status");
        }
        return new HttpResult.Success(response.body());
    }
}
