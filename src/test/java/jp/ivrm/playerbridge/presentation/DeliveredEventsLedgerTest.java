package jp.ivrm.playerbridge.presentation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class DeliveredEventsLedgerTest {
    @TempDir
    Path tempDir;

    private Path ledgerPath() {
        return tempDir.resolve("config").resolve("ivrm").resolve("presentation").resolve("delivered.properties");
    }

    @Test
    void handledIdsPersistAcrossInstances() {
        DeliveredEventsLedger ledger = new DeliveredEventsLedger(ledgerPath(), LoggerFactory.getLogger("test"));
        assertFalse(ledger.isHandled("evt-1"));

        ledger.markHandled("evt-1", Instant.parse("2026-11-23T12:00:00.000Z"));
        assertTrue(ledger.isHandled("evt-1"));
        assertFalse(ledger.isHandled("evt-2"));

        DeliveredEventsLedger reloaded = new DeliveredEventsLedger(ledgerPath(), LoggerFactory.getLogger("test"));
        assertTrue(reloaded.isHandled("evt-1"));
    }

    @Test
    void entriesOlderThanTheRetentionWindowArePrunedOnSave() {
        DeliveredEventsLedger ledger = new DeliveredEventsLedger(ledgerPath(), LoggerFactory.getLogger("test"));
        ledger.markHandled("evt-old", Instant.parse("2026-10-01T00:00:00.000Z"));
        assertTrue(ledger.isHandled("evt-old"));

        // Saving a newer entry prunes everything more than 30 days older than it.
        ledger.markHandled("evt-new", Instant.parse("2026-11-23T00:00:00.000Z"));
        assertFalse(ledger.isHandled("evt-old"));
        assertTrue(ledger.isHandled("evt-new"));

        DeliveredEventsLedger reloaded = new DeliveredEventsLedger(ledgerPath(), LoggerFactory.getLogger("test"));
        assertFalse(reloaded.isHandled("evt-old"));
        assertTrue(reloaded.isHandled("evt-new"));
    }
}
