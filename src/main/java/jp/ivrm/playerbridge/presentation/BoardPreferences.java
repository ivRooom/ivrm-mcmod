package jp.ivrm.playerbridge.presentation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * Per-player sidebar visibility preferences for
 * {@code /ivrm board on|off} (#1584 §5.3, #33).
 *
 * <p>The sidebar is hidden by default since #33 (the TAB-list
 * footer is the default board surface); only explicit opt-ins
 * are stored, in {@code config/ivrm/presentation/board.properties}.
 * Files written while the sidebar was on by default stored
 * opt-outs ({@code "off"} rows) instead. Those rows are
 * dropped on load: their owners keep the same effective state
 * — sidebar hidden — because off is now the default, and the
 * old model never recorded an opt-in that could be carried
 * over, so every player starts hidden and opts in with
 * {@code /ivrm board on}.</p>
 *
 * <p>This is a display preference, not durable game state: a
 * lost or unreadable file degrades to "nobody sees the
 * sidebar" rather than failing the module.</p>
 */
public final class BoardPreferences {
    private final Path path;
    private final Logger logger;
    private final Properties properties = new Properties();

    public BoardPreferences(Path path, Logger logger) {
        this.path = path;
        this.logger = logger;
        load();
    }

    private void load() {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException | IllegalArgumentException exception) {
            logger.warn(
                    "IVRM board preferences could not be read; defaults apply: reasonType={}",
                    exception.getClass().getSimpleName());
            properties.clear();
            return;
        }
        // Normalize legacy files from the default-on era to
        // the opt-in model ("only opt-ins are stored").
        boolean droppedLegacy = properties.entrySet().removeIf(
                entry -> !"on".equals(entry.getValue()));
        if (droppedLegacy) {
            save();
        }
    }

    public synchronized boolean boardEnabled(UUID playerUuid) {
        return "on".equals(properties.getProperty(playerUuid.toString()));
    }

    public synchronized void setBoardEnabled(UUID playerUuid, boolean enabled) {
        if (enabled) {
            properties.setProperty(playerUuid.toString(), "on");
        } else {
            properties.remove(playerUuid.toString());
        }
        save();
    }

    private void save() {
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temp)) {
                properties.store(output, "IVRM board preferences (only opt-ins are stored)");
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            logger.warn(
                    "IVRM board preferences could not be saved: reasonType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
