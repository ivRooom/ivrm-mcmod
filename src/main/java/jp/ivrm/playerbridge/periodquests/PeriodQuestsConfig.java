package jp.ivrm.playerbridge.periodquests;

import java.nio.file.Path;

/**
 * Runtime configuration for the period quest sync module
 * (ivrm-web #1752 S3: the bridge consumer of
 * {@code POST /v1/minecraft/period-quests}).
 *
 * <p>The module is disabled by default and is independent of
 * the Activity sender's and the Presentation module's enable
 * flags: it reuses the sender's server identity (base URL,
 * server id, server role and HMAC secret, all configured
 * through the {@code IVRM_ACTIVITY_*} settings and held by
 * {@code ActivityConfig}) because the API authorizes the
 * period-quests endpoint with the same per-server secret as
 * the presentation snapshot. Only the sync-specific knobs live
 * here. Secrets are never part of {@link #summary()}.</p>
 */
public final class PeriodQuestsConfig {
    private final boolean enabled;
    private final int pollSeconds;
    private final Path questsDir;

    public PeriodQuestsConfig(boolean enabled, int pollSeconds, Path questsDir) {
        this.enabled = enabled;
        this.pollSeconds = pollSeconds;
        this.questsDir = questsDir;
    }

    public static PeriodQuestsConfig load(Path gameDir) {
        boolean enabled = boolSetting("IVRM_PERIOD_QUESTS_ENABLED", "ivrm.periodQuests.enabled", false);
        int pollSeconds = intSetting(
                "IVRM_PERIOD_QUESTS_POLL_SECONDS", "ivrm.periodQuests.pollSeconds", 45, 15, 3600);
        // FTB Quests reads its book from <gameDir>/config/ftbquests/quests;
        // the payload's file paths are relative to that directory.
        Path questsDir = gameDir.resolve("config").resolve("ftbquests").resolve("quests");
        return new PeriodQuestsConfig(enabled, pollSeconds, questsDir);
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

    /** Poll interval for the period-quests payload (design default: 45s). */
    public int pollSeconds() {
        return pollSeconds;
    }

    /**
     * The FTB Quests book directory the payload's relative file
     * paths resolve against. The sync only ever writes the
     * guarded period chapter paths inside this directory (see
     * {@link PeriodQuestFiles}); everything else in it — the
     * permanent quest line above all — is never touched.
     */
    public Path questsDir() {
        return questsDir;
    }

    public String summary() {
        return "enabled=" + enabled
                + ", pollSeconds=" + pollSeconds
                + ", questsDir=" + questsDir;
    }
}
