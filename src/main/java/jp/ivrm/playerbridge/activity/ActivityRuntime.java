package jp.ivrm.playerbridge.activity;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Owns the bounded ingress, durable journal and background dispatcher.
 * Gameplay threads only serialize and offer events to memory; all filesystem
 * and HTTP I/O runs on the dispatcher thread.
 */
public final class ActivityRuntime implements AutoCloseable {
    private static final int MAX_PERSIST_PER_CYCLE = 100;
    private static final int MAX_DRAIN_PER_CYCLE = 50;
    private static final long MAX_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private record PendingEvent(String eventId, String body, Instant occurredAt, String type) {}

    private final ActivityConfig config;
    private final Logger logger;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();
    private final ArrayBlockingQueue<PendingEvent> ingress;
    private final DurableActivityQueue queue;
    private final ActivityHttpClient httpClient;
    private final ScheduledExecutorService dispatcher;
    private volatile boolean started;

    public ActivityRuntime(ActivityConfig config, Logger logger) {
        this(config, logger, Clock.systemUTC());
    }

    ActivityRuntime(ActivityConfig config, Logger logger, Clock clock) {
        this.config = config;
        this.logger = logger;
        this.clock = clock;
        this.ingress = new ArrayBlockingQueue<>(config.maxQueueEntries());
        this.queue = new DurableActivityQueue(
                config.queuePath(),
                config.deadLetterPath(),
                config.corruptPath(),
                config.maxQueueEntries(),
                message -> logger.warn("{}", message));
        this.httpClient = new ActivityHttpClient(config, clock);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "ivrm-activity-dispatcher");
            thread.setDaemon(true);
            return thread;
        };
        this.dispatcher = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    public ActivityConfig config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    public Instant now() {
        return Instant.now(clock);
    }

    public int queuedEvents() {
        return queue.size() + ingress.size();
    }

    public void start() {
        if (!enabled() || started) {
            return;
        }
        started = true;
        dispatcher.scheduleWithFixedDelay(this::safeDrain, 0, 1, TimeUnit.SECONDS);
        logger.info("IVRM Activity sender started: {}", config.summary());
    }

    public void emit(
            String type,
            UUID playerUuid,
            String playerName,
            Map<String, String> attributes) {
        if (!enabled()) {
            return;
        }
        Instant occurredAt = now();
        ActivityEvent event = ActivityEvent.create(
                type,
                playerUuid,
                playerName,
                config.serverRole(),
                occurredAt,
                sequence.getAndIncrement(),
                attributes);
        PendingEvent pending = new PendingEvent(event.eventId(), event.toJson(), occurredAt, type);
        if (!ingress.offer(pending)) {
            logger.error(
                    "Activity in-memory ingress is full; event could not be accepted without blocking gameplay: eventId={}, type={}",
                    event.eventId(),
                    type);
        }
    }

    /** Schedules an immediate background persistence/drain without blocking the caller. */
    public void flushAsync() {
        if (enabled() && !dispatcher.isShutdown()) {
            dispatcher.execute(this::safeDrain);
        }
    }

    private void safeDrain() {
        try {
            if (!persistIngress()) {
                return;
            }
            drainNetworkBatch();
        } catch (RuntimeException exception) {
            logger.error("Unexpected Activity dispatcher failure; queued state is retained", exception);
        }
    }

    /**
     * Final orderly-shutdown pass. Persist every currently accepted ingress
     * event before terminating the dispatcher, then attempt one bounded network
     * drain. If persistence becomes unavailable, the remaining ingress count is
     * reported rather than blocking server shutdown indefinitely.
     */
    private void safeFinalDrain() {
        try {
            while (!ingress.isEmpty()) {
                if (!persistIngress()) {
                    return;
                }
            }
            drainNetworkBatch();
        } catch (RuntimeException exception) {
            logger.error("Unexpected final Activity flush failure; queued state is retained where durable", exception);
        }
    }

    private void drainNetworkBatch() {
        for (int count = 0; count < MAX_DRAIN_PER_CYCLE; count++) {
            if (!drainOne()) {
                break;
            }
        }
    }

    /**
     * Moves ingress events to the fsynced journal before any network attempt.
     * The head remains in memory when neither the active journal nor the
     * dead-letter journal can be persisted.
     */
    private boolean persistIngress() {
        for (int count = 0; count < MAX_PERSIST_PER_CYCLE; count++) {
            PendingEvent pending = ingress.peek();
            if (pending == null) {
                return true;
            }

            DurableActivityQueue.EnqueueResult result =
                    queue.enqueue(pending.eventId(), pending.body(), pending.occurredAt());
            if (result == DurableActivityQueue.EnqueueResult.RETRY_NEEDED) {
                logger.warn("Activity persistence unavailable; ingress head retained for retry");
                return false;
            }

            ingress.poll();
            if (result == DurableActivityQueue.EnqueueResult.DEAD_LETTERED) {
                logger.error(
                        "Activity event bypassed active queue and was preserved in dead-letter: eventId={}, type={}",
                        pending.eventId(),
                        pending.type());
            }
        }
        return true;
    }

    boolean drainOne() {
        var due = queue.nextDue(now().toEpochMilli());
        if (due.isEmpty()) {
            return false;
        }

        DurableActivityQueue.QueueEntry entry = due.get();
        try {
            int statusCode = httpClient.send(entry);
            if (statusCode == 200 || statusCode == 202) {
                return queue.markSuccess(entry.eventId());
            }
            if (statusCode == 409) {
                boolean moved = queue.moveToDeadLetter(entry.eventId(), "http_409_event_conflict");
                if (moved) {
                    logger.error("Activity event conflict moved to dead-letter: eventId={}", entry.eventId());
                } else {
                    logger.error("Activity event conflict could not be dead-lettered; active event retained: eventId={}",
                            entry.eventId());
                }
                return moved;
            }
            if (isRetryableStatus(statusCode)) {
                scheduleRetry(entry, "http_" + statusCode);
                return false;
            }

            boolean moved = queue.moveToDeadLetter(entry.eventId(), "permanent_http_" + statusCode);
            if (moved) {
                logger.error("Permanent Activity HTTP failure moved to dead-letter: eventId={}, status={}",
                        entry.eventId(), statusCode);
            } else {
                logger.error("Permanent Activity HTTP failure could not be dead-lettered; active event retained: eventId={}, status={}",
                        entry.eventId(), statusCode);
            }
            return moved;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            scheduleRetry(entry, "interrupted");
            return false;
        } catch (Exception exception) {
            scheduleRetry(entry, "transport_failure");
            logger.warn("Activity API transport failure; event retained for retry: eventId={}", entry.eventId());
            return false;
        }
    }

    private void scheduleRetry(DurableActivityQueue.QueueEntry entry, String reason) {
        int nextAttempt = entry.attempts() + 1;
        if (nextAttempt >= config.maxAttempts()) {
            boolean moved = queue.moveToDeadLetter(entry.eventId(), "retry_exhausted_" + reason);
            if (moved) {
                logger.error("Activity retry limit reached; event moved to dead-letter: eventId={}", entry.eventId());
            } else {
                logger.error("Activity retry limit reached but dead-letter persistence failed; event retained: eventId={}",
                        entry.eventId());
            }
            return;
        }
        long nextAttemptAt = now().toEpochMilli() + retryDelayMillis(nextAttempt);
        if (!queue.markRetry(entry.eventId(), nextAttemptAt)) {
            logger.warn("Activity retry state could not be persisted; original event remains active: eventId={}",
                    entry.eventId());
        }
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
    }

    private static long retryDelayMillis(int attempt) {
        int exponent = Math.min(attempt, 8);
        long exponential = Math.min(MAX_BACKOFF_MILLIS, 1_000L << exponent);
        long jitter = ThreadLocalRandom.current().nextLong(0, 1_001);
        return Math.min(MAX_BACKOFF_MILLIS, exponential + jitter);
    }

    @Override
    public void close() {
        if (!dispatcher.isShutdown()) {
            dispatcher.execute(this::safeFinalDrain);
            dispatcher.shutdown();
        }
        try {
            if (!dispatcher.awaitTermination(5, TimeUnit.SECONDS)) {
                dispatcher.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            dispatcher.shutdownNow();
        }
        logger.info("IVRM Activity sender stopped with queuedEvents={}", queuedEvents());
    }
}
