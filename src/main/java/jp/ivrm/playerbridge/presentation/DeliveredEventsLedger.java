package jp.ivrm.playerbridge.presentation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;

/**
 * Local ledger of outbox events this server has already handled
 * (#1584 S4, design §4.5).
 *
 * <p>Delivery from the API is at-least-once: if an ack is lost,
 * the same event is polled again. This ledger is the bridge's
 * duplicate suppressor — an event id recorded here is never
 * rendered twice, even across restarts. It is not a source of
 * truth: if the file is lost, the API's {@code delivered} state
 * still bounds re-delivery, and the worst case is one repeated
 * effect. Entries older than 30 days are pruned on save (every
 * event type expires long before that).</p>
 */
public final class DeliveredEventsLedger {
    static final Duration RETENTION = Duration.ofDays(30);

    private final Path path;
    private final Logger logger;
    private final Properties properties = new Properties();

    public DeliveredEventsLedger(Path path, Logger logger) {
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
                    "IVRM delivered-events ledger could not be read; starting empty: reasonType={}",
                    exception.getClass().getSimpleName());
            properties.clear();
        }
    }

    public synchronized boolean isHandled(String eventId) {
        return properties.getProperty(eventId) != null;
    }

    public synchronized void markHandled(String eventId, Instant at) {
        properties.setProperty(eventId, Long.toString(at.toEpochMilli()));
        prune(at);
        save();
    }

    private void prune(Instant now) {
        long cutoff = now.minus(RETENTION).toEpochMilli();
        List<String> stale = new ArrayList<>();
        for (String eventId : properties.stringPropertyNames()) {
            try {
                if (Long.parseLong(properties.getProperty(eventId)) < cutoff) {
                    stale.add(eventId);
                }
            } catch (NumberFormatException exception) {
                stale.add(eventId);
            }
        }
        stale.forEach(properties::remove);
    }

    private void save() {
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temp)) {
                properties.store(output, "IVRM delivered presentation events (duplicate suppression only)");
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            logger.warn(
                    "IVRM delivered-events ledger could not be saved: reasonType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
