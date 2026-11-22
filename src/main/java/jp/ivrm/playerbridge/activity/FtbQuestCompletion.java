package jp.ivrm.playerbridge.activity;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code player.ftb_quest_completed} event shape (ivrm-web #1605 /
 * #1608, quest sync S2).
 *
 * <p>FTB Quests records progress per team, and its quest progress
 * event carries the team plus the members who are online — never
 * the single player whose action completed the quest. The portal
 * decision for this sync is that a completion belongs to the
 * player who caused it, so attribution here is deliberately
 * conservative: the completion is reported only when exactly one
 * distinct team member is online, because that member is the
 * only one who can have caused it. A completion with several
 * members online is ambiguous and is not reported rather than
 * being misattributed; on this server teams are effectively solo
 * (a lone player's team id is their own UUID), so the rule keeps
 * full coverage in practice.</p>
 *
 * <p>Quest and chapter ids travel as FTB Quests code strings —
 * the 16-digit uppercase hex form produced by
 * {@code QuestObjectBase.getCodeString()} — which the consumer
 * treats as the canonical spelling of an FTB quest id.</p>
 */
public final class FtbQuestCompletion {

    public static final String EVENT_TYPE = "player.ftb_quest_completed";

    private FtbQuestCompletion() {}

    /**
     * The player a team completion is attributed to: the sole
     * distinct online member, or empty when attribution would be
     * a guess (nobody online, or several members online).
     */
    public static Optional<UUID> attributeTo(Collection<UUID> onlineMemberUuids) {
        UUID sole = null;
        for (UUID uuid : onlineMemberUuids) {
            if (uuid == null) {
                continue;
            }
            if (sole == null) {
                sole = uuid;
            } else if (!sole.equals(uuid)) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(sole);
    }

    /** The {@code player.ftb_quest_completed} attribute set for one completion. */
    public static Map<String, String> attributes(String questCode, String chapterCode) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("quest", questCode);
        if (chapterCode != null && !chapterCode.isEmpty()) {
            attributes.put("chapter", chapterCode);
        }
        return Map.copyOf(attributes);
    }
}
