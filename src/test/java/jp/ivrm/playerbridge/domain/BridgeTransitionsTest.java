package jp.ivrm.playerbridge.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BridgeTransitionsTest {
    @Test
    void normalTransferTransitionsAreAllowed() {
        assertTrue(BridgeTransitions.canTransition(BridgeState.IDLE, BridgeState.PREPARED));
        assertTrue(BridgeTransitions.canTransition(BridgeState.PREPARED, BridgeState.IN_TRANSIT));
        assertTrue(BridgeTransitions.canTransition(BridgeState.IN_TRANSIT, BridgeState.IMPORTED));
        assertTrue(BridgeTransitions.canTransition(BridgeState.IMPORTED, BridgeState.COMMITTED));
        assertTrue(BridgeTransitions.canTransition(BridgeState.COMMITTED, BridgeState.IDLE));
    }

    @Test
    void recoveryTransitionsAreExplicitlyAllowed() {
        assertTrue(BridgeTransitions.canTransition(BridgeState.PREPARED, BridgeState.ROLLED_BACK));
        assertTrue(BridgeTransitions.canTransition(BridgeState.IN_TRANSIT, BridgeState.RECOVERY_REQUIRED));
        assertTrue(BridgeTransitions.canTransition(BridgeState.RECOVERY_REQUIRED, BridgeState.ADMIN_REVIEW));
        assertTrue(BridgeTransitions.canTransition(BridgeState.ADMIN_REVIEW, BridgeState.ROLLED_BACK));
        assertTrue(BridgeTransitions.canTransition(BridgeState.ROLLED_BACK, BridgeState.IDLE));
    }

    @Test
    void invalidTransitionsFailClosed() {
        assertFalse(BridgeTransitions.canTransition(BridgeState.IDLE, BridgeState.IMPORTED));
        assertFalse(BridgeTransitions.canTransition(BridgeState.PREPARED, BridgeState.COMMITTED));
        assertFalse(BridgeTransitions.canTransition(BridgeState.IMPORTED, BridgeState.ROLLED_BACK));
        assertFalse(BridgeTransitions.canTransition(BridgeState.COMMITTED, BridgeState.PREPARED));
        assertFalse(BridgeTransitions.canTransition(null, BridgeState.IDLE));
        assertFalse(BridgeTransitions.canTransition(BridgeState.IDLE, null));
        assertThrows(IllegalStateException.class,
                () -> BridgeTransitions.requireTransition(BridgeState.IDLE, BridgeState.COMMITTED));
        assertDoesNotThrow(() ->
                BridgeTransitions.requireTransition(BridgeState.IDLE, BridgeState.PREPARED));
    }

    @Test
    void noStateAllowsSelfTransition() {
        for (BridgeState state : BridgeState.values()) {
            assertFalse(BridgeTransitions.canTransition(state, state), state.name());
        }
    }
}
