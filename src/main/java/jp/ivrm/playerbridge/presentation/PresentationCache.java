package jp.ivrm.playerbridge.presentation;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerEntry;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.Snapshot;

/**
 * Holds the most recent successfully fetched snapshot.
 *
 * <p>Fail-soft by design: a failed fetch never clears the cache,
 * so the sidebar keeps rendering the last good state. Freshness
 * is judged against the API's own {@code generatedAt} clock edge
 * plus the local fetch time — a snapshot stays
 * {@link #usable(Instant, Duration) usable} for the configured
 * stale window after it was fetched; beyond that the renderer
 * drops the dynamic progress sections and shows only the server
 * basics, so stale progress is never presented as current.</p>
 */
public final class PresentationCache {
    private Snapshot snapshot;
    private Instant fetchedAt;

    public synchronized void update(Snapshot next, Instant at) {
        this.snapshot = next;
        this.fetchedAt = at;
    }

    public synchronized Optional<Snapshot> latest() {
        return Optional.ofNullable(snapshot);
    }

    public synchronized Optional<PlayerEntry> player(UUID uuid) {
        if (snapshot == null) {
            return Optional.empty();
        }
        return snapshot.player(uuid);
    }

    /** The daily reset instant from the latest snapshot, if any. */
    public synchronized Optional<Instant> dailyResetsAt() {
        return snapshot == null ? Optional.empty() : Optional.of(snapshot.dailyResetsAt());
    }

    /**
     * Whether the cached snapshot is fresh enough to render its
     * dynamic progress sections at {@code now}.
     */
    public synchronized boolean usable(Instant now, Duration staleAfter) {
        if (snapshot == null || fetchedAt == null) {
            return false;
        }
        Duration age = Duration.between(fetchedAt, now);
        return !age.isNegative() && age.compareTo(staleAfter) <= 0;
    }
}
