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
 * {@code /ivrm board on|off} (#1584 §5.3).
 *
 * <p>The sidebar is shown by default; only explicit opt-outs are
 * stored, in {@code config/ivrm/presentation/board.properties}.
 * This is a display preference, not durable game state: a lost
 * or unreadable file degrades to "everyone sees the sidebar"
 * rather than failing the module.</p>
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
        }
    }

    public synchronized boolean boardEnabled(UUID playerUuid) {
        return !"off".equals(properties.getProperty(playerUuid.toString()));
    }

    public synchronized void setBoardEnabled(UUID playerUuid, boolean enabled) {
        if (enabled) {
            properties.remove(playerUuid.toString());
        } else {
            properties.setProperty(playerUuid.toString(), "off");
        }
        save();
    }

    private void save() {
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temp)) {
                properties.store(output, "IVRM board preferences (only opt-outs are stored)");
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            logger.warn(
                    "IVRM board preferences could not be saved: reasonType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
