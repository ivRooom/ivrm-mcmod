package jp.ivrm.playerbridge.activity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Bounded NDJSON-backed queue. Every gameplay event is persisted before any
 * network attempt. Malformed queue records are quarantined instead of blocking
 * server startup.
 */
public final class DurableActivityQueue {
    public record QueueEntry(
            String eventId,
            String body,
            int attempts,
            long nextAttemptAtEpochMillis,
            String queuedAt) {
        QueueEntry withRetry(int nextAttempts, long nextAttemptAt) {
            return new QueueEntry(eventId, body, nextAttempts, nextAttemptAt, queuedAt);
        }
    }

    private final Path queuePath;
    private final Path deadLetterPath;
    private final Path corruptPath;
    private final int maxEntries;
    private final Consumer<String> diagnostic;
    private final List<QueueEntry> entries = new ArrayList<>();

    public DurableActivityQueue(
            Path queuePath,
            Path deadLetterPath,
            Path corruptPath,
            int maxEntries,
            Consumer<String> diagnostic) {
        this.queuePath = queuePath;
        this.deadLetterPath = deadLetterPath;
        this.corruptPath = corruptPath;
        this.maxEntries = maxEntries;
        this.diagnostic = diagnostic;
        restore();
    }

    public synchronized boolean enqueue(String eventId, String body, Instant now) {
        QueueEntry entry = new QueueEntry(eventId, body, 0, now.toEpochMilli(), now.toString());
        if (entries.size() >= maxEntries) {
            appendDeadLetter(entry, "queue_full");
            diagnostic.accept("Activity queue is full; event moved to dead-letter: eventId=" + eventId);
            return false;
        }
        try {
            appendForced(queuePath, serialize(entry) + System.lineSeparator());
            entries.add(entry);
            return true;
        } catch (IOException exception) {
            appendDeadLetter(entry, "queue_write_failed");
            diagnostic.accept("Failed to persist Activity event; event moved to dead-letter: eventId=" + eventId);
            return false;
        }
    }

    public synchronized Optional<QueueEntry> nextDue(long nowEpochMillis) {
        return entries.stream()
                .filter(entry -> entry.nextAttemptAtEpochMillis() <= nowEpochMillis)
                .findFirst();
    }

    public synchronized void markSuccess(String eventId) {
        if (entries.removeIf(entry -> entry.eventId().equals(eventId))) {
            rewriteQueue();
        }
    }

    public synchronized int markRetry(String eventId, long nextAttemptAtEpochMillis) {
        for (int index = 0; index < entries.size(); index++) {
            QueueEntry entry = entries.get(index);
            if (entry.eventId().equals(eventId)) {
                QueueEntry updated = entry.withRetry(entry.attempts() + 1, nextAttemptAtEpochMillis);
                entries.set(index, updated);
                rewriteQueue();
                return updated.attempts();
            }
        }
        return 0;
    }

    public synchronized void moveToDeadLetter(String eventId, String reason) {
        for (int index = 0; index < entries.size(); index++) {
            QueueEntry entry = entries.get(index);
            if (entry.eventId().equals(eventId)) {
                appendDeadLetter(entry, reason);
                entries.remove(index);
                rewriteQueue();
                return;
            }
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    private void restore() {
        if (!Files.exists(queuePath)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(queuePath, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    QueueEntry entry = parse(line);
                    if (entries.size() >= maxEntries) {
                        appendDeadLetter(entry, "queue_overflow_on_restore");
                    } else {
                        entries.add(entry);
                    }
                } catch (RuntimeException exception) {
                    quarantineCorruptLine(line, "invalid_queue_record");
                    diagnostic.accept("Quarantined one malformed Activity queue record");
                }
            }
            rewriteQueue();
        } catch (IOException exception) {
            diagnostic.accept("Activity queue restore failed; sender continues with an empty in-memory queue");
        }
    }

    private void rewriteQueue() {
        try {
            if (entries.isEmpty()) {
                Files.deleteIfExists(queuePath);
                return;
            }
            ensureParent(queuePath);
            Path temp = queuePath.resolveSibling(queuePath.getFileName() + ".tmp");
            StringBuilder content = new StringBuilder();
            for (QueueEntry entry : entries) {
                content.append(serialize(entry)).append(System.lineSeparator());
            }
            Files.writeString(
                    temp,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, queuePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, queuePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            diagnostic.accept("Failed to rewrite Activity queue; retained in-memory state for this process");
        }
    }

    private void appendDeadLetter(QueueEntry entry, String reason) {
        JsonObject object = JsonParser.parseString(serialize(entry)).getAsJsonObject();
        object.addProperty("reason", reason);
        object.addProperty("deadLetteredAt", Instant.now().toString());
        try {
            appendForced(deadLetterPath, object + System.lineSeparator());
        } catch (IOException exception) {
            diagnostic.accept("Failed to persist Activity dead-letter record: eventId=" + entry.eventId());
        }
    }

    private void quarantineCorruptLine(String raw, String reason) {
        JsonObject object = new JsonObject();
        object.addProperty("raw", raw);
        object.addProperty("reason", reason);
        object.addProperty("quarantinedAt", Instant.now().toString());
        try {
            appendForced(corruptPath, object + System.lineSeparator());
        } catch (IOException exception) {
            diagnostic.accept("Failed to persist corrupt Activity queue record");
        }
    }

    private static String serialize(QueueEntry entry) {
        JsonObject object = new JsonObject();
        object.addProperty("eventId", entry.eventId());
        object.addProperty("body", entry.body());
        object.addProperty("attempts", entry.attempts());
        object.addProperty("nextAttemptAtEpochMillis", entry.nextAttemptAtEpochMillis());
        object.addProperty("queuedAt", entry.queuedAt());
        return object.toString();
    }

    private static QueueEntry parse(String line) {
        JsonObject object = JsonParser.parseString(line).getAsJsonObject();
        return new QueueEntry(
                object.get("eventId").getAsString(),
                object.get("body").getAsString(),
                object.get("attempts").getAsInt(),
                object.get("nextAttemptAtEpochMillis").getAsLong(),
                object.get("queuedAt").getAsString());
    }

    private static void appendForced(Path path, String content) throws IOException {
        ensureParent(path);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
