package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicInteger;
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
    void closePersistsAcceptedIngressAndRejectsNewIngressWhileHttpRequestIsBlocked() throws Exception {
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

            Thread closeThread = Thread.ofPlatform().start(runtime::close);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runtime.acceptingEvents() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(runtime.acceptingEvents(), "shutdown must close ingress before the durability barrier");

            runtime.emit(
                    "player.logout",
                    UUID.fromString("018f4b20-8a6f-7a2a-8f4b-1234567890ad"),
                    "PlayerThree",
                    Map.of());

            releaseRequest.countDown();
            closeThread.join(5_000);
            assertFalse(closeThread.isAlive(), "shutdown should terminate after the blocked request is released");

            DurableActivityQueue restored = restore(config);
            boolean foundAcceptedLogout = false;
            boolean foundRejectedLogout = false;
            while (true) {
                var due = restored.nextDue(Long.MAX_VALUE);
                if (due.isEmpty()) {
                    break;
                }
                var entry = due.orElseThrow();
                if (entry.body().contains("\"playerName\":\"PlayerTwo\"")) {
                    foundAcceptedLogout = true;
                }
                if (entry.body().contains("\"playerName\":\"PlayerThree\"")) {
                    foundRejectedLogout = true;
                }
                assertTrue(restored.markSuccess(entry.eventId()));
            }
            assertTrue(foundAcceptedLogout, "ingress accepted before shutdown must be durable");
            assertFalse(foundRejectedLogout, "ingress emitted after shutdown begins must be rejected");
        } finally {
            releaseRequest.countDown();
            runtime.close();
            server.stop(0);
        }
    }

    @Test
    void invalidSuccessfulAcknowledgementRetainsEventForRetry() throws Exception {
        String eventId = "018f4b20-8a6f-7a2a-8f4b-1234567890ab";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ActivityHttpClient.PATH, exchange -> {
            byte[] response = ("{\"status\":\"accepted\",\"eventId\":\"different-event\",\"replayed\":false}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        Path state = tempDir.resolve("invalid-ack");
        ActivityConfig config = config(state, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        DurableActivityQueue seed = restore(config);
        String body = "{\"eventId\":\"" + eventId + "\",\"occurredAt\":\"2026-09-02T00:00:00Z\"}";
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                seed.enqueue(eventId, body, Instant.parse("2026-09-02T00:00:00Z")));

        Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:01:00Z"), ZoneOffset.UTC);
        ActivityRuntime runtime = new ActivityRuntime(
                config,
                LoggerFactory.getLogger(ActivityRuntimeTest.class),
                clock);
        try {
            assertFalse(runtime.drainOne());
        } finally {
            runtime.close();
            server.stop(0);
        }

        DurableActivityQueue restored = restore(config);
        assertEquals(1, restored.size());
        var retained = restored.nextDue(Long.MAX_VALUE).orElseThrow();
        assertEquals(eventId, retained.eventId());
        assertEquals(1, retained.attempts());
        assertEquals(body, retained.body());
    }

    @Test
    void durableBacklogDrainsEvenWhenIngressCannotBeDeadLetteredAtCapacity() throws Exception {
        AtomicInteger acceptedRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ActivityHttpClient.PATH, exchange -> {
            String eventId = exchange.getRequestHeaders().getFirst("X-IVRM-Event-Id");
            byte[] response = ("{\"status\":\"accepted\",\"eventId\":\"" + eventId
                            + "\",\"replayed\":false}")
                    .getBytes(StandardCharsets.UTF_8);
            acceptedRequests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        Path state = tempDir.resolve("durable-backlog-progress");
        ActivityConfig config = config(
                state,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                1);
        DurableActivityQueue seed = restore(config);
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                seed.enqueue(
                        "018f4b20-8a6f-7a2a-8f4b-1234567890ab",
                        "{\"eventId\":\"018f4b20-8a6f-7a2a-8f4b-1234567890ab\"}",
                        Instant.parse("2026-09-02T00:00:00Z")));

        Files.createDirectory(config.deadLetterPath());
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:01:00Z"), ZoneOffset.UTC);
        ActivityRuntime runtime = new ActivityRuntime(
                config,
                LoggerFactory.getLogger(ActivityRuntimeTest.class),
                clock);
        try {
            runtime.emit(
                    "player.logout",
                    UUID.fromString("018f4b20-8a6f-7a2a-8f4b-1234567890ac"),
                    "PlayerTwo",
                    Map.of());
            runtime.start();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (acceptedRequests.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }

            assertEquals(2, acceptedRequests.get(),
                    "durable backlog must drain so retained ingress can use the freed capacity");
            assertEquals(0, runtime.queuedEvents());
        } finally {
            runtime.close();
            server.stop(0);
        }
    }

    private ActivityConfig config(Path state, URI baseUri) {
        return config(state, baseUri, 100);
    }

    private ActivityConfig config(Path state, URI baseUri, int maxQueueEntries) {
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
                maxQueueEntries,
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
