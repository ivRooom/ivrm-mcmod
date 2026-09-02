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
 * Gameplay threads only serialize and offer events to memory; normal filesystem
 * and HTTP I/O runs on the dispatcher thread. Orderly shutdown is the sole
 * exception: it synchronously persists remaining ingress before interrupting
 * any in-flight network work.
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
    private final Object ingressLifecycleLock = new Object();
    private final Object persistenceLock = new Object();
    private final DurableActivityQueue queue;
    private final ActivityHttpClient httpClient;
    private final ScheduledExecutorService dispatcher;
    private volatile boolean started;
    private volatile boolean closing;

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

    boolean acceptingEvents() {
        return enabled() && !closing;
    }

    public void start() {
        synchronized (ingressLifecycleLock) {
            if (!enabled() || started || closing) {
                return;
            }
            started = true;
            dispatcher.scheduleWithFixedDelay(this::safeDrain, 0, 1, TimeUnit.SECONDS);
        }
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
        synchronized (ingressLifecycleLock) {
            if (closing) {
                logger.warn("Activity event rejected during shutdown: eventId={}, type={}", event.eventId(), type);
                return;
            }
            if (!ingress.offer(pending)) {
                logger.error(
                        "Activity in-memory ingress is full; event could not be accepted without blocking gameplay: eventId={}, type={}",
                        event.eventId(),
                        type);
            }
        }
    }

    private void safeDrain() {
        try {
            // A failed ingress persistence attempt must not stall events that
            // are already durable. Draining the durable backlog can free queue
            // capacity so the retained ingress head succeeds on a later cycle.
            persistIngressBatch();
            drainNetworkBatch();
        } catch (RuntimeException exception) {
            logger.error("Unexpected Activity dispatcher failure; queued state is retained", exception);
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
     * Moves one bounded batch of ingress events to the fsynced journal before
     * any network attempt. Persistence is serialized with the shutdown flush so
     * both paths can safely inspect/poll the same ingress head.
     */
    private boolean persistIngressBatch() {
        synchronized (persistenceLock) {
            return persistIngressBatchLocked();
        }
    }

    private boolean persistIngressBatchLocked() {
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

    /**
     * Orderly shutdown durability barrier. This method performs no HTTP work and
     * runs before the dispatcher is interrupted, so an in-flight request cannot
     * prevent accepted ingress from reaching durable storage.
     */
    private boolean persistAllIngressForShutdown() {
        synchronized (persistenceLock) {
            while (!ingress.isEmpty()) {
                if (!persistIngressBatchLocked()) {
                    return false;
                }
            }
            return true;
        }
    }

    boolean drainOne() {
        var due = queue.nextDue(now().toEpochMilli());
        if (due.isEmpty()) {
            return false;
        }

        DurableActivityQueue.QueueEntry entry = due.get();
        try {
            ActivityHttpClient.SendResult result = httpClient.send(entry);
            int statusCode = result.statusCode();
            if (statusCode == 200 || statusCode == 202) {
                if (!result.validAcknowledgement()) {
                    scheduleRetry(entry, "invalid_acknowledgement");
                    logger.warn(
                            "Activity API returned an invalid acknowledgement; event retained for retry: eventId={}, status={}",
                            entry.eventId(),
                            statusCode);
                    return false;
                }
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
        synchronized (ingressLifecycleLock) {
            if (closing) {
                return;
            }
            closing = true;
        }

        boolean persisted = false;
        try {
            persisted = persistAllIngressForShutdown();
        } catch (RuntimeException exception) {
            logger.error("Final Activity ingress persistence failed; accepted in-memory events may remain only in RAM", exception);
        }
        if (!persisted && !ingress.isEmpty()) {
            logger.error("Activity sender shutdown with {} ingress event(s) not yet durable", ingress.size());
        }

        dispatcher.shutdownNow();
        try {
            if (!dispatcher.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Activity dispatcher did not terminate within shutdown grace period; durable queue is preserved");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        logger.info("IVRM Activity sender stopped with queuedEvents={}", queuedEvents());
    }
}
