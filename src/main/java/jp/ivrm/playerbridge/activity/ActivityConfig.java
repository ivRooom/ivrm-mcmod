package jp.ivrm.playerbridge.activity;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Runtime configuration for the Minecraft Activity sender.
 *
 * <p>The sender is disabled by default. Secrets are intentionally excluded from
 * {@link #summary()} so operators can log the effective configuration without
 * leaking credentials.</p>
 */
public final class ActivityConfig {
    private static final String DEFAULT_BASE_URL = "https://api.ivrm.jp";

    private final boolean enabled;
    private final URI baseUri;
    private final String serverId;
    private final String serverRole;
    private final String secret;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int heartbeatSeconds;
    private final int afkThresholdSeconds;
    private final int maxQueueEntries;
    private final int maxAttempts;
    private final Path queuePath;
    private final Path deadLetterPath;
    private final Path corruptPath;

    public ActivityConfig(
            boolean enabled,
            URI baseUri,
            String serverId,
            String serverRole,
            String secret,
            Duration connectTimeout,
            Duration requestTimeout,
            int heartbeatSeconds,
            int afkThresholdSeconds,
            int maxQueueEntries,
            int maxAttempts,
            Path queuePath,
            Path deadLetterPath,
            Path corruptPath) {
        this.enabled = enabled;
        this.baseUri = baseUri;
        this.serverId = serverId;
        this.serverRole = serverRole;
        this.secret = secret;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.heartbeatSeconds = heartbeatSeconds;
        this.afkThresholdSeconds = afkThresholdSeconds;
        this.maxQueueEntries = maxQueueEntries;
        this.maxAttempts = maxAttempts;
        this.queuePath = queuePath;
        this.deadLetterPath = deadLetterPath;
        this.corruptPath = corruptPath;
    }

    public static ActivityConfig load(Path gameDir) {
        boolean enabled = boolSetting("IVRM_ACTIVITY_ENABLED", "ivrm.activity.enabled", false);
        URI baseUri = URI.create(setting("IVRM_ACTIVITY_BASE_URL", "ivrm.activity.baseUrl", DEFAULT_BASE_URL));
        String serverId = setting("IVRM_ACTIVITY_SERVER_ID", "ivrm.activity.serverId", "").trim();
        String serverRole = setting("IVRM_ACTIVITY_SERVER_ROLE", "ivrm.activity.serverRole", "main")
                .trim()
                .toLowerCase(Locale.ROOT);
        String secret = setting("IVRM_ACTIVITY_SERVER_SECRET", "ivrm.activity.serverSecret", "");

        validateBaseUri(baseUri);
        if (!serverRole.equals("main") && !serverRole.equals("resource")) {
            throw new IllegalArgumentException("IVRM_ACTIVITY_SERVER_ROLE must be main or resource");
        }
        if (enabled && serverId.isBlank()) {
            throw new IllegalArgumentException("IVRM_ACTIVITY_SERVER_ID is required when activity sender is enabled");
        }
        if (enabled && secret.isBlank()) {
            throw new IllegalArgumentException("IVRM_ACTIVITY_SERVER_SECRET is required when activity sender is enabled");
        }

        int connectTimeoutSeconds = intSetting(
                "IVRM_ACTIVITY_CONNECT_TIMEOUT_SECONDS", "ivrm.activity.connectTimeoutSeconds", 5, 1, 60);
        int requestTimeoutSeconds = intSetting(
                "IVRM_ACTIVITY_REQUEST_TIMEOUT_SECONDS", "ivrm.activity.requestTimeoutSeconds", 10, 1, 120);
        int heartbeatSeconds = intSetting(
                "IVRM_ACTIVITY_HEARTBEAT_SECONDS", "ivrm.activity.heartbeatSeconds", 30, 5, 3600);
        int afkThresholdSeconds = intSetting(
                "IVRM_ACTIVITY_AFK_SECONDS", "ivrm.activity.afkSeconds", 300, 30, 86400);
        int maxQueueEntries = intSetting(
                "IVRM_ACTIVITY_QUEUE_MAX", "ivrm.activity.queueMax", 10_000, 100, 1_000_000);
        int maxAttempts = intSetting(
                "IVRM_ACTIVITY_MAX_ATTEMPTS", "ivrm.activity.maxAttempts", 20, 1, 1000);

        Path stateDir = gameDir.resolve("config").resolve("ivrm").resolve("activity");
        return new ActivityConfig(
                enabled,
                baseUri,
                serverId,
                serverRole,
                secret,
                Duration.ofSeconds(connectTimeoutSeconds),
                Duration.ofSeconds(requestTimeoutSeconds),
                heartbeatSeconds,
                afkThresholdSeconds,
                maxQueueEntries,
                maxAttempts,
                stateDir.resolve("queue.ndjson"),
                stateDir.resolve("dead-letter.ndjson"),
                stateDir.resolve("corrupt.ndjson"));
    }

    private static void validateBaseUri(URI baseUri) {
        String scheme = baseUri.getScheme();
        if (scheme == null || baseUri.getHost() == null) {
            throw new IllegalArgumentException("IVRM_ACTIVITY_BASE_URL must be an absolute HTTP(S) URL");
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        boolean localHttp = "http".equalsIgnoreCase(scheme)
                && ("localhost".equalsIgnoreCase(baseUri.getHost())
                        || "127.0.0.1".equals(baseUri.getHost())
                        || "::1".equals(baseUri.getHost()));
        if (!localHttp) {
            throw new IllegalArgumentException("Activity API requires HTTPS except for localhost development");
        }
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

    public URI baseUri() {
        return baseUri;
    }

    public String serverId() {
        return serverId;
    }

    public String serverRole() {
        return serverRole;
    }

    public String secret() {
        return secret;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public int heartbeatSeconds() {
        return heartbeatSeconds;
    }

    public int afkThresholdSeconds() {
        return afkThresholdSeconds;
    }

    public int maxQueueEntries() {
        return maxQueueEntries;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Path queuePath() {
        return queuePath;
    }

    public Path deadLetterPath() {
        return deadLetterPath;
    }

    public Path corruptPath() {
        return corruptPath;
    }

    public String summary() {
        return "enabled=" + enabled
                + ", baseUri=" + baseUri
                + ", serverId=" + (serverId.isBlank() ? "<unset>" : serverId)
                + ", serverRole=" + serverRole
                + ", secretConfigured=" + !secret.isBlank()
                + ", heartbeatSeconds=" + heartbeatSeconds
                + ", afkThresholdSeconds=" + afkThresholdSeconds
                + ", maxQueueEntries=" + maxQueueEntries
                + ", maxAttempts=" + maxAttempts;
    }
}
