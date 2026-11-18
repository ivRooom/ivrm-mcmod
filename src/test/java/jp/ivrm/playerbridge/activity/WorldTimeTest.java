package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class WorldTimeTest {

    @Test
    void worldDayCountsWholeElapsedDays() {
        assertEquals(0L, WorldTime.worldDay(0L));
        assertEquals(0L, WorldTime.worldDay(23_999L));
        assertEquals(1L, WorldTime.worldDay(24_000L));
        assertEquals(1L, WorldTime.worldDay(47_999L));
        assertEquals(2L, WorldTime.worldDay(48_000L));
        // A long-running world: 1,000 in-game days.
        assertEquals(1_000L, WorldTime.worldDay(24_000_000L));
    }

    @Test
    void worldDayClampsNegativeGameTimeToZero() {
        assertEquals(0L, WorldTime.worldDay(-1L));
        assertEquals(0L, WorldTime.worldDay(-24_000L));
    }

    @Test
    void heartbeatAttributesCarryAfkAndWorldTime() {
        assertEquals(
                Map.of(
                        "afk", "false",
                        "world_game_time", "30000",
                        "world_day", "1"),
                WorldTime.heartbeatAttributes(false, 30_000L));
        assertEquals(
                Map.of(
                        "afk", "true",
                        "world_game_time", "0",
                        "world_day", "0"),
                WorldTime.heartbeatAttributes(true, 0L));
    }
}
