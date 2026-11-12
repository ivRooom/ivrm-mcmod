package jp.ivrm.playerbridge.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerRoleTest {
    @Test
    void parsesConfiguredRolesCaseInsensitively() {
        assertEquals(ServerRole.MAIN, ServerRole.parse("main"));
        assertEquals(ServerRole.MAIN, ServerRole.parse(" MAIN "));
        assertEquals(ServerRole.RESOURCE, ServerRole.parse("resource"));
        assertEquals(ServerRole.RESOURCE, ServerRole.parse(" Resource "));
    }

    @Test
    void rejectsMissingAndUnsupportedRoles() {
        assertThrows(IllegalArgumentException.class, () -> ServerRole.parse(null));
        assertThrows(IllegalArgumentException.class, () -> ServerRole.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ServerRole.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> ServerRole.parse("survival"));
    }

    @Test
    void oppositeIsSymmetricAndConfigValuesAreStable() {
        for (ServerRole role : ServerRole.values()) {
            assertEquals(role, role.opposite().opposite());
            assertEquals(role, ServerRole.parse(role.configValue()));
        }
        assertEquals("main", ServerRole.MAIN.configValue());
        assertEquals("resource", ServerRole.RESOURCE.configValue());
        assertEquals(ServerRole.RESOURCE, ServerRole.MAIN.opposite());
    }
}
