package jp.ivrm.playerbridge.presentation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class BoardPreferencesTest {
    @TempDir
    Path tempDir;

    private static final UUID PLAYER = UUID.fromString("c389d9ba-ac7c-465e-8fa9-6c3d0840a612");

    @Test
    void boardIsOnByDefaultAndOptOutsPersistAcrossInstances() {
        Path path = tempDir.resolve("config").resolve("ivrm").resolve("presentation").resolve("board.properties");
        BoardPreferences preferences = new BoardPreferences(path, LoggerFactory.getLogger("test"));
        assertTrue(preferences.boardEnabled(PLAYER));

        preferences.setBoardEnabled(PLAYER, false);
        assertFalse(preferences.boardEnabled(PLAYER));

        BoardPreferences reloaded = new BoardPreferences(path, LoggerFactory.getLogger("test"));
        assertFalse(reloaded.boardEnabled(PLAYER));
        assertTrue(reloaded.boardEnabled(UUID.randomUUID()));

        reloaded.setBoardEnabled(PLAYER, true);
        BoardPreferences again = new BoardPreferences(path, LoggerFactory.getLogger("test"));
        assertTrue(again.boardEnabled(PLAYER));
    }
}
