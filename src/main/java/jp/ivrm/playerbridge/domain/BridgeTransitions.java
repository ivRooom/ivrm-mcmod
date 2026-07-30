package jp.ivrm.playerbridge.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Central transition policy used by commands, recovery, and persistence.
 */
public final class BridgeTransitions {
    private static final Map<BridgeState, Set<BridgeState>> ALLOWED =
            new EnumMap<>(BridgeState.class);

    static {
        allow(BridgeState.IDLE, BridgeState.PREPARED);
        allow(
                BridgeState.PREPARED,
                BridgeState.IN_TRANSIT,
                BridgeState.ROLLED_BACK,
                BridgeState.ADMIN_REVIEW
        );
        allow(
                BridgeState.IN_TRANSIT,
                BridgeState.IMPORTED,
                BridgeState.RECOVERY_REQUIRED,
                BridgeState.ADMIN_REVIEW
        );
        allow(
                BridgeState.IMPORTED,
                BridgeState.COMMITTED,
                BridgeState.RECOVERY_REQUIRED,
                BridgeState.ADMIN_REVIEW
        );
        allow(BridgeState.COMMITTED, BridgeState.IDLE);
        allow(BridgeState.ROLLED_BACK, BridgeState.IDLE);
        allow(
                BridgeState.RECOVERY_REQUIRED,
                BridgeState.ROLLED_BACK,
                BridgeState.IMPORTED,
                BridgeState.ADMIN_REVIEW
        );
        allow(
                BridgeState.ADMIN_REVIEW,
                BridgeState.ROLLED_BACK,
                BridgeState.IMPORTED,
                BridgeState.COMMITTED
        );
    }

    private BridgeTransitions() {
    }

    public static boolean canTransition(BridgeState from, BridgeState to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireTransition(BridgeState from, BridgeState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    "invalid bridge state transition: " + from + " -> " + to
            );
        }
    }

    private static void allow(BridgeState from, BridgeState... targets) {
        ALLOWED.put(from, EnumSet.of(targets[0], targets));
    }
}
