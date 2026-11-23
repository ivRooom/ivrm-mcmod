package jp.ivrm.playerbridge.presentation;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Runtime configuration for the Minecraft Presentation module
 * (#1584 S3: in-game sidebar and {@code /ivrm today}).
 *
 * <p>The module is disabled by default and is independent of the
 * Activity sender's enable flag: it reuses the sender's server
 * identity (base URL, server id, server role and HMAC secret, all
 * configured through the {@code IVRM_ACTIVITY_*} settings and held
 * by {@code ActivityConfig}) because the API authorizes the
 * presentation snapshot with the same per-server secret as the
 * activity ingest. Only the presentation-specific knobs live here.
 * Secrets are never part of {@link #summary()}.</p>
 */
public final class PresentationConfig {
    private final boolean enabled;
    private final int snapshotSeconds;
    private final int staleSeconds;
    private final Path stateDir;

    public PresentationConfig(boolean enabled, int snapshotSeconds, int staleSeconds, Path stateDir) {
        this.enabled = enabled;
        this.snapshotSeconds = snapshotSeconds;
        this.staleSeconds = staleSeconds;
        this.stateDir = stateDir;
    }

    public static PresentationConfig load(Path gameDir) {
        boolean enabled = boolSetting("IVRM_PRESENTATION_ENABLED", "ivrm.presentation.enabled", false);
        int snapshotSeconds = intSetting(
                "IVRM_PRESENTATION_SNAPSHOT_SECONDS", "ivrm.presentation.snapshotSeconds", 45, 15, 3600);
        int staleSeconds = intSetting(
                "IVRM_PRESENTATION_STALE_SECONDS", "ivrm.presentation.staleSeconds", 300, 30, 3600);
        Path stateDir = gameDir.resolve("config").resolve("ivrm").resolve("presentation");
        return new PresentationConfig(enabled, snapshotSeconds, staleSeconds, stateDir);
    }

    private static String setting(String environmentName, String propertyName, String fallback) {
        String property = System.getProperty(propertyName);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String environment = System.getenv(environmentName);
        return environment == null || environment.isBlank() ? fallback : environment;
    }

    private static boolean boolSetting(String environmentName, String propertyName, boolean fallback) {
        String raw = setting(environmentName, propertyName, Boolean.toString(fallback));
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        throw new IllegalArgumentException(environmentName + " must be true or false");
    }

    private static int intSetting(
            String environmentName,
            String propertyName,
            int fallback,
            int minimum,
            int maximum) {
        String raw = setting(environmentName, propertyName, Integer.toString(fallback));
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(
                        environmentName + " must be between " + minimum + " and " + maximum);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(environmentName + " must be an integer", exception);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** Poll interval for the presentation snapshot (design default: 45s). */
    public int snapshotSeconds() {
        return snapshotSeconds;
    }

    /**
     * How long a fetched snapshot may keep rendering its dynamic
     * progress sections. Beyond this the sidebar degrades to the
     * server basics so stale progress is never shown as current
     * (design default: 300s).
     */
    public Duration staleAfter() {
        return Duration.ofSeconds(staleSeconds);
    }

    public Path stateDir() {
        return stateDir;
    }

    /** File holding the per-player sidebar on/off preferences. */
    public Path boardPreferencesPath() {
        return stateDir.resolve("board.properties");
    }

    public String summary() {
        return "enabled=" + enabled
                + ", snapshotSeconds=" + snapshotSeconds
                + ", staleSeconds=" + staleSeconds;
    }
}
