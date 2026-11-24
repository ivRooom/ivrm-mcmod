package jp.ivrm.playerbridge.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.AchievementNext;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.Achievements;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.Daily;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.DailyItem;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerEntry;
import jp.ivrm.playerbridge.presentation.SidebarLines.LocalInfo;
import org.junit.jupiter.api.Test;

final class TabFooterTextTest {
    private static final UUID PLAYER = UUID.fromString("c389d9ba-ac7c-465e-8fa9-6c3d0840a612");
    private static final LocalInfo LOCAL = new LocalInfo(4, 10, 3, 128);
    private static final Instant NOW = Instant.parse("2026-11-23T07:36:00Z");
    private static final Instant RESETS_AT = Instant.parse("2026-11-23T15:00:00Z");

    private static DailyItem item(String title, int progress, int target, boolean completed) {
        return new DailyItem("key-" + title, title, progress, target, completed, 100);
    }

    private static PlayerEntry linked(Daily daily, Achievements achievements) {
        return new PlayerEntry(PLAYER, true, daily, achievements);
    }

    @Test
    void rendersTheFullSummaryWithQuestsAndNextAchievement() {
        Daily daily = new Daily("2026-11-23", 1, 5, List.of(
                item("ログインする", 1, 1, true),
                item("鉱石を掘る", 12, 20, false),
                item("魚を釣る", 3, 5, false),
                item("作物を収穫する", 8, 10, false),
                item("鉄を集める", 1, 20, false)));
        Achievements achievements = new Achievements(34, 80, List.of(
                new AchievementNext("mc-mining-gold", "鉱夫への道", 420, 500, "gold")));

        String footer = TabFooterText.renderFooter(LOCAL, linked(daily, achievements), true, RESETS_AT, NOW);
        assertEquals(
                String.join("\n",
                        "オンライン 4/10 ・ アクティブ 3",
                        "Day 128",
                        "◆ デイリー 1/5",
                        "・作物を収穫する 8/10",
                        "・鉱石を掘る 12/20",
                        "・魚を釣る 3/5",
                        "  あと1件: /ivrm today",
                        "◆ 実績 34/80",
                        "次: 鉱夫への道 420/500",
                        "リセットまで 7:24"),
                footer);
    }

    @Test
    void footerLinesNeverDriftFromTheSidebarLines() {
        Daily daily = new Daily("2026-11-23", 0, 2, List.of(
                item("鉱石を掘る", 12, 20, false),
                item("魚を釣る", 3, 5, false)));
        Achievements achievements = new Achievements(34, 80, List.of());
        PlayerEntry entry = linked(daily, achievements);

        String footer = TabFooterText.renderFooter(LOCAL, entry, true, RESETS_AT, NOW);
        assertEquals(
                SidebarLines.renderSidebar(LOCAL, entry, true, RESETS_AT, NOW),
                List.of(footer.split("\n")));
    }

    @Test
    void rendersWithoutQuestsAndWithoutNextAchievement() {
        Daily empty = new Daily("2026-11-23", 0, 0, List.of());
        Achievements achievements = new Achievements(34, 80, List.of());

        String footer = TabFooterText.renderFooter(LOCAL, linked(empty, achievements), true, RESETS_AT, NOW);
        assertTrue(footer.contains("・本日の対象ミッションはありません"));
        assertTrue(footer.contains("◆ 実績 34/80"));
        assertFalse(footer.contains("次:"));
    }

    @Test
    void rendersUnlinkedPlayersWithoutAnyPersonalProgress() {
        PlayerEntry entry = new PlayerEntry(PLAYER, false, null, null);
        assertEquals(
                String.join("\n",
                        "オンライン 4/10 ・ アクティブ 3",
                        "Day 128",
                        "ポータル未連携",
                        "進捗表示には Web で Minecraft 連携が必要です"),
                TabFooterText.renderFooter(LOCAL, entry, true, RESETS_AT, NOW));
    }

    @Test
    void showsServerBasicsWhileThePlayerHasNoEntryYet() {
        assertEquals(
                "オンライン 4/10 ・ アクティブ 3\nDay 128",
                TabFooterText.renderFooter(LOCAL, null, true, RESETS_AT, NOW));
    }

    @Test
    void clearsTheFooterWhenTheSnapshotIsNotUsable() {
        PlayerEntry entry = linked(
                new Daily("2026-11-23", 0, 1, List.of(item("鉱石を掘る", 0, 20, false))),
                new Achievements(0, 80, List.of()));
        // Stale or never fetched: the empty footer clears any
        // previously shown summary instead of degrading to the
        // server basics like the always-on sidebar does.
        assertEquals("", TabFooterText.renderFooter(LOCAL, entry, false, RESETS_AT, NOW));
        assertEquals("", TabFooterText.renderFooter(LOCAL, null, false, RESETS_AT, NOW));
    }
}
