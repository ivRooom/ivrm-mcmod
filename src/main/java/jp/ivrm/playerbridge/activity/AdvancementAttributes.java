package jp.ivrm.playerbridge.activity;

import java.util.Map;

/**
 * {@code player.advancement} attributes (ivrm-web #1433, M9).
 *
 * <p>Advancements are one-shot milestones rather than statistics, so they
 * travel as their own event type instead of through the statistics differ.
 * Recipe unlock advancements are internal bookkeeping — discovering or
 * smelting something new grants one each time — so they are excluded: only
 * advancements whose path does not start with {@code recipes/} are
 * reported, for the vanilla and mod namespaces alike.</p>
 */
public final class AdvancementAttributes {

    private AdvancementAttributes() {}

    /** Whether earning the advancement should be reported as an event. */
    public static boolean isReportable(String advancementId) {
        String path = advancementId;
        int separator = advancementId.indexOf(':');
        if (separator >= 0) {
            path = advancementId.substring(separator + 1);
        }
        return !path.startsWith("recipes/");
    }

    /** The {@code player.advancement} attribute set for one earned advancement. */
    public static Map<String, String> attributes(String advancementId) {
        return Map.of("advancement", advancementId);
    }
}
