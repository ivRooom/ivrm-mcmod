package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ActivityHttpClientTest {
    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retryPreservesEventIdentityAndBodyButResignsWithFreshTransportTimestamp() throws Exception {
        List<CapturedRequest> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ActivityHttpClient.PATH, exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new CapturedRequest(
                    exchange.getRequestHeaders().getFirst("X-IVRM-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-IVRM-Event-Id"),
                    exchange.getRequestHeaders().getFirst("X-IVRM-Signature"),
                    body));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();

        String eventId = "018f4b20-8a6f-7a2a-8f4b-1234567890ab";
        String body = "{\"eventId\":\"018f4b20-8a6f-7a2a-8f4b-1234567890ab\",\"occurredAt\":\"2026-09-02T00:00:00Z\"}";
        DurableActivityQueue.QueueEntry entry = new DurableActivityQueue.QueueEntry(
                eventId, body, 0, 0, "2026-09-02T00:00:00Z");
        ActivityConfig config = config(server.getAddress().getPort());

        Instant firstAttempt = Instant.parse("2026-09-02T00:01:00Z");
        Instant retryAttempt = Instant.parse("2026-09-02T00:01:05Z");
        assertEquals(202, new ActivityHttpClient(config, Clock.fixed(firstAttempt, ZoneOffset.UTC)).send(entry));
        assertEquals(202, new ActivityHttpClient(config, Clock.fixed(retryAttempt, ZoneOffset.UTC)).send(entry));

        assertEquals(2, requests.size());
        CapturedRequest first = requests.get(0);
        CapturedRequest retry = requests.get(1);
        assertEquals(eventId, first.eventId);
        assertEquals(eventId, retry.eventId);
        assertEquals(body, first.body);
        assertEquals(body, retry.body);
        assertNotEquals(first.timestamp, retry.timestamp);
        assertNotEquals(first.signature, retry.signature);
        assertEquals(
                ActivitySigner.sign(
                        config.secret(),
                        ActivityHttpClient.METHOD,
                        ActivityHttpClient.PATH,
                        first.timestamp,
                        eventId,
                        body),
                first.signature);
        assertEquals(
                ActivitySigner.sign(
                        config.secret(),
                        ActivityHttpClient.METHOD,
                        ActivityHttpClient.PATH,
                        retry.timestamp,
                        eventId,
                        body),
                retry.signature);
    }

    private ActivityConfig config(int port) {
        Path state = tempDir.resolve("state");
        return new ActivityConfig(
                true,
                URI.create("http://127.0.0.1:" + port),
                "test-server",
                "main",
                "test-secret",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                30,
                300,
                100,
                5,
                state.resolve("queue.ndjson"),
                state.resolve("dead-letter.ndjson"),
                state.resolve("corrupt.ndjson"));
    }

    private record CapturedRequest(String timestamp, String eventId, String signature, String body) {}
}
