package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
        List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ActivityHttpClient.PATH, exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String eventId = exchange.getRequestHeaders().getFirst("X-IVRM-Event-Id");
            requests.add(new CapturedRequest(
                    exchange.getRequestHeaders().getFirst("X-IVRM-Timestamp"),
                    eventId,
                    exchange.getRequestHeaders().getFirst("X-IVRM-Signature"),
                    body));
            byte[] response = ("{\"status\":\"accepted\",\"eventId\":\"" + eventId
                            + "\",\"replayed\":false}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
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
        ActivityHttpClient.SendResult firstResult =
                new ActivityHttpClient(config, Clock.fixed(firstAttempt, ZoneOffset.UTC)).send(entry);
        ActivityHttpClient.SendResult retryResult =
                new ActivityHttpClient(config, Clock.fixed(retryAttempt, ZoneOffset.UTC)).send(entry);
        assertEquals(202, firstResult.statusCode());
        assertTrue(firstResult.validAcknowledgement());
        assertEquals(202, retryResult.statusCode());
        assertTrue(retryResult.validAcknowledgement());

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

    @Test
    void rejectsMalformedMismatchedAndStatusInconsistentAcknowledgements() throws Exception {
        AtomicInteger requestNumber = new AtomicInteger();
        String expectedEventId = "018f4b20-8a6f-7a2a-8f4b-1234567890ab";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ActivityHttpClient.PATH, exchange -> {
            int current = requestNumber.getAndIncrement();
            int statusCode;
            String responseBody;
            if (current == 0) {
                statusCode = 202;
                responseBody = "not-json";
            } else if (current == 1) {
                statusCode = 202;
                responseBody = "{\"status\":\"accepted\",\"eventId\":\"different-event\",\"replayed\":false}";
            } else {
                statusCode = 200;
                responseBody = "{\"status\":\"accepted\",\"eventId\":\"" + expectedEventId
                        + "\",\"replayed\":false}";
            }
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        DurableActivityQueue.QueueEntry entry = new DurableActivityQueue.QueueEntry(
                expectedEventId,
                "{\"eventId\":\"" + expectedEventId + "\"}",
                0,
                0,
                "2026-09-02T00:00:00Z");
        ActivityConfig config = config(server.getAddress().getPort());
        ActivityHttpClient client = new ActivityHttpClient(
                config,
                Clock.fixed(Instant.parse("2026-09-02T00:01:00Z"), ZoneOffset.UTC));

        ActivityHttpClient.SendResult malformed = client.send(entry);
        ActivityHttpClient.SendResult mismatched = client.send(entry);
        ActivityHttpClient.SendResult replayMismatch = client.send(entry);

        assertEquals(202, malformed.statusCode());
        assertFalse(malformed.validAcknowledgement());
        assertEquals(202, mismatched.statusCode());
        assertFalse(mismatched.validAcknowledgement());
        assertEquals(200, replayMismatch.statusCode());
        assertFalse(replayMismatch.validAcknowledgement());
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
