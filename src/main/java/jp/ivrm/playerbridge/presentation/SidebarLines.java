package jp.ivrm.playerbridge.presentation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.Achievements;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.Daily;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.DailyItem;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerEntry;

/**
 * Pure text rendering for the in-game sidebar and
 * {@code /ivrm today} (#1584 §5.3 / §5.4).
 *
 * <p>Loader-independent so the layout rules can be unit-tested:
 * at most {@value #MAX_SIDEBAR_LINES} sidebar lines, incomplete
 * dailies first (highest completion ratio first, at most
 * {@value #MAX_SIDEBAR_DAILY_ITEMS} of them), only the single
 * closest achievement in the sidebar, and long titles truncated.
 * When the cache is not usable the sidebar degrades to the
 * server basics (online counts and world day) — stale progress
 * is never rendered as if it were current.</p>
 */
public final class SidebarLines {
    public static final int MAX_SIDEBAR_LINES = 15;
    public static final int MAX_SIDEBAR_DAILY_ITEMS = 3;
    public static final int MAX_LINE_LENGTH = 40;

    /** Server-local facts the bridge itself knows, without the API. */
    public record LocalInfo(int online, int maxPlayers, int active, long worldDay) {
    }

    private SidebarLines() {
    }

    public static List<String> renderSidebar(
            LocalInfo local, PlayerEntry entry, boolean cacheUsable, Instant dailyResetsAt, Instant now) {
        List<String> lines = new ArrayList<>();
        lines.add("オンライン " + local.online() + "/" + local.maxPlayers() + " ・ アクティブ " + local.active());
        lines.add("Day " + local.worldDay());

        if (entry == null || !cacheUsable) {
            return List.copyOf(lines);
        }
        if (!entry.linked()) {
            lines.add("ポータル未連携");
            lines.add("進捗表示には Web で Minecraft 連携が必要です");
            return List.copyOf(lines);
        }

        Daily daily = entry.daily();
        if (daily != null) {
            lines.add("◆ デイリー " + daily.completed() + "/" + daily.total());
            List<DailyItem> incomplete = sortedIncomplete(daily);
            if (daily.total() == 0) {
                lines.add("・本日の対象ミッションはありません");
            } else if (incomplete.isEmpty()) {
                lines.add("・全ミッション完了");
            } else {
                for (DailyItem item : incomplete.stream().limit(MAX_SIDEBAR_DAILY_ITEMS).toList()) {
                    lines.add(truncate("・" + item.title() + " " + item.progress() + "/" + item.target()));
                }
                if (incomplete.size() > MAX_SIDEBAR_DAILY_ITEMS) {
                    lines.add("  あと" + (incomplete.size() - MAX_SIDEBAR_DAILY_ITEMS) + "件: /ivrm today");
                }
            }
        }

        Achievements achievements = entry.achievements();
        if (achievements != null) {
            lines.add("◆ 実績 " + achievements.unlocked() + "/" + achievements.total());
            if (!achievements.next().isEmpty()) {
                var next = achievements.next().get(0);
                lines.add(truncate("次: " + next.name() + " " + next.progress() + "/" + next.target()));
            }
        }

        if (daily != null && dailyResetsAt != null) {
            lines.add("リセットまで " + formatRemaining(dailyResetsAt, now));
        }
        return List.copyOf(lines.stream().limit(MAX_SIDEBAR_LINES).toList());
    }

    public static List<String> renderToday(
            LocalInfo local, PlayerEntry entry, boolean cacheUsable) {
        String onlineLine =
                "オンライン: " + local.online() + "/" + local.maxPlayers() + "人（アクティブ " + local.active() + "人）";
        if (entry == null || !cacheUsable) {
            return List.of("進捗情報を取得できませんでした。少し待ってから再度お試しください。", onlineLine);
        }
        if (!entry.linked()) {
            return List.of("ポータル未連携です。進捗表示には Web で Minecraft 連携が必要です。", onlineLine);
        }

        List<String> lines = new ArrayList<>();
        Daily daily = entry.daily();
        if (daily == null) {
            lines.add("―― 今日のデイリーミッション ――");
            lines.add("・デイリー情報は一時的に取得できません");
        } else {
            lines.add("―― 今日のデイリーミッション " + daily.completed() + "/" + daily.total() + " ――");
            if (daily.total() == 0) {
                lines.add("・本日の対象ミッションはありません");
            }
            for (DailyItem item : daily.items()) {
                String mark = item.completed() ? "✓ " : "・";
                lines.add(truncate(
                        mark + item.title() + " " + item.progress() + "/" + item.target()
                                + " +" + item.xpReward() + " XP",
                        60));
            }
        }

        Achievements achievements = entry.achievements();
        if (achievements != null) {
            lines.add("―― アチーブメント " + achievements.unlocked() + "/" + achievements.total() + " ――");
            if (!achievements.next().isEmpty()) {
                lines.add("次に近い実績:");
                for (var next : achievements.next()) {
                    lines.add(truncate("・" + next.name() + " " + next.progress() + "/" + next.target(), 60));
                }
            }
        }
        lines.add(onlineLine);
        return List.copyOf(lines);
    }

    /** Incomplete dailies, highest completion ratio first (stable for ties). */
    private static List<DailyItem> sortedIncomplete(Daily daily) {
        return daily.incompleteItems().stream()
                .sorted(Comparator.comparingDouble(SidebarLines::ratio).reversed())
                .toList();
    }

    private static double ratio(DailyItem item) {
        return item.target() > 0 ? (double) item.progress() / item.target() : 0.0;
    }

    static String formatRemaining(Instant resetsAt, Instant now) {
        Duration remaining = Duration.between(now, resetsAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return "まもなく";
        }
        long hours = remaining.toHours();
        long minutes = remaining.toMinutesPart();
        return hours + ":" + (minutes < 10 ? "0" : "") + minutes;
    }

    static String truncate(String line) {
        return truncate(line, MAX_LINE_LENGTH);
    }

    static String truncate(String line, int maxLength) {
        if (line.length() <= maxLength) {
            return line;
        }
        return line.substring(0, Math.max(0, maxLength - 1)) + "…";
    }
}
