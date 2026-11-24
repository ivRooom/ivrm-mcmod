package jp.ivrm.playerbridge.presentation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class BoardPreferencesTest {
    @TempDir
    Path tempDir;

    private static final UUID PLAYER = UUID.fromString("c389d9ba-ac7c-465e-8fa9-6c3d0840a612");

    private Path boardPath() {
        return tempDir.resolve("config").resolve("ivrm").resolve("presentation").resolve("board.properties");
    }

    @Test
    void boardIsOffByDefaultAndOptInsPersistAcrossInstances() {
        Path path = boardPath();
        BoardPreferences preferences = new BoardPreferences(path, LoggerFactory.getLogger("test"));
        assertFalse(preferences.boardEnabled(PLAYER));

        preferences.setBoardEnabled(PLAYER, true);
        assertTrue(preferences.boardEnabled(PLAYER));

        BoardPreferences reloaded = new BoardPreferences(path, LoggerFactory.getLogger("test"));
        assertTrue(reloaded.boardEnabled(PLAYER));
        assertFalse(reloaded.boardEnabled(UUID.randomUUID()));

        reloaded.setBoardEnabled(PLAYER, false);
        assertFalse(reloaded.boardEnabled(PLAYER));

        BoardPreferences again = new BoardPreferences(path, LoggerFactory.getLogger("test"));
        assertFalse(again.boardEnabled(PLAYER));
    }

    @Test
    void legacyOptOutRowsAreDroppedOnLoadAndStayHidden() throws IOException {
        Path path = boardPath();
        Files.createDirectories(path.getParent());
        // A file from the default-on era: only opt-outs stored.
        Files.writeString(path, PLAYER + "=off\n");

        BoardPreferences preferences = new BoardPreferences(path, LoggerFactory.getLogger("test"));
        // The owner keeps the same effective state (hidden),
        // because off is the default under the opt-in model.
        assertFalse(preferences.boardEnabled(PLAYER));
        // The legacy row is normalized away on load.
        assertFalse(Files.readString(path).contains(PLAYER.toString()));

        // Opting in afterwards works and persists as "on".
        preferences.setBoardEnabled(PLAYER, true);
        BoardPreferences reloaded = new BoardPreferences(path, LoggerFactory.getLogger("test"));
        assertTrue(reloaded.boardEnabled(PLAYER));
    }
}
