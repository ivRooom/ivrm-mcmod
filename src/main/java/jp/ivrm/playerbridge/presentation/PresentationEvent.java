package jp.ivrm.playerbridge.presentation;

import java.time.Instant;
import java.util.UUID;

/**
 * One Presentation Outbox event returned by
 * {@code POST /v1/minecraft/presentation/events} (#1584 S4).
 *
 * <p>An event mirrors a completion / all-clear / unlock the API
 * already recorded in its source-of-truth ledgers; the bridge
 * only renders the effect. The payload carries exactly what the
 * renderer needs (display title, reward amounts, rank, effect
 * profile) — the bridge never re-derives progress or rewards.
 * Payload fields are nullable because the three event types use
 * different subsets.</p>
 */
public record PresentationEvent(
        String eventId,
        UUID uuid,
        String eventType,
        Payload payload,
        Instant createdAt,
        Instant expiresAt) {

    public static final String TYPE_QUEST_COMPLETED = "quest_completed";
    public static final String TYPE_QUEST_ALL_CLEAR = "quest_all_clear";
    public static final String TYPE_ACHIEVEMENT_UNLOCKED = "achievement_unlocked";

    /** Effect intensity sent by the API: standard / celebration / grand. */
    public record Payload(
            String questKey,
            String title,
            String periodType,
            String periodKey,
            Integer xpReward,
            Integer pointsAmount,
            String code,
            String name,
            String rank,
            String tier,
            String effectProfile) {

        public static Payload empty() {
            return new Payload(null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    public boolean isAchievementUnlock() {
        return TYPE_ACHIEVEMENT_UNLOCKED.equals(eventType);
    }

    public boolean isAllClear() {
        return TYPE_QUEST_ALL_CLEAR.equals(eventType);
    }
}
