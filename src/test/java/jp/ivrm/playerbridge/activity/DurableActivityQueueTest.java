package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                first.enqueue("one", body, Instant.parse("2026-09-02T00:00:01Z")));
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

        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                queue.enqueue("one", "{}", Instant.parse("2026-09-02T00:00:00Z")));
        assertEquals(
                DurableActivityQueue.EnqueueResult.DEAD_LETTERED,
                queue.enqueue("two", "{}", Instant.parse("2026-09-02T00:00:01Z")));

        assertEquals(1, queue.size());
        String deadLetter = Files.readString(paths.deadLetter, StandardCharsets.UTF_8);
        assertTrue(deadLetter.contains("\"eventId\":\"two\""));
        assertTrue(deadLetter.contains("\"reason\":\"queue_full\""));
    }

    @Test
    void restoreAppliesCapacityAfterReplayingLaterTombstones() throws Exception {
        Paths paths = paths();
        DurableActivityQueue original = queue(paths, 2);
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                original.enqueue("A", "{\"value\":1}", Instant.parse("2026-09-02T00:00:00Z")));
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                original.enqueue("B", "{\"value\":2}", Instant.parse("2026-09-02T00:00:01Z")));
        assertTrue(original.markSuccess("A"));

        DurableActivityQueue restored = queue(paths, 1);

        assertEquals(1, restored.size());
        assertEquals("B", restored.nextDue(Long.MAX_VALUE).orElseThrow().eventId());
        assertFalse(Files.exists(paths.deadLetter));
    }

    @Test
    void restoreDeadLettersOnlyFinalActiveOverflow() throws Exception {
        Paths paths = paths();
        DurableActivityQueue original = queue(paths, 2);
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                original.enqueue("A", "{\"value\":1}", Instant.parse("2026-09-02T00:00:00Z")));
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                original.enqueue("B", "{\"value\":2}", Instant.parse("2026-09-02T00:00:01Z")));

        DurableActivityQueue restored = queue(paths, 1);

        assertEquals(1, restored.size());
        assertEquals("A", restored.nextDue(Long.MAX_VALUE).orElseThrow().eventId());
        String deadLetter = Files.readString(paths.deadLetter, StandardCharsets.UTF_8);
        assertTrue(deadLetter.contains("\"eventId\":\"B\""));
        assertTrue(deadLetter.contains("\"reason\":\"queue_overflow_on_restore\""));
    }

    @Test
    void restoreOverflowFailureAbortsWithoutQuarantiningValidRecord() throws Exception {
        Paths paths = paths();
        DurableActivityQueue original = queue(paths, 2);
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                original.enqueue("one", "{\"value\":1}", Instant.parse("2026-09-02T00:00:00Z")));
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                original.enqueue("two", "{\"value\":2}", Instant.parse("2026-09-02T00:00:01Z")));
        String originalJournal = Files.readString(paths.queue, StandardCharsets.UTF_8);

        Files.createDirectory(paths.deadLetter);
        assertThrows(IllegalStateException.class, () -> queue(paths, 1));

        assertEquals(originalJournal, Files.readString(paths.queue, StandardCharsets.UTF_8));
        assertFalse(Files.exists(paths.corrupt));
    }

    @Test
    void refusesToAppendAfterJournalBoundaryBecomesIncomplete() throws Exception {
        Paths paths = paths();
        DurableActivityQueue queue = queue(paths, 10);
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                queue.enqueue("one", "{\"value\":1}", Instant.parse("2026-09-02T00:00:00Z")));

        Files.writeString(
                paths.queue,
                "partial-record",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        String damagedJournal = Files.readString(paths.queue, StandardCharsets.UTF_8);

        assertEquals(
                DurableActivityQueue.EnqueueResult.DEAD_LETTERED,
                queue.enqueue("two", "{\"value\":2}", Instant.parse("2026-09-02T00:00:01Z")));

        assertEquals(damagedJournal, Files.readString(paths.queue, StandardCharsets.UTF_8));
        assertFalse(Files.readString(paths.queue, StandardCharsets.UTF_8).contains("\"eventId\":\"two\""));
        String deadLetter = Files.readString(paths.deadLetter, StandardCharsets.UTF_8);
        assertTrue(deadLetter.contains("\"eventId\":\"two\""));
        assertTrue(deadLetter.contains("\"reason\":\"queue_write_failed\""));
    }

    @Test
    void retainsActiveEntryWhenDeadLetterPersistenceFails() throws Exception {
        Paths paths = paths();
        DurableActivityQueue queue = queue(paths, 10);
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                queue.enqueue("one", "{}", Instant.parse("2026-09-02T00:00:00Z")));

        Files.createDirectory(paths.deadLetter);
        assertFalse(queue.moveToDeadLetter("one", "test_failure"));
        assertEquals(1, queue.size());

        DurableActivityQueue restored = queue(paths, 10);
        assertEquals(1, restored.size());
        assertEquals("one", restored.nextDue(Long.MAX_VALUE).orElseThrow().eventId());
    }

    @Test
    void acknowledgementIsJournaledAndRestoredWithoutFullRewrite() throws Exception {
        Paths paths = paths();
        DurableActivityQueue queue = queue(paths, 10);
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                queue.enqueue("one", "{\"value\":1}", Instant.parse("2026-09-02T00:00:00Z")));
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                queue.enqueue("two", "{\"value\":2}", Instant.parse("2026-09-02T00:00:01Z")));

        assertTrue(queue.markSuccess("one"));
        String journal = Files.readString(paths.queue, StandardCharsets.UTF_8);
        assertTrue(journal.contains("\"eventId\":\"one\""));
        assertTrue(journal.contains("\"removed\":true"));
        assertTrue(journal.contains("\"eventId\":\"two\""));

        DurableActivityQueue restored = queue(paths, 10);
        assertEquals(1, restored.size());
        assertEquals("two", restored.nextDue(Long.MAX_VALUE).orElseThrow().eventId());
    }

    @Test
    void retryStateUsesLastJournalRecordAfterRestart() {
        Paths paths = paths();
        DurableActivityQueue queue = queue(paths, 10);
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                queue.enqueue("one", "{}", Instant.parse("2026-09-02T00:00:00Z")));
        long retryAt = Instant.parse("2026-09-02T00:01:00Z").toEpochMilli();
        assertTrue(queue.markRetry("one", retryAt));

        DurableActivityQueue restored = queue(paths, 10);
        var entry = restored.nextDue(retryAt).orElseThrow();
        assertEquals(1, entry.attempts());
        assertEquals(retryAt, entry.nextAttemptAtEpochMillis());
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

    @Test
    void isolatesUnreadableUtf8QueueBeforeAcceptingNewWrites() throws Exception {
        Paths paths = paths();
        Files.createDirectories(paths.queue.getParent());
        Files.write(paths.queue, new byte[] {(byte) 0xC3, (byte) 0x28});

        DurableActivityQueue queue = queue(paths, 10);

        assertEquals(0, queue.size());
        assertFalse(Files.exists(paths.queue));
        try (var files = Files.list(paths.queue.getParent())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("queue.ndjson.unreadable-")));
        }
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
