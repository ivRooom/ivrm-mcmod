package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ActivityJournalBoundaryRecoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void repairsQueueJournalBoundaryOnStartup() throws Exception {
        QueuePaths paths = paths();
        DurableActivityQueue original = queue(paths, 10);
        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                original.enqueue("one", "{\"value\":1}", Instant.parse("2026-09-06T00:00:00Z")));

        removeTrailingLineSeparator(paths.queue());

        DurableActivityQueue restored = queue(paths, 10);
        assertEquals(1, restored.size());
        assertEquals("one", restored.nextDue(Long.MAX_VALUE).orElseThrow().eventId());
        assertEndsWithLineSeparator(paths.queue());
    }

    @Test
    void repairsDeadLetterJournalBeforeQueueEarlyReturnAndAllowsLaterAppend() throws Exception {
        QueuePaths paths = paths();
        Files.createDirectories(paths.deadLetter().getParent());
        Files.writeString(paths.deadLetter(), "{\"eventId\":\"seed\"}", StandardCharsets.UTF_8);

        DurableActivityQueue queue = queue(paths, 1);
        assertEndsWithLineSeparator(paths.deadLetter());

        assertEquals(
                DurableActivityQueue.EnqueueResult.ACTIVE,
                queue.enqueue("one", "{}", Instant.parse("2026-09-06T00:00:00Z")));
        assertEquals(
                DurableActivityQueue.EnqueueResult.DEAD_LETTERED,
                queue.enqueue("two", "{}", Instant.parse("2026-09-06T00:00:01Z")));

        String deadLetter = Files.readString(paths.deadLetter(), StandardCharsets.UTF_8);
        assertTrue(deadLetter.contains("\"eventId\":\"seed\""));
        assertTrue(deadLetter.contains("\"eventId\":\"two\""));
        assertEndsWithLineSeparator(paths.deadLetter());
    }

    @Test
    void repairsCorruptJournalBeforeQueueEarlyReturnAndAllowsLaterQuarantine() throws Exception {
        QueuePaths paths = paths();
        Files.createDirectories(paths.corrupt().getParent());
        Files.writeString(paths.corrupt(), "{\"raw\":\"seed\"}", StandardCharsets.UTF_8);

        queue(paths, 10);
        assertEndsWithLineSeparator(paths.corrupt());

        Files.writeString(paths.queue(), "not-json" + System.lineSeparator(), StandardCharsets.UTF_8);
        DurableActivityQueue restored = queue(paths, 10);

        assertEquals(0, restored.size());
        String corrupt = Files.readString(paths.corrupt(), StandardCharsets.UTF_8);
        assertTrue(corrupt.contains("\"raw\":\"seed\""));
        assertTrue(corrupt.contains("\"reason\":\"invalid_queue_record\""));
        assertEndsWithLineSeparator(paths.corrupt());
    }

    private void removeTrailingLineSeparator(Path path) throws Exception {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String separator = System.lineSeparator();
        assertTrue(content.endsWith(separator));
        Files.writeString(path, content.substring(0, content.length() - separator.length()), StandardCharsets.UTF_8);
    }

    private void assertEndsWithLineSeparator(Path path) throws Exception {
        assertTrue(Files.readString(path, StandardCharsets.UTF_8).endsWith(System.lineSeparator()));
    }

    private DurableActivityQueue queue(QueuePaths paths, int maxEntries) {
        return new DurableActivityQueue(
                paths.queue(),
                paths.deadLetter(),
                paths.corrupt(),
                maxEntries,
                ignored -> {});
    }

    private QueuePaths paths() {
        Path directory = tempDir.resolve("activity");
        return new QueuePaths(
                directory.resolve("queue.ndjson"),
                directory.resolve("dead-letter.ndjson"),
                directory.resolve("corrupt.ndjson"));
    }

    private record QueuePaths(Path queue, Path deadLetter, Path corrupt) {}
}
