package jp.ivrm.playerbridge.periodquests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PeriodQuestsJsonTest {
    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void buildsTheExactRequestKeySet() {
        assertEquals(
                "{\"schemaVersion\":1,\"serverRole\":\"main\"}",
                PeriodQuestsJson.buildRequestBody("main"));
    }

    @Test
    void parsesThePayloadEnvelope() {
        String body = """
                {"generatedAt":"2026-11-25T00:00:00Z",
                 "hash":"%s",
                 "files":{"chapters/ivrm_period_daily.json5":"{}",
                          "lang/ja_jp/chapters/0123456789ABCDEF.json5":"{}"},
                 "deleteFiles":["lang/en_us/chapters/FEDCBA9876543210.json5"],
                 "futureField":true}
                """.formatted(HASH);
        PeriodQuestsPayload payload = PeriodQuestsJson.parsePayload(body);
        assertEquals(Instant.parse("2026-11-25T00:00:00Z"), payload.generatedAt());
        assertEquals(HASH, payload.hash());
        assertEquals("{}", payload.files().get("chapters/ivrm_period_daily.json5"));
        assertEquals("{}", payload.files().get("lang/ja_jp/chapters/0123456789ABCDEF.json5"));
        assertEquals(List.of("lang/en_us/chapters/FEDCBA9876543210.json5"), payload.deleteFiles());
    }

    @Test
    void rejectsMalformedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> PeriodQuestsJson.parsePayload("not-json"));
        assertThrows(IllegalArgumentException.class, () -> PeriodQuestsJson.parsePayload("[]"));
        // Missing hash.
        assertThrows(
                IllegalArgumentException.class,
                () -> PeriodQuestsJson.parsePayload(
                        "{\"generatedAt\":\"2026-11-25T00:00:00Z\",\"files\":{},\"deleteFiles\":[]}"));
        // Hash must be lowercase SHA-256 hex.
        assertThrows(
                IllegalArgumentException.class,
                () -> PeriodQuestsJson.parsePayload(
                        "{\"generatedAt\":\"2026-11-25T00:00:00Z\",\"hash\":\""
                                + HASH.toUpperCase()
                                + "\",\"files\":{},\"deleteFiles\":[]}"));
        // File bodies must be strings.
        assertThrows(
                IllegalArgumentException.class,
                () -> PeriodQuestsJson.parsePayload(
                        "{\"generatedAt\":\"2026-11-25T00:00:00Z\",\"hash\":\""
                                + HASH
                                + "\",\"files\":{\"chapters/ivrm_period_daily.json5\":3},\"deleteFiles\":[]}"));
        // Deletions must be strings.
        assertThrows(
                IllegalArgumentException.class,
                () -> PeriodQuestsJson.parsePayload(
                        "{\"generatedAt\":\"2026-11-25T00:00:00Z\",\"hash\":\""
                                + HASH
                                + "\",\"files\":{},\"deleteFiles\":[3]}"));
        // generatedAt must be an instant.
        assertThrows(
                IllegalArgumentException.class,
                () -> PeriodQuestsJson.parsePayload(
                        "{\"generatedAt\":\"tomorrow\",\"hash\":\""
                                + HASH
                                + "\",\"files\":{},\"deleteFiles\":[]}"));
    }

    @Test
    void parsedPayloadVerifiesAgainstTheHashContract() {
        // The S2 golden shape: the hash names the file set, so a
        // payload whose declared hash was computed over exactly
        // these files verifies end to end after parsing.
        String body = """
                {"generatedAt":"2026-11-25T00:00:00Z",
                 "hash":"e0a882ff9fc37f2d52d1072d95a8fe3e30a979839bbbd1b3f88deb8119ec9b39",
                 "files":{"chapters/ivrm_period_daily.json5":"{\\n  id: \\"AAAAAAAAAAAAAAAA\\",\\n}\\n",
                          "lang/ja_jp/chapters/0123456789ABCDEF.json5":"{\\n  \\"chapter.0123456789ABCDEF.title\\": \\"Daily\\"\\n}\\n"},
                 "deleteFiles":["lang/ja_jp/chapters/FEDCBA9876543210.json5",
                                "lang/en_us/chapters/FEDCBA9876543210.json5"]}
                """;
        PeriodQuestsPayload payload = PeriodQuestsJson.parsePayload(body);
        assertTrue(PeriodQuestFiles.hashMatches(payload));
    }
}
