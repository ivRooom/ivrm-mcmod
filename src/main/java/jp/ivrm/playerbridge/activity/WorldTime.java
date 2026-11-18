package jp.ivrm.playerbridge.activity;

import java.util.Map;

/**
 * World clock attributes for {@code player.heartbeat} (#1315 B3).
 *
 * <p>The member dashboard derives the server's world day on the read
 * side from the newest main-server heartbeat, so each heartbeat also
 * carries the world time of the level the player is currently in:
 * the raw game time in ticks and the whole in-game days elapsed
 * since the world started. Both are plain integer strings, like
 * every other activity attribute.
 */
public final class WorldTime {
    /** Ticks in one in-game day (20 minutes of real time). */
    public static final long TICKS_PER_DAY = 24_000L;

    private WorldTime() {}

    /** Whole in-game days elapsed at the given game time. */
    public static long worldDay(long gameTime) {
        return Math.max(0L, gameTime) / TICKS_PER_DAY;
    }

    /** The {@code player.heartbeat} attribute set for one sample. */
    public static Map<String, String> heartbeatAttributes(boolean afk, long gameTime) {
        long clamped = Math.max(0L, gameTime);
        return Map.of(
                "afk", Boolean.toString(afk),
                "world_game_time", Long.toString(clamped),
                "world_day", Long.toString(clamped / TICKS_PER_DAY));
    }
}
