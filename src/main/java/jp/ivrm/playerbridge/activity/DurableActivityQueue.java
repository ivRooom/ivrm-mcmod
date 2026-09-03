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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Bounded NDJSON-backed durable queue.
 *
 * <p>The queue file is an append-only journal: new events, retry state and
 * acknowledgements are fsynced as individual records. Periodic compaction is
 * performed by the dispatcher thread, avoiding a full-backlog rewrite for each
 * delivery. Malformed records are quarantined before they are removed from the
 * active journal.</p>
 */
public final class DurableActivityQueue {
    private static final int COMPACT_AFTER_MUTATIONS = 512;

    public enum EnqueueResult {
        ACTIVE,
        DEAD_LETTERED,
        RETRY_NEEDED
    }

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
    private int mutationsSinceCompaction;
    private boolean queueMetadataSyncPending;

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

    public synchronized EnqueueResult enqueue(String eventId, String body, Instant now) {
        if (!ensureQueueMetadataDurable()) {
            diagnostic.accept("Activity queue metadata durability is unresolved; event retained for retry");
            return EnqueueResult.RETRY_NEEDED;
        }

        QueueEntry entry = new QueueEntry(eventId, body, 0, now.toEpochMilli(), now.toString());
        if (entries.size() >= maxEntries) {
            if (appendDeadLetter(entry, "queue_full")) {
                diagnostic.accept("Activity queue is full; event moved to dead-letter: eventId=" + eventId);
                return EnqueueResult.DEAD_LETTERED;
            }
            diagnostic.accept("Activity queue is full and dead-letter persistence failed; event retained for retry");
            return EnqueueResult.RETRY_NEEDED;
        }

        return appendActiveEntry(entry, "queue_write_failed");
    }

    /**
     * Orderly-shutdown persistence path. Events already accepted into the
     * in-memory ingress must become durable even when the normal active-queue
     * capacity is currently full. This may temporarily exceed maxEntries; on
     * restart the existing restore-capacity policy reconciles any overflow.
     */
    public synchronized EnqueueResult enqueueForShutdown(String eventId, String body, Instant now) {
        if (!ensureQueueMetadataDurable()) {
            diagnostic.accept("Activity queue metadata durability is unresolved during shutdown; ingress retained");
            return EnqueueResult.RETRY_NEEDED;
        }
        QueueEntry entry = new QueueEntry(eventId, body, 0, now.toEpochMilli(), now.toString());
        return appendActiveEntry(entry, "shutdown_queue_write_failed");
    }

    private EnqueueResult appendActiveEntry(QueueEntry entry, String deadLetterReason) {
        try {
            appendForced(queuePath, serialize(entry) + System.lineSeparator());
            entries.add(entry);
            mutationsSinceCompaction++;
            maybeCompact();
            return EnqueueResult.ACTIVE;
        } catch (IOException exception) {
            if (appendDeadLetter(entry, deadLetterReason)) {
                diagnostic.accept("Failed to persist Activity event in the active queue; event moved to dead-letter: eventId="
                        + entry.eventId());
                return EnqueueResult.DEAD_LETTERED;
            }
            diagnostic.accept("Failed to persist Activity event or dead-letter; event retained in ingress for retry");
            return EnqueueResult.RETRY_NEEDED;
        }
    }

    public synchronized Optional<QueueEntry> nextDue(long nowEpochMillis) {
        if (!ensureQueueMetadataDurable()) {
            diagnostic.accept("Activity queue delivery paused until directory metadata durability is confirmed");
            return Optional.empty();
        }
        return entries.stream()
                .filter(entry -> entry.nextAttemptAtEpochMillis() <= nowEpochMillis)
                .findFirst();
    }

