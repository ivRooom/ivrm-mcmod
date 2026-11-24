package jp.ivrm.playerbridge.periodquests;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One period-quests payload from the API (ivrm-web #1752 S2):
 * the generated FTB period chapter files for the current daily
 * / weekly / monthly boards, the previous periods' lang files
 * to remove, and the payload hash naming exactly this file set.
 *
 * <p>File paths are relative to the FTB Quests book directory
 * (see {@link PeriodQuestsConfig#questsDir()}). The hash is the
 * API's canonical SHA-256 over the file set; the bridge
 * recomputes it before applying anything (see
 * {@link PeriodQuestFiles}), so a truncated or tampered body
 * can never reach the book.</p>
 */
public record PeriodQuestsPayload(
        Instant generatedAt, String hash, Map<String, String> files, List<String> deleteFiles) {
    public PeriodQuestsPayload {
        files = Map.copyOf(files);
        deleteFiles = List.copyOf(deleteFiles);
    }
}
