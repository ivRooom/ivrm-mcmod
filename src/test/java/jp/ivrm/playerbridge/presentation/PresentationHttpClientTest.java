package jp.ivrm.playerbridge.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import jp.ivrm.playerbridge.activity.ActivityConfig;
import jp.ivrm.playerbridge.activity.ActivitySigner;
import jp.ivrm.playerbridge.presentation.PresentationHttpClient.FetchResult;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PresentationHttpClientTest {
    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private record CapturedRequest(
            String serverId, String timestamp, String eventId, String signature, String body) {
    }

    private ActivityConfig config(int port) {
        Path state = tempDir.resolve("state");
        return new ActivityConfig(
                false,
                URI.create("http://127.0.0.1:" + port),
                "test-server",
                "main",
                "test-secret",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                30,
                300,
                300,
                100,
                5,
                state.resolve("queue.ndjson"),
                state.resolve("dead-letter.ndjson"),
                state.resolve("corrupt.ndjson"));
    }

    @Test
    void signsLikeTheActivityIngestAndParsesTheSnapshot() throws Exception {
        List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
        String snapshotJson = """
                {"generatedAt":"2026-11-23T12:00:00Z",
                 "server":{"role":"main","dailyResetsAt":"2026-11-23T15:00:00Z"},
                 "players":[{"uuid":"c389d9ba-ac7c-465e-8fa9-6c3d0840a612","linked":false,"daily":null,"achievements":null}]}
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PresentationHttpClient.PATH, exchange -> {
            requests.add(new CapturedRequest(
                    exchange.getRequestHeaders().getFirst("X-IVRM-Server-Id"),
                    exchange.getRequestHeaders().getFirst("X-IVRM-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-IVRM-Event-Id"),
                    exchange.getRequestHeaders().getFirst("X-IVRM-Signature"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = snapshotJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ActivityConfig config = config(server.getAddress().getPort());
        PresentationHttpClient client = new PresentationHttpClient(
                config, Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC));
        UUID player = UUID.fromString("c389d9ba-ac7c-465e-8fa9-6c3d0840a612");
        FetchResult result = client.fetch(List.of(new PlayerRef(player, "ivuruGG")));

        FetchResult.Success success = assertInstanceOf(FetchResult.Success.class, result);
        assertEquals(player, success.snapshot().players().get(0).uuid());

        assertEquals(1, requests.size());
        CapturedRequest request = requests.get(0);
        assertEquals("test-server", request.serverId());
        assertEquals("2026-11-23T12:00:00Z", request.timestamp());
        // The event id must be a fresh UUID the API's header validation accepts.
        UUID.fromString(request.eventId());
        assertEquals(
                "{\"schemaVersion\":1,\"serverRole\":\"main\",\"players\":[{\"uuid\":\"c389d9ba-ac7c-465e-8fa9-6c3d0840a612\",\"name\":\"ivuruGG\"}]}",
                request.body());
        assertEquals(
                ActivitySigner.sign(
                        "test-secret",
                        PresentationHttpClient.METHOD,
                        PresentationHttpClient.PATH,
                        request.timestamp(),
                        request.eventId(),
                        request.body()),
                request.signature());
    }

    @Test
    void non200AndMalformedBodiesBecomeFailures() throws Exception {
        AtomicReference<Integer> mode = new AtomicReference<>(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PresentationHttpClient.PATH, exchange -> {
            exchange.getRequestBody().readAllBytes();
            int current = mode.getAndSet(mode.get() + 1);
            int status = current == 0 ? 503 : 200;
            byte[] response = (current == 0 ? "{\"error\":\"presentation_unavailable\"}" : "not-json")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        PresentationHttpClient client = new PresentationHttpClient(config(server.getAddress().getPort()));
        List<PlayerRef> players = List.of(new PlayerRef(UUID.randomUUID(), "someone"));

        FetchResult unavailable = client.fetch(players);
        FetchResult.Failure failure = assertInstanceOf(FetchResult.Failure.class, unavailable);
        assertEquals(503, failure.statusCode());

        FetchResult malformed = client.fetch(players);
        failure = assertInstanceOf(FetchResult.Failure.class, malformed);
        assertEquals(200, failure.statusCode());
        assertTrue(failure.reason().contains("unparsable"));
    }
}
