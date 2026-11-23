package jp.ivrm.playerbridge.presentation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class EffectsPreferencesTest {
    @TempDir
    Path tempDir;

    private static final UUID PLAYER = UUID.fromString("c389d9ba-ac7c-465e-8fa9-6c3d0840a612");

    @Test
    void effectsAreOnByDefaultAndOptOutsPersistAcrossInstances() {
        Path path = tempDir.resolve("config").resolve("ivrm").resolve("presentation").resolve("effects.properties");
        EffectsPreferences preferences = new EffectsPreferences(path, LoggerFactory.getLogger("test"));
        assertTrue(preferences.effectsEnabled(PLAYER));

        preferences.setEffectsEnabled(PLAYER, false);
        assertFalse(preferences.effectsEnabled(PLAYER));

        EffectsPreferences reloaded = new EffectsPreferences(path, LoggerFactory.getLogger("test"));
        assertFalse(reloaded.effectsEnabled(PLAYER));
        assertTrue(reloaded.effectsEnabled(UUID.randomUUID()));

        reloaded.setEffectsEnabled(PLAYER, true);
        EffectsPreferences again = new EffectsPreferences(path, LoggerFactory.getLogger("test"));
        assertTrue(again.effectsEnabled(PLAYER));
    }
}