    /**
     * Persists an acknowledgement before removing the active in-memory entry.
     * A failed acknowledgement leaves the event retryable; receiver idempotency
     * makes that safer than losing the only durable copy.
     */
    public synchronized boolean markSuccess(String eventId) {
        if (!ensureQueueMetadataDurable()) {
            return false;
        }
        int index = findIndex(eventId);
        if (index < 0) {
            return false;
        }
        if (!appendTombstone(eventId, "accepted")) {
            diagnostic.accept("Failed to persist Activity acknowledgement; event retained for replay: eventId=" + eventId);
            return false;
        }
        entries.remove(index);
        mutationsSinceCompaction++;
        maybeCompact();
        return true;
    }

    /** Persists retry state before updating the active in-memory entry. */
    public synchronized boolean markRetry(String eventId, long nextAttemptAtEpochMillis) {
        if (!ensureQueueMetadataDurable()) {
            return false;
        }
        int index = findIndex(eventId);
        if (index < 0) {
            return false;
        }
        QueueEntry entry = entries.get(index);
        QueueEntry updated = entry.withRetry(entry.attempts() + 1, nextAttemptAtEpochMillis);
        try {
            appendForced(queuePath, serialize(updated) + System.lineSeparator());
        } catch (IOException exception) {
            diagnostic.accept("Failed to persist Activity retry state; original event remains active: eventId=" + eventId);
            return false;
        }
        entries.set(index, updated);
        mutationsSinceCompaction++;
        maybeCompact();
        return true;
    }

    /**
     * Dead-letter persistence is committed first. The active entry is removed
     * only after both the dead-letter record and queue tombstone are durable.
     */
    public synchronized boolean moveToDeadLetter(String eventId, String reason) {
        if (!ensureQueueMetadataDurable()) {
            return false;
        }
        int index = findIndex(eventId);
        if (index < 0) {
            return false;
        }
        QueueEntry entry = entries.get(index);
        if (!appendDeadLetter(entry, reason)) {
            diagnostic.accept("Activity dead-letter persistence failed; active event retained: eventId=" + eventId);
            return false;
        }
        if (!appendTombstone(eventId, "dead_lettered")) {
            diagnostic.accept("Activity queue tombstone failed after dead-letter append; active event retained: eventId=" + eventId);
            return false;
        }
        entries.remove(index);
        mutationsSinceCompaction++;
        maybeCompact();
        return true;
    }

    public synchronized int size() {
        return entries.size();
    }

