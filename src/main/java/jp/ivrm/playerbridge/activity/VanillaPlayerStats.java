package jp.ivrm.playerbridge.activity;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;

/**
 * Reads a player's vanilla statistics into the sparse snapshot shape consumed
 * by {@link PlayerStatsDiffer}. Statistic ids use the composite form
 * {@code <stat type id>/<value id>} (for example
 * {@code minecraft:mined/minecraft:iron_ore}), which mirrors the vanilla
 * statistics file layout flattened into a single key.
 */
public final class VanillaPlayerStats {

    private VanillaPlayerStats() {}

    /** Returns the player's current totals, containing only totals above zero. */
    public static Map<String, Integer> snapshot(ServerPlayer player) {
        StatsCounter counter = player.getStats();
        Map<String, Integer> totals = new HashMap<>();
        collect(counter, Stats.BLOCK_MINED, totals);
        collect(counter, Stats.ITEM_CRAFTED, totals);
        collect(counter, Stats.ITEM_USED, totals);
        collect(counter, Stats.ENTITY_KILLED, totals);
        collectCustom(counter, totals);
        return totals;
    }

    private static <T> void collect(StatsCounter counter, StatType<T> type, Map<String, Integer> totals) {
        String typeId = BuiltInRegistries.STAT_TYPE.getKey(type).toString();
        for (T value : type.getRegistry()) {
            int total = counter.getValue(type, value);
            if (total > 0) {
                totals.put(typeId + "/" + type.getRegistry().getKey(value), total);
            }
        }
    }

    private static void collectCustom(StatsCounter counter, Map<String, Integer> totals) {
        String typeId = BuiltInRegistries.STAT_TYPE.getKey(Stats.CUSTOM).toString();
        for (Identifier statId : Stats.CUSTOM.getRegistry()) {
            int total = counter.getValue(Stats.CUSTOM, statId);
            if (total > 0) {
                totals.put(typeId + "/" + statId, total);
            }
        }
    }
}
