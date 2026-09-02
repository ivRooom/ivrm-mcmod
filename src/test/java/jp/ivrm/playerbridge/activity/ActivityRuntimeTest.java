package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class ActivityRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void closePersistsAcceptedIngressBeforeStoppingDispatcher() throws Exception {
        Path state = tempDir.resolve("activity");
        ActivityConfig config = new ActivityConfig(
                true,
                URI.create("https://api.ivrm.jp"),
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

        String journal = Files.readString(config.queuePath(), StandardCharsets.UTF_8);
        assertTrue(journal.contains("\"type\":\"player.logout\""));
        assertTrue(journal.contains("\"playerName\":\"PlayerOne\""));
        assertEquals(1, new DurableActivityQueue(
                        config.queuePath(),
                        config.deadLetterPath(),
                        config.corruptPath(),
                        config.maxQueueEntries(),
                        ignored -> {})
                .size());
    }
}
