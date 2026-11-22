package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class FtbQuestCompletionTest {
    private static final UUID PLAYER_A = UUID.fromString("018f4b20-8a6f-7a2a-8f4b-1234567890ac");
    private static final UUID PLAYER_B = UUID.fromString("018f4b20-8a6f-7a2a-8f4b-1234567890ad");

    @Test
    void attributesTheSoleOnlineMember() {
        assertEquals(PLAYER_A, FtbQuestCompletion.attributeTo(List.of(PLAYER_A)).orElseThrow());
    }

    @Test
    void attributesWhenTheSameMemberAppearsTwice() {
        // Defensive: a duplicated entry for one member is still
        // a single distinct member, not an ambiguity.
        assertEquals(
                PLAYER_A,
                FtbQuestCompletion.attributeTo(List.of(PLAYER_A, PLAYER_A)).orElseThrow());
    }

    @Test
    void doesNotAttributeWhenNobodyIsOnline() {
        assertTrue(FtbQuestCompletion.attributeTo(List.of()).isEmpty());
    }

    @Test
    void doesNotAttributeWhenSeveralMembersAreOnline() {
        // Team progress is shared, so with two members online the
        // player who caused the completion cannot be told apart;
        // the completion must not be reported under a guess.
        assertTrue(FtbQuestCompletion.attributeTo(List.of(PLAYER_A, PLAYER_B)).isEmpty());
    }

    @Test
    void attributesCarryQuestAndChapterCodeStrings() {
        Map<String, String> attributes =
                FtbQuestCompletion.attributes("4AB97123575988B4", "4F77846D40DA8ED3");
        assertEquals(
                Map.of("quest", "4AB97123575988B4", "chapter", "4F77846D40DA8ED3"), attributes);
    }

    @Test
    void attributesOmitAMissingChapter() {
        assertEquals(
                Map.of("quest", "4AB97123575988B4"),
                FtbQuestCompletion.attributes("4AB97123575988B4", null));
        assertEquals(
                Map.of("quest", "4AB97123575988B4"),
                FtbQuestCompletion.attributes("4AB97123575988B4", ""));
    }

    @Test
    void eventTypeIsTheCanonicalQuestSyncType() {
        assertEquals("player.ftb_quest_completed", FtbQuestCompletion.EVENT_TYPE);
    }
}