    private int findIndex(String eventId) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).eventId().equals(eventId)) {
                return index;
            }
        }
        return -1;
    }

    private void restore() {
        if (!Files.exists(queuePath)) {
            return;
        }

        try {
            repairTrailingRecordBoundary(queuePath);
        } catch (IOException repairFailure) {
            throw new IllegalStateException(
                    "Activity queue trailing record boundary could not be repaired durably; sender must remain disabled",
                    repairFailure);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(queuePath, StandardCharsets.UTF_8);
        } catch (IOException readFailure) {
            quarantineUnreadableQueue(readFailure);
            return;
        }

        Map<String, QueueEntry> restored = new LinkedHashMap<>();
        boolean needsCompaction = false;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }

            JsonObject object;
            String eventId;
            QueueEntry entry = null;
            boolean removed;
            try {
                object = JsonParser.parseString(line).getAsJsonObject();
                eventId = object.get("eventId").getAsString();
                removed = object.has("removed") && object.get("removed").getAsBoolean();
                if (!removed) {
                    entry = parse(object);
                }
            } catch (RuntimeException parseFailure) {
                if (!quarantineCorruptLine(line, "invalid_queue_record")) {
                    throw new IllegalStateException(
                            "Activity queue contains a malformed record that could not be quarantined",
                            parseFailure);
                }
                diagnostic.accept("Quarantined one malformed Activity queue record");
                needsCompaction = true;
                continue;
            }

            if (removed) {
                restored.remove(eventId);
                needsCompaction = true;
                continue;
            }

            if (restored.put(entry.eventId(), entry) != null) {
                needsCompaction = true;
            }
        }

        // Capacity is a property of the final active set, not of an intermediate
        // journal prefix. Replay all tombstones/retries first, then preserve only
        // the actual final overflow in dead-letter storage.
        if (restored.size() > maxEntries) {
            List<QueueEntry> active = new ArrayList<>(restored.values());
            for (int index = maxEntries; index < active.size(); index++) {
                QueueEntry overflow = active.get(index);
                if (!appendDeadLetter(overflow, "queue_overflow_on_restore")) {
                    throw new IllegalStateException(
                            "Failed to preserve overflowed Activity queue record; original journal remains intact");
                }
            }
            restored.clear();
            for (int index = 0; index < maxEntries; index++) {
                QueueEntry retained = active.get(index);
                restored.put(retained.eventId(), retained);
            }
            needsCompaction = true;
        }

        entries.addAll(restored.values());
        if (needsCompaction && !compactQueue()) {
            diagnostic.accept("Activity queue startup compaction failed; original append-only journal remains authoritative");
        }
    }

    /**
     * A process crash can leave a fully written JSON record without its trailing
     * newline. Such a record is recoverable, but appendForced intentionally
     * refuses a non-newline EOF. Repair only the record boundary before parsing;
     * malformed content is still quarantined by the normal restore path.
     */
    private void repairTrailingRecordBoundary(Path path) throws IOException {
        long size = Files.size(path);
        if (size == 0L) {
            return;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer lastByte = ByteBuffer.allocate(1);
            channel.position(size - 1);
            if (channel.read(lastByte) != 1) {
                throw new IOException("Could not inspect Activity journal trailing record boundary");
            }
            lastByte.flip();
            if (lastByte.get() == (byte) '\n') {
                return;
            }

            ByteBuffer newline = ByteBuffer.wrap(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
            channel.position(size);
            try {
                while (newline.hasRemaining()) {
                    channel.write(newline);
                }
                channel.force(true);
            } catch (IOException repairFailure) {
                try {
                    channel.truncate(size);
                    channel.force(true);
                } catch (IOException rollbackFailure) {
                    repairFailure.addSuppressed(rollbackFailure);
                }
                throw repairFailure;
            }
            diagnostic.accept("Repaired an unterminated Activity queue tail before restore");
        }
    }

    private void quarantineUnreadableQueue(IOException cause) {
        Path quarantine = queuePath.resolveSibling(
                queuePath.getFileName() + ".unreadable-" + System.currentTimeMillis());
        try {
            try {
                Files.move(queuePath, quarantine, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(queuePath, quarantine);
            }
            forceParentDirectory(queuePath);
            diagnostic.accept("Unreadable Activity queue was isolated intact before sender startup");
        } catch (IOException moveFailure) {
            IllegalStateException failure = new IllegalStateException(
                    "Activity queue could not be restored or isolated; sender must remain disabled",
                    moveFailure);
            failure.addSuppressed(cause);
            throw failure;
        }
    }

    private void maybeCompact() {
        if (mutationsSinceCompaction < COMPACT_AFTER_MUTATIONS) {
            return;
        }
        if (!compactQueue()) {
            diagnostic.accept("Activity queue compaction failed; append-only journal remains authoritative");
        }
    }

    private boolean compactQueue() {
        if (!ensureQueueMetadataDurable()) {
            return false;
        }

        try {
            if (entries.isEmpty()) {
                if (Files.deleteIfExists(queuePath)) {
                    try {
                        forceParentDirectory(queuePath);
                    } catch (IOException syncFailure) {
                        queueMetadataSyncPending = true;
                        return false;
                    }
                }
                mutationsSinceCompaction = 0;
                return true;
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
            // Never replace the authoritative journal with a non-atomic move.
            // If the filesystem cannot provide ATOMIC_MOVE, leave queue.ndjson
            // untouched and treat compaction as failed/retryable.
            Files.move(temp, queuePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try {
                forceParentDirectory(queuePath);
            } catch (IOException syncFailure) {
                // The inode has already been atomically replaced, but the rename
                // metadata is not yet confirmed durable. Stop delivery/mutations
                // until a later operation successfully fsyncs the parent.
                queueMetadataSyncPending = true;
                return false;
            }
            mutationsSinceCompaction = 0;
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean ensureQueueMetadataDurable() {
        if (!queueMetadataSyncPending) {
            return true;
        }
        try {
            forceParentDirectory(queuePath);
            queueMetadataSyncPending = false;
            diagnostic.accept("Activity queue directory metadata durability was recovered");
            return true;
        } catch (IOException retryFailure) {
            return false;
        }
    }

    private boolean appendTombstone(String eventId, String disposition) {
        JsonObject object = new JsonObject();
        object.addProperty("eventId", eventId);
        object.addProperty("removed", true);
        object.addProperty("disposition", disposition);
        object.addProperty("recordedAt", Instant.now().toString());
        try {
            appendForced(queuePath, object + System.lineSeparator());
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean appendDeadLetter(QueueEntry entry, String reason) {
        JsonObject object = JsonParser.parseString(serialize(entry)).getAsJsonObject();
        object.addProperty("reason", reason);
        object.addProperty("deadLetteredAt", Instant.now().toString());
        try {
            appendForced(deadLetterPath, object + System.lineSeparator());
            return true;
        } catch (IOException exception) {
            diagnostic.accept("Failed to persist Activity dead-letter record: eventId=" + entry.eventId());
            return false;
        }
    }

    private boolean quarantineCorruptLine(String raw, String reason) {
        JsonObject object = new JsonObject();
        object.addProperty("raw", raw);
        object.addProperty("reason", reason);
        object.addProperty("quarantinedAt", Instant.now().toString());
        try {
            appendForced(corruptPath, object + System.lineSeparator());
            return true;
        } catch (IOException exception) {
            diagnostic.accept("Failed to persist corrupt Activity queue record");
            return false;
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

    private static QueueEntry parse(JsonObject object) {
        return new QueueEntry(
                object.get("eventId").getAsString(),
                object.get("body").getAsString(),
                object.get("attempts").getAsInt(),
                object.get("nextAttemptAtEpochMillis").getAsLong(),
                object.get("queuedAt").getAsString());
    }

    /**
     * Appends one complete journal record and fsyncs it. The first successful
     * record also fsyncs the full ancestor-directory chain on POSIX so retries
     * after a failed initial directory/file durability step cannot skip it.
     * If a write or force fails after a partial record was written, the file is
     * truncated back to its original boundary before the failure is reported.
     * Future appends refuse a non-newline EOF, so a failed rollback cannot be
     * extended into a second corrupted record.
     */
    private static void appendForced(Path path, String content) throws IOException {
        ensureParent(path);
        long originalSize = Files.exists(path) ? Files.size(path) : 0L;
        verifyAppendBoundary(path, originalSize);

        ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            channel.position(originalSize);
            try {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
                if (originalSize == 0L) {
                    forceAncestorDirectories(path);
                }
            } catch (IOException appendFailure) {
                try {
                    channel.truncate(originalSize);
                    channel.force(true);
                } catch (IOException rollbackFailure) {
                    appendFailure.addSuppressed(rollbackFailure);
                }
                throw appendFailure;
            }
        }
    }

    private static void verifyAppendBoundary(Path path, long size) throws IOException {
        if (size == 0L) {
            return;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer lastByte = ByteBuffer.allocate(1);
            channel.position(size - 1);
            if (channel.read(lastByte) != 1) {
                throw new IOException("Could not verify Activity journal record boundary");
            }
            lastByte.flip();
            if (lastByte.get() != (byte) '\n') {
                throw new IOException("Activity journal has an incomplete trailing record; refusing append");
            }
        }
    }

    private static void forceAncestorDirectories(Path path) throws IOException {
        Path directory = path.getParent();
        while (directory != null) {
            forceDirectory(directory);
            directory = directory.getParent();
        }
    }

    private static void forceParentDirectory(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            forceDirectory(parent);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException | IOException exception) {
            // Java/Windows commonly cannot open a directory as FileChannel.
            // Production Minecraft runs on Linux, where directory fsync is part
            // of the durability guarantee. Keep local Windows development usable.
            if (!isWindows()) {
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Could not fsync Activity journal directory", exception);
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
