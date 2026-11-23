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
 * Per-player achievement-effect preferences for
 * {@code /ivrm effects on|off} (#1584 §6.1).
 *
 * <p>Effects are shown by default; only explicit opt-outs are
 * stored, in {@code config/ivrm/presentation/effects.properties}
 * — the same pattern as {@link BoardPreferences}. Opting out
 * stops the Title / sound / particles; the chat completion
 * record still arrives and the event is still acked. A lost or
 * unreadable file degrades to "everyone sees effects".</p>
 */
public final class EffectsPreferences {
    private final Path path;
    private final Logger logger;
    private final Properties properties = new Properties();

    public EffectsPreferences(Path path, Logger logger) {
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
                    "IVRM effects preferences could not be read; defaults apply: reasonType={}",
                    exception.getClass().getSimpleName());
            properties.clear();
        }
    }

    public synchronized boolean effectsEnabled(UUID playerUuid) {
        return !"off".equals(properties.getProperty(playerUuid.toString()));
    }

    public synchronized void setEffectsEnabled(UUID playerUuid, boolean enabled) {
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
                properties.store(output, "IVRM effects preferences (only opt-outs are stored)");
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            logger.warn(
                    "IVRM effects preferences could not be saved: reasonType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
