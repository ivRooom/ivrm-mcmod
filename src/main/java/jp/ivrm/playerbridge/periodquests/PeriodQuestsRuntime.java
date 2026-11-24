package jp.ivrm.playerbridge.periodquests;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import jp.ivrm.playerbridge.activity.ActivityConfig;
import jp.ivrm.playerbridge.periodquests.PeriodQuestsHttpClient.FetchResult;
import org.slf4j.Logger;

/**
 * Background poller for the period quest sync (ivrm-web #1752
 * S3), mirroring the Presentation poller: everything runs on
 * its own daemon thread, polls happen every
 * {@code pollSeconds} while at least one player is connected,
 * and a login can pull the next poll forward with
 * {@link #requestRefresh()}.
 *
 * <p>Each payload is verified before anything on disk changes
 * — the path guards and the recomputed payload hash in
 * {@link PeriodQuestFiles} — and a verified, changed payload is
 * swapped in by {@link PeriodQuestFileStore} and then reloaded
 * into the book through the reload supplier the NeoForge
 * adapter provides (the {@code ftbquests reload} command on the
 * server thread). Every failure mode — transport, non-200,
 * unparsable body, failed verification, failed write, failed
 * reload — keeps the current book files and is retried on a
 * later poll: fetch/verify/write failures back off
 * exponentially (interval × 2 per consecutive failure, capped
 * at 5 minutes), and a failed reload is retried as a
 * reload-only cycle by {@link PeriodQuestsSyncState}, so the
 * book converges without the sync ever touching anything
 * outside the guarded period chapter paths.</p>
 */
public final class PeriodQuestsRuntime implements AutoCloseable {
    private static final long MAX_BACKOFF_SECONDS = 300L;

    private final PeriodQuestsConfig config;
    private final PeriodQuestsHttpClient httpClient;
    private final PeriodQuestFileStore fileStore;
    private final PeriodQuestsSyncState syncState = new PeriodQuestsSyncState();
    private final Logger logger;
    private final Clock clock;
    private final ScheduledExecutorService poller;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    private volatile IntSupplier onlinePlayers = () -> 0;
    private volatile BooleanSupplier reloadSupplier = () -> false;
    private volatile Instant nextPollAt = Instant.EPOCH;
    private volatile boolean started;
    private volatile boolean closing;

    public PeriodQuestsRuntime(ActivityConfig identity, PeriodQuestsConfig config, Logger logger) {
        this(identity, config, logger, Clock.systemUTC());
    }

