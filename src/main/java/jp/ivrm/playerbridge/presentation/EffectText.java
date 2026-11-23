package jp.ivrm.playerbridge.presentation;

import java.util.ArrayList;
import java.util.List;
import jp.ivrm.playerbridge.presentation.PresentationEvent.Payload;

/**
 * Pure rendering decisions and text for outbox effects
 * (#1584 S4, design §6).
 *
 * <p>Kept free of Minecraft classes so the wording and the
 * profile mapping are unit-testable. The rules, per the design
 * confirmed by Kanata:</p>
 * <ul>
 *   <li>Effects target the achiever only.</li>
 *   <li>A normal quest completion is ActionBar + chat; an
 *   all-clear or an achievement unlock is Title + sound +
 *   particles. The API's effectProfile (standard / celebration
 *   / grand) only grades the sound and particles.</li>
 *   <li>Offline catch-up: achievements get one simplified Title
 *   on the next login; quest completions never get a Title
 *   retroactively — they fold into a login chat summary.</li>
 *   <li>With effects off (globally or per player), the chat
 *   record still arrives; only the overlay / sound / particles
 *   are skipped.</li>
 * </ul>
 */
public final class EffectText {
    private EffectText() {
    }

    public enum Presentation {
        ACTION_BAR,
        TITLE,
        CHAT_ONLY
    }

    public enum SoundProfile {
        NONE,
        SMALL,
        LEVELUP,
        CHALLENGE
    }

    public enum ParticleProfile {
        NONE,
        SMALL,
        FIREWORK
    }

    public record RenderPlan(
            Presentation presentation,
            String titleText,
            String subtitleText,
            String actionBarText,
            List<String> chatLines,
            SoundProfile sound,
            ParticleProfile particles) {
        public RenderPlan {
            chatLines = List.copyOf(chatLines);
        }
    }

    /** Live delivery: the player was online when the event arrived. */
    public static RenderPlan planLive(PresentationEvent event, boolean effectsEnabled) {
        Payload payload = event.payload();
        List<String> chat = new ArrayList<>();
        if (event.isAllClear()) {
            chat.add("[IVRM] " + periodWord(payload.periodType()) + missionWord(payload.periodType())
                    + "をすべて完了しました。詳細は /ivrm today");
            if (!effectsEnabled) {
                return chatOnly(chat);
            }
            return new RenderPlan(
                    Presentation.TITLE,
                    missionWord(payload.periodType()) + "全完了！",
                    "おつかれさま！ 全クリアボーナス +" + amount(payload.pointsAmount()) + " pt",
                    null,
                    chat,
                    SoundProfile.CHALLENGE,
                    ParticleProfile.FIREWORK);
        }
        if (event.isAchievementUnlock()) {
            chat.add(achievementChatLine(payload));
            if (!effectsEnabled) {
                return chatOnly(chat);
            }
            boolean grand = "grand".equals(payload.effectProfile());
            return new RenderPlan(
                    Presentation.TITLE,
                    "アチーブメント解除！",
                    payload.name() == null ? "" : payload.name(),
                    null,
                    chat,
                    grand ? SoundProfile.CHALLENGE : SoundProfile.LEVELUP,
                    grand ? ParticleProfile.FIREWORK : ParticleProfile.SMALL);
        }
        // quest_completed
        String label = "daily".equals(payload.periodType()) ? "デイリー" : "クエスト";
        chat.add("[IVRM] " + missionWord(payload.periodType()) + "「" + title(payload)
                + "」を完了しました（+" + amount(payload.xpReward()) + " XP）");
        if (!effectsEnabled) {
            return chatOnly(chat);
        }
        boolean celebration = "celebration".equals(payload.effectProfile());
        return new RenderPlan(
                Presentation.ACTION_BAR,
                null,
                null,
                "✓ " + label + "完了: " + title(payload) + "  +" + amount(payload.xpReward()) + " XP",
                chat,
                celebration ? SoundProfile.LEVELUP : SoundProfile.SMALL,
                celebration ? ParticleProfile.SMALL : ParticleProfile.NONE);
    }

    /**
     * Login catch-up for events that completed while the player
     * was offline (design §11-3): achievements get one
     * simplified Title, quest events fold into chat only.
     */
    public static RenderPlan planCatchUp(PresentationEvent event, boolean effectsEnabled) {
        Payload payload = event.payload();
        List<String> chat = new ArrayList<>();
        if (event.isAchievementUnlock()) {
            chat.add(achievementChatLine(payload));
            if (!effectsEnabled) {
                return chatOnly(chat);
            }
            return new RenderPlan(
                    Presentation.TITLE,
                    "アチーブメント解除！",
                    payload.name() == null ? "" : payload.name(),
                    null,
                    chat,
                    SoundProfile.SMALL,
                    ParticleProfile.NONE);
        }
        if (event.isAllClear()) {
            chat.add("[IVRM] オフライン中に" + missionWord(payload.periodType())
                    + "の全クリアを達成しました（ボーナス +"
                    + amount(payload.pointsAmount()) + " pt）");
            return chatOnly(chat);
        }
        chat.add("[IVRM] オフライン中に完了: " + missionWord(payload.periodType()) + "「"
                + title(payload) + "」（+" + amount(payload.xpReward()) + " XP）");
        return chatOnly(chat);
    }

    private static RenderPlan chatOnly(List<String> chat) {
        return new RenderPlan(
                Presentation.CHAT_ONLY, null, null, null, chat, SoundProfile.NONE, ParticleProfile.NONE);
    }

    private static String achievementChatLine(Payload payload) {
        StringBuilder line = new StringBuilder("[IVRM] アチーブメント「")
                .append(payload.name() == null ? "" : payload.name())
                .append("」を解除しました");
        List<String> details = new ArrayList<>();
        if (payload.rank() != null && !payload.rank().isBlank()) {
            details.add(payload.rank());
        }
        if (payload.xpReward() != null) {
            details.add("+" + payload.xpReward() + " XP");
        }
        if (!details.isEmpty()) {
            line.append("（").append(String.join("、", details)).append("）");
        }
        return line.toString();
    }

    private static String title(Payload payload) {
        return payload.title() == null ? "" : payload.title();
    }

    private static int amount(Integer value) {
        return value == null ? 0 : value;
    }

    /** 今日の / 今週の / 今月の, for all-clear wording. */
    private static String periodWord(String periodType) {
        if ("weekly".equals(periodType)) {
            return "今週の";
        }
        if ("monthly".equals(periodType)) {
            return "今月の";
        }
        return "今日の";
    }

    /** デイリーミッション / ウィークリーミッション / …, for completion wording. */
    private static String missionWord(String periodType) {
        if ("weekly".equals(periodType)) {
            return "ウィークリーミッション";
        }
        if ("monthly".equals(periodType)) {
            return "マンスリーミッション";
        }
        if ("event".equals(periodType)) {
            return "イベントクエスト";
        }
        return "デイリーミッション";
    }
}
