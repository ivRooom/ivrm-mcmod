package jp.ivrm.playerbridge.activity;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Owns the durable queue and background dispatcher. Gameplay threads only
 * serialize and append events; they never wait for the Activity API.
 */
public final class ActivityRuntime implements AutoCloseable {
    private static final int MAX_DRAIN_PER_CYCLE = 50;
    private static final long MAX_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final ActivityConfig config;
    private final Logger logger;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();
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
        return queue.size();
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
        boolean queued = queue.enqueue(event.eventId(), event.toJson(), occurredAt);
        if (!queued) {
            logger.error("Activity event could not enter the active queue: eventId={}, type={}", event.eventId(), type);
        }
    }

    /** Schedules an immediate background drain without blocking the caller. */
    public void flushAsync() {
        if (enabled() && !dispatcher.isShutdown()) {
            dispatcher.execute(this::safeDrain);
        }
    }

    private void safeDrain() {
        try {
            for (int count = 0; count < MAX_DRAIN_PER_CYCLE; count++) {
                if (!drainOne()) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            logger.error("Unexpected Activity dispatcher failure; queue remains durable", exception);
        }
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
                queue.markSuccess(entry.eventId());
                return true;
            }
            if (statusCode == 409) {
                queue.moveToDeadLetter(entry.eventId(), "http_409_event_conflict");
                logger.error("Activity event conflict moved to dead-letter: eventId={}", entry.eventId());
                return true;
            }
            if (isRetryableStatus(statusCode)) {
                scheduleRetry(entry, "http_" + statusCode);
                return false;
            }

            queue.moveToDeadLetter(entry.eventId(), "permanent_http_" + statusCode);
            logger.error("Permanent Activity HTTP failure moved to dead-letter: eventId={}, status={}",
                    entry.eventId(), statusCode);
            return true;
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
            queue.moveToDeadLetter(entry.eventId(), "retry_exhausted_" + reason);
            logger.error("Activity retry limit reached; event moved to dead-letter: eventId={}", entry.eventId());
            return;
        }
        long nextAttemptAt = now().toEpochMilli() + retryDelayMillis(nextAttempt);
        queue.markRetry(entry.eventId(), nextAttemptAt);
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
        dispatcher.shutdown();
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
