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

final class SidebarLinesTest {
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
    void rendersTheFullSidebarWithinTheLineLimit() {
        Daily daily = new Daily("2026-11-23", 1, 5, List.of(
                item("ログインする", 1, 1, true),
                item("鉱石を掘る", 12, 20, false),
                item("魚を釣る", 3, 5, false),
                item("作物を収穫する", 8, 10, false),
                item("鉄を集める", 1, 20, false)));
        Achievements achievements = new Achievements(34, 80, List.of(
                new AchievementNext("mc-mining-gold", "鉱夫への道", 420, 500, "gold")));

        List<String> lines = SidebarLines.renderSidebar(LOCAL, linked(daily, achievements), true, RESETS_AT, NOW);
        assertEquals(
                List.of(
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
                lines);
        assertTrue(lines.size() <= SidebarLines.MAX_SIDEBAR_LINES);
    }

    @Test
    void rendersUnlinkedPlayersWithoutAnyPersonalProgress() {
        PlayerEntry entry = new PlayerEntry(PLAYER, false, null, null);
        assertEquals(
                List.of(
                        "オンライン 4/10 ・ アクティブ 3",
                        "Day 128",
                        "ポータル未連携",
                        "進捗表示には Web で Minecraft 連携が必要です"),
                SidebarLines.renderSidebar(LOCAL, entry, true, RESETS_AT, NOW));
    }

    @Test
    void staleOrMissingCacheDegradesToServerBasics() {
        PlayerEntry entry = linked(
                new Daily("2026-11-23", 0, 1, List.of(item("鉱石を掘る", 0, 20, false))),
                new Achievements(0, 80, List.of()));
        List<String> basics = List.of("オンライン 4/10 ・ アクティブ 3", "Day 128");
        assertEquals(basics, SidebarLines.renderSidebar(LOCAL, entry, false, RESETS_AT, NOW));
        assertEquals(basics, SidebarLines.renderSidebar(LOCAL, null, true, RESETS_AT, NOW));
    }

    @Test
    void failSoftNullSectionsAreSkippedIndependently() {
        PlayerEntry entry = linked(null, new Achievements(3, 80, List.of()));
        assertEquals(
                List.of("オンライン 4/10 ・ アクティブ 3", "Day 128", "◆ 実績 3/80"),
                SidebarLines.renderSidebar(LOCAL, entry, true, RESETS_AT, NOW));
    }

    @Test
    void allCompleteAndEmptyDailyGetTheirOwnLines() {
        Daily done = new Daily("2026-11-23", 2, 2, List.of(
                item("ログインする", 1, 1, true), item("石炭を集める", 20, 20, true)));
        List<String> lines = SidebarLines.renderSidebar(LOCAL, linked(done, null), true, RESETS_AT, NOW);
        assertTrue(lines.contains("・全ミッション完了"));

        Daily empty = new Daily("2026-11-23", 0, 0, List.of());
        lines = SidebarLines.renderSidebar(LOCAL, linked(empty, null), true, RESETS_AT, NOW);
        assertTrue(lines.contains("・本日の対象ミッションはありません"));
    }

    @Test
    void longTitlesAreTruncated() {
        String longTitle = "あ".repeat(60);
        Daily daily = new Daily("2026-11-23", 0, 1, List.of(item(longTitle, 1, 2, false)));
        List<String> lines = SidebarLines.renderSidebar(LOCAL, linked(daily, null), true, RESETS_AT, NOW);
        String itemLine = lines.get(3);
        assertEquals(SidebarLines.MAX_LINE_LENGTH, itemLine.length());
        assertTrue(itemLine.endsWith("…"));
    }

    @Test
    void rendersTodayWithCompletionMarksAndRewards() {
        Daily daily = new Daily("2026-11-23", 1, 2, List.of(
                new DailyItem("login", "ログインする", 1, 1, true, 50),
                new DailyItem("mine-iron", "鉄を集める", 12, 20, false, 100)));
        Achievements achievements = new Achievements(34, 80, List.of(
                new AchievementNext("a", "鉱夫への道", 420, 500, "gold"),
                new AchievementNext("b", "釣り名人", 31, 50, "silver")));
        assertEquals(
                List.of(
                        "―― 今日のデイリーミッション 1/2 ――",
                        "✓ ログインする 1/1 +50 XP",
                        "・鉄を集める 12/20 +100 XP",
                        "―― アチーブメント 34/80 ――",
                        "次に近い実績:",
                        "・鉱夫への道 420/500",
                        "・釣り名人 31/50",
                        "オンライン: 4/10人（アクティブ 3人）"),
                SidebarLines.renderToday(LOCAL, linked(daily, achievements), true));
    }

    @Test
    void todayDegradesWhenCacheIsUnusable() {
        List<String> lines = SidebarLines.renderToday(LOCAL, null, false);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("取得できませんでした"));
        assertFalse(lines.get(1).isBlank());
    }

    @Test
    void formatsTheResetCountdown() {
        assertEquals("7:24", SidebarLines.formatRemaining(RESETS_AT, NOW));
        assertEquals("0:05", SidebarLines.formatRemaining(NOW.plusSeconds(300), NOW));
        assertEquals("まもなく", SidebarLines.formatRemaining(NOW.minusSeconds(1), NOW));
    }
}
