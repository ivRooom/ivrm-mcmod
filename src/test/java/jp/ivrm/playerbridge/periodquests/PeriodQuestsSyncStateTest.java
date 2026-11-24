package jp.ivrm.playerbridge.periodquests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jp.ivrm.playerbridge.periodquests.PeriodQuestsSyncState.Action;
import org.junit.jupiter.api.Test;

final class PeriodQuestsSyncStateTest {
    @Test
    void firstPayloadAppliesThenReloads() {
        PeriodQuestsSyncState state = new PeriodQuestsSyncState();
        assertNull(state.appliedHash());
        assertFalse(state.reloadPending());
        assertEquals(Action.APPLY_AND_RELOAD, state.nextAction("hash-a"));

        state.markApplied("hash-a");
        assertEquals("hash-a", state.appliedHash());
        assertTrue(state.reloadPending());
        // Files are on disk but the book has not reloaded them yet.
        assertEquals(Action.RELOAD_ONLY, state.nextAction("hash-a"));
        // A newer payload supersedes the pending reload.
        assertEquals(Action.APPLY_AND_RELOAD, state.nextAction("hash-b"));

        state.markReloaded();
        assertFalse(state.reloadPending());
        assertEquals(Action.NONE, state.nextAction("hash-a"));
        assertEquals(Action.APPLY_AND_RELOAD, state.nextAction("hash-b"));
    }

    @Test
    void failedReloadStaysPendingUntilItSucceeds() {
        PeriodQuestsSyncState state = new PeriodQuestsSyncState();
        state.markApplied("hash-a");
        // Reload attempts that fail change nothing: the state
        // keeps asking for a reload-only retry.
        assertEquals(Action.RELOAD_ONLY, state.nextAction("hash-a"));
        assertEquals(Action.RELOAD_ONLY, state.nextAction("hash-a"));
        state.markReloaded();
        assertEquals(Action.NONE, state.nextAction("hash-a"));
    }
}
