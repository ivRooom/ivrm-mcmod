package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class ActivityRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void closePersistsAcceptedIngressBeforeStoppingDispatcher() throws Exception {
        Path state = tempDir.resolve("activity");
        ActivityConfig config = config(state, URI.create("https://api.ivrm.jp"));
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
        ActivityRuntime runtime = new ActivityRuntime(
                config,
                LoggerFactory.getLogger(ActivityRuntimeTest.class),
                clock);

        runtime.emit(
                "player.logout",
                UUID.fromString("018f4b20-8a6f-7a2a-8f4b-1234567890ab"),
                "PlayerOne",
                Map.of());
        assertEquals(1, runtime.queuedEvents());

        runtime.close();

        assertTrue(Files.exists(config.queuePath()));
        DurableActivityQueue restored = restore(config);
        assertEquals(1, restored.size());
        String body = restored.nextDue(Long.MAX_VALUE).orElseThrow().body();
        assertTrue(body.contains("\"type\":\"player.logout\""));
        assertTrue(body.contains("\"playerName\":\"PlayerOne\""));
    }

    @Test
    void closePersistsPendingIngressWhileHttpRequestIsBlocked() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ActivityHttpClient.PATH, exchange -> {
            requestStarted.countDown();
            try {
                releaseRequest.await(15, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(503, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        Path state = tempDir.resolve("blocked-http");
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        ActivityConfig config = config(state, baseUri);
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
        ActivityRuntime runtime = new ActivityRuntime(
                config,
                LoggerFactory.getLogger(ActivityRuntimeTest.class),
                clock);

        try {
            runtime.start();
            runtime.emit(
                    "player.heartbeat",
                    UUID.fromString("018f4b20-8a6f-7a2a-8f4b-1234567890ab"),
                    "PlayerOne",
                    Map.of("afk", "false"));
            assertTrue(requestStarted.await(10, TimeUnit.SECONDS), "first HTTP request should be blocked");

            runtime.emit(
                    "player.logout",
                    UUID.fromString("018f4b20-8a6f-7a2a-8f4b-1234567890ac"),
                    "PlayerTwo",
                    Map.of());

            runtime.close();

            DurableActivityQueue restored = restore(config);
            boolean foundPendingLogout = false;
            while (true) {
                var due = restored.nextDue(Long.MAX_VALUE);
                if (due.isEmpty()) {
                    break;
                }
                var entry = due.orElseThrow();
                if (entry.body().contains("\"type\":\"player.logout\"")
                        && entry.body().contains("\"playerName\":\"PlayerTwo\"")) {
                    foundPendingLogout = true;
                }
                assertTrue(restored.markSuccess(entry.eventId()));
            }
            assertTrue(foundPendingLogout, "pending ingress must be durable even while HTTP is blocked");
        } finally {
            releaseRequest.countDown();
            runtime.close();
            server.stop(0);
        }
    }

    private ActivityConfig config(Path state, URI baseUri) {
        return new ActivityConfig(
                true,
                baseUri,
                "test-server",
                "main",
                "test-secret",
                Duration.ofSeconds(1),
                Duration.ofSeconds(120),
                30,
                300,
                100,
                5,
                state.resolve("queue.ndjson"),
                state.resolve("dead-letter.ndjson"),
                state.resolve("corrupt.ndjson"));
    }

    private DurableActivityQueue restore(ActivityConfig config) {
        return new DurableActivityQueue(
                config.queuePath(),
                config.deadLetterPath(),
                config.corruptPath(),
                config.maxQueueEntries(),
                ignored -> {});
    }
}
