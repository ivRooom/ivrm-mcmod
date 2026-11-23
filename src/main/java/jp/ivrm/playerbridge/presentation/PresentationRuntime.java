package jp.ivrm.playerbridge.presentation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import jp.ivrm.playerbridge.activity.ActivityConfig;
import jp.ivrm.playerbridge.presentation.PresentationHttpClient.FetchResult;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerRef;
import org.slf4j.Logger;

/**
 * Background poller for the Presentation Snapshot.
 *
 * <p>Runs entirely on its own daemon thread: the Minecraft tick
 * thread never performs HTTP. Polls happen every
 * {@code snapshotSeconds} while at least one player is connected;
 * a login can pull the next poll forward with
 * {@link #requestRefresh()}. Failures keep the previous cache and
 * back off exponentially (interval × 2 per consecutive failure,
 * capped at 5 minutes), so an API outage degrades the sidebar to
 * its stale/basic form instead of hammering the API or touching
 * gameplay.</p>
 */
public final class PresentationRuntime implements AutoCloseable {
    private static final long MAX_BACKOFF_SECONDS = 300L;

    private final PresentationConfig config;
    private final PresentationCache cache;
    private final PresentationHttpClient httpClient;
    private final Logger logger;
    private final Clock clock;
    private final ScheduledExecutorService poller;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    private volatile Supplier<List<PlayerRef>> playersSupplier = List::of;
    private volatile Instant nextPollAt = Instant.EPOCH;
    private volatile boolean started;
    private volatile boolean closing;

    public PresentationRuntime(
            ActivityConfig identity, PresentationConfig config, PresentationCache cache, Logger logger) {
        this(identity, config, cache, logger, Clock.systemUTC());
    }

    PresentationRuntime(
            ActivityConfig identity,
            PresentationConfig config,
            PresentationCache cache,
            Logger logger,
            Clock clock) {
        this.config = config;
        this.cache = cache;
        this.logger = logger;
        this.clock = clock;
        this.httpClient = new PresentationHttpClient(identity, clock);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "ivrm-presentation-poller");
            thread.setDaemon(true);
            return thread;
        };
        this.poller = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    public boolean enabled() {
        return config.enabled();
    }

    public PresentationConfig config() {
        return config;
    }

    public Instant now() {
        return Instant.now(clock);
    }

    /** Supplies the currently connected players; called on the poller thread only. */
    public void setPlayersSupplier(Supplier<List<PlayerRef>> supplier) {
        this.playersSupplier = supplier == null ? List::of : supplier;
    }

    public void start() {
        synchronized (this) {
            if (!enabled() || started || closing) {
                return;
            }
            started = true;
            nextPollAt = now();
            poller.scheduleWithFixedDelay(this::safePollCycle, 0, 1, TimeUnit.SECONDS);
        }
        logger.info("IVRM Presentation poller started: {}", config.summary());
    }

    /** Pulls the next poll forward (for example right after a login). */
    public void requestRefresh() {
        if (!started || closing) {
            return;
        }
        Instant now = now();
        if (nextPollAt.isAfter(now)) {
            nextPollAt = now;
        }
    }

    private void safePollCycle() {
        try {
            pollCycle();
        } catch (RuntimeException exception) {
            // The poller must never die or leak into gameplay; only
            // the type is logged (messages can carry request data).
            logger.warn("Presentation poll cycle failed: reasonType={}", exception.getClass().getSimpleName());
        }
    }

    private void pollCycle() {
        if (closing || !started) {
            return;
        }
        Instant now = now();
        if (now.isBefore(nextPollAt) || !inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            List<PlayerRef> players = playersSupplier.get();
            if (players.isEmpty()) {
                // Nobody to render for; check again shortly so a
                // fresh login is picked up without waiting a full
                // interval even if its refresh request raced us.
                nextPollAt = now.plusSeconds(Math.min(15L, config.snapshotSeconds()));
                return;
            }
            FetchResult result = httpClient.fetch(players);
            Instant fetchedAt = now();
            if (result instanceof FetchResult.Success success) {
                cache.update(success.snapshot(), fetchedAt);
                consecutiveFailures.set(0);
                nextPollAt = fetchedAt.plusSeconds(config.snapshotSeconds());
            } else if (result instanceof FetchResult.Failure failure) {
                int failures = consecutiveFailures.incrementAndGet();
                long backoff = backoffSeconds(failures);
                nextPollAt = fetchedAt.plusSeconds(backoff);
                logger.warn(
                        "Presentation snapshot fetch failed: status={}, reason={}, consecutiveFailures={}, retryInSeconds={}",
                        failure.statusCode(),
                        failure.reason(),
                        failures,
                        backoff);
            }
        } finally {
            inFlight.set(false);
        }
    }

    private long backoffSeconds(int failures) {
        long interval = config.snapshotSeconds();
        long scaled = interval << Math.min(Math.max(failures - 1, 0), 4);
        if (scaled < interval) {
            scaled = interval;
        }
        return Math.min(Math.max(scaled, interval), MAX_BACKOFF_SECONDS);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closing) {
                return;
            }
            closing = true;
        }
        poller.shutdownNow();
        try {
            poller.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
