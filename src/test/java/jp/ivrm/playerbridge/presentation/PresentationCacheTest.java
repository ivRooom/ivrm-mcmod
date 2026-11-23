package jp.ivrm.playerbridge.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerEntry;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.Snapshot;
import org.junit.jupiter.api.Test;

final class PresentationCacheTest {
    private static final UUID PLAYER = UUID.fromString("c389d9ba-ac7c-465e-8fa9-6c3d0840a612");
    private static final Instant FETCHED_AT = Instant.parse("2026-11-23T12:00:00Z");

    private static Snapshot snapshot() {
        return new Snapshot(
                FETCHED_AT,
                "main",
                Instant.parse("2026-11-23T15:00:00Z"),
                List.of(new PlayerEntry(PLAYER, false, null, null)));
    }

    @Test
    void emptyCacheHasNothingAndIsNotUsable() {
        PresentationCache cache = new PresentationCache();
        assertTrue(cache.latest().isEmpty());
        assertTrue(cache.player(PLAYER).isEmpty());
        assertTrue(cache.dailyResetsAt().isEmpty());
        assertFalse(cache.usable(FETCHED_AT, Duration.ofSeconds(300)));
    }

    @Test
    void servesPlayersAndResetInstantAfterUpdate() {
        PresentationCache cache = new PresentationCache();
        cache.update(snapshot(), FETCHED_AT);
        assertEquals(PLAYER, cache.player(PLAYER).orElseThrow().uuid());
        assertEquals(Instant.parse("2026-11-23T15:00:00Z"), cache.dailyResetsAt().orElseThrow());
        assertTrue(cache.player(UUID.randomUUID()).isEmpty());
    }

    @Test
    void usableWithinTheStaleWindowOnly() {
        PresentationCache cache = new PresentationCache();
        cache.update(snapshot(), FETCHED_AT);
        Duration staleAfter = Duration.ofSeconds(300);
        assertTrue(cache.usable(FETCHED_AT.plusSeconds(299), staleAfter));
        assertTrue(cache.usable(FETCHED_AT.plusSeconds(300), staleAfter));
        assertFalse(cache.usable(FETCHED_AT.plusSeconds(301), staleAfter));
    }

    @Test
    void aNewerUpdateReplacesThePreviousSnapshot() {
        PresentationCache cache = new PresentationCache();
        cache.update(snapshot(), FETCHED_AT);
        Snapshot newer = new Snapshot(
                FETCHED_AT.plusSeconds(60),
                "main",
                Instant.parse("2026-11-24T15:00:00Z"),
                List.of());
        cache.update(newer, FETCHED_AT.plusSeconds(60));
        assertEquals(newer, cache.latest().orElseThrow());
        assertTrue(cache.player(PLAYER).isEmpty());
    }
}
