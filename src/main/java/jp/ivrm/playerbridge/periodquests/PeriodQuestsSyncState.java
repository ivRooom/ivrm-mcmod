package jp.ivrm.playerbridge.periodquests;

/**
 * The sync's decision state (ivrm-web #1752 S3): which payload
 * hash the on-disk period chapter files currently carry, and
 * whether the running quest book has actually reloaded them.
 *
 * <p>Files and book are tracked separately on purpose. A swap
 * whose reload fails leaves the correct files on disk with the
 * old book still in memory; the state machine then asks for a
 * reload-only retry on the next poll instead of rewriting the
 * same files or, worse, concluding the sync is done. The state
 * is memory-only: after a bridge restart the first payload is
 * always applied and reloaded once, which is harmless (the
 * writes are byte-identical) and guarantees the book matches
 * the API even if files were edited while the server was down.</p>
 */
public final class PeriodQuestsSyncState {
    public enum Action {
        /** The book already shows this payload; nothing to do. */
        NONE,
        /** The files on disk differ from this payload: swap them, then reload. */
        APPLY_AND_RELOAD,
        /** The files already carry this payload but the book has not reloaded them. */
        RELOAD_ONLY
    }

    private String appliedHash;
    private boolean reloadPending;

    public synchronized Action nextAction(String payloadHash) {
        if (!payloadHash.equals(appliedHash)) {
            return Action.APPLY_AND_RELOAD;
        }
        return reloadPending ? Action.RELOAD_ONLY : Action.NONE;
    }

    /** Records that the files for {@code hash} are now on disk and await a reload. */
    public synchronized void markApplied(String hash) {
        appliedHash = hash;
        reloadPending = true;
    }

    /** Records that the book has reloaded the applied files. */
    public synchronized void markReloaded() {
        reloadPending = false;
    }

    public synchronized String appliedHash() {
        return appliedHash;
    }

    public synchronized boolean reloadPending() {
        return reloadPending;
    }
}
