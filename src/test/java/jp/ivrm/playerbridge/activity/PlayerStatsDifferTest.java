package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PlayerStatsDifferTest {

    @Test
    void firstSnapshotEstablishesBaselineWithoutDeltas() {
        PlayerStatsDiffer differ = new PlayerStatsDiffer();

        List<PlayerStatsDiffer.StatDelta> deltas = differ.diff(Map.of(
                "minecraft:mined/minecraft:stone", 120,
                "minecraft:custom/minecraft:play_time", 54_000));

        assertEquals(List.of(), deltas);
    }

    @Test
    void positiveDeltasAreReportedAndBaselineAdvances() {
        PlayerStatsDiffer differ = new PlayerStatsDiffer();
        differ.diff(Map.of("minecraft:mined/minecraft:stone", 120));

        assertEquals(
                List.of(new PlayerStatsDiffer.StatDelta("minecraft:mined/minecraft:stone", 15)),
                differ.diff(Map.of("minecraft:mined/minecraft:stone", 135)));
        assertEquals(List.of(), differ.diff(Map.of("minecraft:mined/minecraft:stone", 135)));
    }

    @Test
    void statisticAppearingAfterBaselineReportsItsFullTotal() {
        PlayerStatsDiffer differ = new PlayerStatsDiffer();
        differ.diff(Map.of("minecraft:mined/minecraft:stone", 10));

        assertEquals(
                List.of(new PlayerStatsDiffer.StatDelta("minecraft:killed/minecraft:zombie", 3)),
                differ.diff(Map.of(
                        "minecraft:mined/minecraft:stone", 10,
                        "minecraft:killed/minecraft:zombie", 3)));
    }

    @Test
    void decreasedOrVanishedTotalsProduceNoDeltaButAdvanceTheBaseline() {
        PlayerStatsDiffer differ = new PlayerStatsDiffer();
        differ.diff(Map.of("minecraft:mined/minecraft:stone", 10));

        assertEquals(List.of(), differ.diff(Map.of("minecraft:mined/minecraft:stone", 6)));
        assertEquals(
                List.of(new PlayerStatsDiffer.StatDelta("minecraft:mined/minecraft:stone", 3)),
                differ.diff(Map.of("minecraft:mined/minecraft:stone", 9)));
        assertEquals(List.of(), differ.diff(Map.of()));
    }

    @Test
    void deltasAreOrderedByStatisticId() {
        PlayerStatsDiffer differ = new PlayerStatsDiffer();
        differ.diff(Map.of());

        assertEquals(
                List.of(
                        new PlayerStatsDiffer.StatDelta("minecraft:crafted/minecraft:torch", 4),
                        new PlayerStatsDiffer.StatDelta("minecraft:mined/minecraft:stone", 2),
                        new PlayerStatsDiffer.StatDelta("minecraft:used/minecraft:torch", 1)),
                differ.diff(Map.of(
                        "minecraft:used/minecraft:torch", 1,
                        "minecraft:mined/minecraft:stone", 2,
                        "minecraft:crafted/minecraft:torch", 4)));
    }
}
