package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DurableActivityQueueTest {
    @TempDir
    Path tempDir;

    @Test
    void restoresPersistedEventsWithoutChangingBody() {
        Paths paths = paths();
        DurableActivityQueue first = queue(paths, 10);
        String body = "{\"eventId\":\"one\",\"occurredAt\":\"2026-09-02T00:00:00Z\"}";

        assertTrue(first.enqueue("one", body, Instant.parse("2026-09-02T00:00:01Z")));
        DurableActivityQueue restored = queue(paths, 10);

        assertEquals(1, restored.size());
        var entry = restored.nextDue(Instant.parse("2026-09-02T00:00:02Z").toEpochMilli()).orElseThrow();
        assertEquals("one", entry.eventId());
        assertEquals(body, entry.body());
        assertEquals(0, entry.attempts());
    }

    @Test
    void movesOverflowToDeadLetterInsteadOfDroppingIt() throws Exception {
        Paths paths = paths();
        DurableActivityQueue queue = queue(paths, 1);

        assertTrue(queue.enqueue("one", "{}", Instant.parse("2026-09-02T00:00:00Z")));
        assertFalse(queue.enqueue("two", "{}", Instant.parse("2026-09-02T00:00:01Z")));

        assertEquals(1, queue.size());
        String deadLetter = Files.readString(paths.deadLetter, StandardCharsets.UTF_8);
        assertTrue(deadLetter.contains("\"eventId\":\"two\""));
        assertTrue(deadLetter.contains("\"reason\":\"queue_full\""));
    }

    @Test
    void quarantinesMalformedQueueRecordsAndStillStarts() throws Exception {
        Paths paths = paths();
        Files.createDirectories(paths.queue.getParent());
        Files.writeString(paths.queue, "not-json\n", StandardCharsets.UTF_8);

        DurableActivityQueue queue = queue(paths, 10);

        assertEquals(0, queue.size());
        assertFalse(Files.exists(paths.queue));
        assertTrue(Files.readString(paths.corrupt, StandardCharsets.UTF_8).contains("invalid_queue_record"));
    }

    private DurableActivityQueue queue(Paths paths, int maxEntries) {
        return new DurableActivityQueue(
                paths.queue,
                paths.deadLetter,
                paths.corrupt,
                maxEntries,
                ignored -> {});
    }

    private Paths paths() {
        Path directory = tempDir.resolve("activity");
        return new Paths(
                directory.resolve("queue.ndjson"),
                directory.resolve("dead-letter.ndjson"),
                directory.resolve("corrupt.ndjson"));
    }

    private record Paths(Path queue, Path deadLetter, Path corrupt) {}
}
