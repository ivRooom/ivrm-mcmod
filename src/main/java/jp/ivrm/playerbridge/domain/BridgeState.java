package jp.ivrm.playerbridge.domain;

/**
 * Durable transfer states stored in the shared bridge store.
 */
public enum BridgeState {
    IDLE,
    PREPARED,
    IN_TRANSIT,
    IMPORTED,
    COMMITTED,
    ROLLED_BACK,
    RECOVERY_REQUIRED,
    ADMIN_REVIEW
}
