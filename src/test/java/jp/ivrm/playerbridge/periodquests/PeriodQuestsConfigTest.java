package jp.ivrm.playerbridge.periodquests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PeriodQuestsConfigTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperties() {
        System.clearProperty("ivrm.periodQuests.enabled");
        System.clearProperty("ivrm.periodQuests.pollSeconds");
    }

    @Test
    void defaultsAreDisabledWithTheDesignInterval() {
        PeriodQuestsConfig config = PeriodQuestsConfig.load(tempDir);
        assertFalse(config.enabled());
        assertEquals(45, config.pollSeconds());
        assertEquals(
                tempDir.resolve("config").resolve("ftbquests").resolve("quests"),
                config.questsDir());
        assertFalse(config.summary().contains("secret"));
    }

    @Test
    void propertiesOverrideTheDefaults() {
        System.setProperty("ivrm.periodQuests.enabled", "true");
        System.setProperty("ivrm.periodQuests.pollSeconds", "60");
        PeriodQuestsConfig config = PeriodQuestsConfig.load(tempDir);
        assertTrue(config.enabled());
        assertEquals(60, config.pollSeconds());
    }

    @Test
    void invalidValuesAreRejected() {
        System.setProperty("ivrm.periodQuests.pollSeconds", "5");
        assertThrows(IllegalArgumentException.class, () -> PeriodQuestsConfig.load(tempDir));
        System.setProperty("ivrm.periodQuests.pollSeconds", "soon");
        assertThrows(IllegalArgumentException.class, () -> PeriodQuestsConfig.load(tempDir));
        System.clearProperty("ivrm.periodQuests.pollSeconds");
        System.setProperty("ivrm.periodQuests.enabled", "yes");
        assertThrows(IllegalArgumentException.class, () -> PeriodQuestsConfig.load(tempDir));
    }
}
