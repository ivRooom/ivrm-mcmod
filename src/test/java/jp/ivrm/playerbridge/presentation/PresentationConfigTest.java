package jp.ivrm.playerbridge.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PresentationConfigTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperties() {
        System.clearProperty("ivrm.presentation.enabled");
        System.clearProperty("ivrm.presentation.snapshotSeconds");
        System.clearProperty("ivrm.presentation.staleSeconds");
    }

    @Test
    void defaultsAreDisabledWithTheDesignIntervals() {
        PresentationConfig config = PresentationConfig.load(tempDir);
        assertFalse(config.enabled());
        assertEquals(45, config.snapshotSeconds());
        assertEquals(Duration.ofSeconds(300), config.staleAfter());
        assertEquals(
                tempDir.resolve("config").resolve("ivrm").resolve("presentation").resolve("board.properties"),
                config.boardPreferencesPath());
        assertFalse(config.summary().contains("secret"));
    }

    @Test
    void propertiesOverrideTheDefaults() {
        System.setProperty("ivrm.presentation.enabled", "true");
        System.setProperty("ivrm.presentation.snapshotSeconds", "60");
        System.setProperty("ivrm.presentation.staleSeconds", "120");
        PresentationConfig config = PresentationConfig.load(tempDir);
        assertTrue(config.enabled());
        assertEquals(60, config.snapshotSeconds());
        assertEquals(Duration.ofSeconds(120), config.staleAfter());
    }

    @Test
    void invalidValuesAreRejected() {
        System.setProperty("ivrm.presentation.snapshotSeconds", "5");
        assertThrows(IllegalArgumentException.class, () -> PresentationConfig.load(tempDir));
        System.setProperty("ivrm.presentation.snapshotSeconds", "soon");
        assertThrows(IllegalArgumentException.class, () -> PresentationConfig.load(tempDir));
        System.clearProperty("ivrm.presentation.snapshotSeconds");
        System.setProperty("ivrm.presentation.enabled", "yes");
        assertThrows(IllegalArgumentException.class, () -> PresentationConfig.load(tempDir));
    }
}
