package jp.ivrm.playerbridge.activity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tracks one player's vanilla statistics totals and extracts positive deltas.
 *
 * <p>Loader-independent: the NeoForge adapter supplies sparse snapshots
 * (statistic id to total, containing only totals greater than zero) and emits
 * the returned deltas as canonical {@code player.stat_delta} events. The first
 * snapshot only establishes the baseline, so statistics a player accumulated
 * before observation started are never reported as one large delta.</p>
 */
public final class PlayerStatsDiffer {

    /** One positive change of a single statistic since the previous snapshot. */
    public record StatDelta(String stat, int delta) {}

    private Map<String, Integer> baseline;

    /**
     * Compares the current totals with the previous snapshot and advances the
     * baseline to the current totals. Vanilla statistics are monotonic, but a
     * decreased or vanished total defensively produces no delta while still
     * advancing the baseline, so a later rise is measured from the lower value.
     *
     * @return the positive deltas, ordered by statistic id for deterministic
     *         emission; empty on the first call, which only sets the baseline
     */
    public List<StatDelta> diff(Map<String, Integer> currentTotals) {
        Map<String, Integer> current = Map.copyOf(currentTotals);
        if (baseline == null) {
            baseline = current;
            return List.of();
        }
        List<StatDelta> deltas = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : new TreeMap<>(current).entrySet()) {
            int delta = entry.getValue() - baseline.getOrDefault(entry.getKey(), 0);
            if (delta > 0) {
                deltas.add(new StatDelta(entry.getKey(), delta));
            }
        }
        baseline = current;
        return List.copyOf(deltas);
    }
}
