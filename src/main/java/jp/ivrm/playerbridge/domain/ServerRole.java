package jp.ivrm.playerbridge.domain;

import java.util.Locale;

/**
 * Logical server roles participating in a transfer.
 */
public enum ServerRole {
    MAIN,
    RESOURCE;

    public static ServerRole parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("server role must not be blank");
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "main" -> MAIN;
            case "resource" -> RESOURCE;
            default -> throw new IllegalArgumentException(
                    "unsupported server role: " + value
            );
        };
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public ServerRole opposite() {
        return this == MAIN ? RESOURCE : MAIN;
    }
}