    PeriodQuestsRuntime(ActivityConfig identity, PeriodQuestsConfig config, Logger logger, Clock clock) {
        this.config = config;
        this.logger = logger;
        this.clock = clock;
        this.httpClient = new PeriodQuestsHttpClient(identity, clock);
        this.fileStore = new PeriodQuestFileStore(config.questsDir());
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "ivrm-period-quests-poller");
            thread.setDaemon(true);
            return thread;
        };
        this.poller = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    public boolean enabled() {
        return config.enabled();
    }

    public PeriodQuestsConfig config() {
        return config;
    }

    public Instant now() {
        return Instant.now(clock);
    }

    /** Supplies the connected player count; called on the poller thread only. */
    public void setOnlinePlayersSupplier(IntSupplier supplier) {
        this.onlinePlayers = supplier == null ? () -> 0 : supplier;
    }

    /**
     * Supplies the book reload, executed on the server thread by
     * the NeoForge adapter. Returning false (including "no
     * server available right now") leaves the reload pending for
     * a later cycle.
     */
    public void setReloadSupplier(BooleanSupplier supplier) {
        this.reloadSupplier = supplier == null ? () -> false : supplier;
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
        logger.info("IVRM Period quests poller started: {}", config.summary());
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
            logger.warn("Period quests poll cycle failed: reasonType={}", exception.getClass().getSimpleName());
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
            if (onlinePlayers.getAsInt() <= 0) {
                // Nobody is playing: the design accepts the book
                // catching up at the next login's poll instead of
                // polling (and reloading) into an empty server.
                nextPollAt = now.plusSeconds(Math.min(15L, config.pollSeconds()));
                return;
            }
            FetchResult result = httpClient.fetch();
            Instant fetchedAt = now();
            if (result instanceof FetchResult.Failure failure) {
                scheduleFailure(fetchedAt, failure.statusCode(), failure.reason());
            } else if (result instanceof FetchResult.Success success) {
                handlePayload(success.payload(), fetchedAt);
            }
        } finally {
            inFlight.set(false);
        }
    }

    private void handlePayload(PeriodQuestsPayload payload, Instant fetchedAt) {
        try {
            PeriodQuestFiles.validate(payload);
        } catch (IllegalArgumentException exception) {
            scheduleFailure(fetchedAt, 200, "payload failed validation: " + exception.getClass().getSimpleName());
            return;
        }
        if (!PeriodQuestFiles.hashMatches(payload)) {
            scheduleFailure(fetchedAt, 200, "payload hash mismatch");
            return;
        }

        switch (syncState.nextAction(payload.hash())) {
            case NONE -> {
                consecutiveFailures.set(0);
                nextPollAt = fetchedAt.plusSeconds(config.pollSeconds());
            }
            case RELOAD_ONLY -> {
                if (tryReload()) {
                    syncState.markReloaded();
                    consecutiveFailures.set(0);
                    logger.info("Period quest book reload completed: hash={}", shortHash(payload.hash()));
                } else {
                    logger.warn("Period quest book reload is still pending: hash={}", shortHash(payload.hash()));
                }
                nextPollAt = fetchedAt.plusSeconds(config.pollSeconds());
            }
            case APPLY_AND_RELOAD -> {
                try {
                    fileStore.apply(payload);
                } catch (IOException | RuntimeException exception) {
                    int failures = consecutiveFailures.incrementAndGet();
                    long backoff = backoffSeconds(failures);
                    nextPollAt = fetchedAt.plusSeconds(backoff);
                    logger.warn(
                            "Period quest file swap failed: reasonType={}, consecutiveFailures={}, retryInSeconds={}",
                            exception.getClass().getSimpleName(),
                            failures,
                            backoff);
                    return;
                }
                syncState.markApplied(payload.hash());
                consecutiveFailures.set(0);
                if (tryReload()) {
                    syncState.markReloaded();
                    logger.info(
                            "Period quest chapters updated and reloaded: hash={}, files={}, deletions={}",
                            shortHash(payload.hash()),
                            payload.files().size(),
                            payload.deleteFiles().size());
                } else {
                    logger.warn(
                            "Period quest chapters updated but the book reload failed; it will be retried: hash={}",
                            shortHash(payload.hash()));
                }
                nextPollAt = fetchedAt.plusSeconds(config.pollSeconds());
            }
        }
    }

    private boolean tryReload() {
        try {
            return reloadSupplier.getAsBoolean();
        } catch (RuntimeException exception) {
            logger.warn("Period quest book reload failed: reasonType={}", exception.getClass().getSimpleName());
            return false;
        }
    }

    private void scheduleFailure(Instant fetchedAt, int statusCode, String reason) {
        int failures = consecutiveFailures.incrementAndGet();
        long backoff = backoffSeconds(failures);
        nextPollAt = fetchedAt.plusSeconds(backoff);
        logger.warn(
                "Period quests fetch failed: status={}, reason={}, consecutiveFailures={}, retryInSeconds={}",
                statusCode,
                reason,
                failures,
                backoff);
    }

    private long backoffSeconds(int failures) {
        long interval = config.pollSeconds();
        long scaled = interval << Math.min(Math.max(failures - 1, 0), 4);
        if (scaled < interval) {
            scaled = interval;
        }
        return Math.min(Math.max(scaled, interval), MAX_BACKOFF_SECONDS);
    }

    private static String shortHash(String hash) {
        return hash == null || hash.length() <= 8 ? String.valueOf(hash) : hash.substring(0, 8);
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
