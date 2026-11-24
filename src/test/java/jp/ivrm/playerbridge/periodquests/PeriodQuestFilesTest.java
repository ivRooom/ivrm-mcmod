package jp.ivrm.playerbridge.periodquests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PeriodQuestFilesTest {
    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    private static final String DAILY_BODY = "{\n  id: \"AAAAAAAAAAAAAAAA\",\n}\n";
    private static final String LANG_BODY = "{\n  \"chapter.0123456789ABCDEF.title\": \"Daily\"\n}\n";
    // SHA-256 over the canonical form the S2 generator defines,
    // computed independently of this implementation.
    private static final String HASH_WITH_DELETES =
            "e0a882ff9fc37f2d52d1072d95a8fe3e30a979839bbbd1b3f88deb8119ec9b39";
    private static final String HASH_WITHOUT_DELETES =
            "b80d15c849494329dbc195d5cce7df78e3f28a1a4d180928618e06808a1a15fb";

    private static Map<String, String> goldenFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("chapters/ivrm_period_daily.json5", DAILY_BODY);
        files.put("lang/ja_jp/chapters/0123456789ABCDEF.json5", LANG_BODY);
        return files;
    }

    private static List<String> goldenDeletes() {
        return List.of(
                "lang/ja_jp/chapters/FEDCBA9876543210.json5",
                "lang/en_us/chapters/FEDCBA9876543210.json5");
    }

    private static PeriodQuestsPayload payload(Map<String, String> files, List<String> deletes) {
        return new PeriodQuestsPayload(NOW, HASH_WITH_DELETES, files, deletes);
    }

    @Test
    void writeGuardAdmitsOnlyThePeriodChapterPaths() {
        assertTrue(PeriodQuestFiles.isAllowedWritePath("chapters/ivrm_period_daily.json5"));
        assertTrue(PeriodQuestFiles.isAllowedWritePath("chapters/ivrm_period_weekly.json5"));
        assertTrue(PeriodQuestFiles.isAllowedWritePath("chapters/ivrm_period_monthly.json5"));
        assertTrue(PeriodQuestFiles.isAllowedWritePath("lang/ja_jp/chapters/0123456789ABCDEF.json5"));
        assertTrue(PeriodQuestFiles.isAllowedWritePath("lang/en_us/chapters/0123456789ABCDEF.json5"));

        // The permanent line and everything else stay out of reach.
        assertFalse(PeriodQuestFiles.isAllowedWritePath("chapters/welcome.json5"));
        assertFalse(PeriodQuestFiles.isAllowedWritePath("chapters/ivrm_period_yearly.json5"));
        assertFalse(PeriodQuestFiles.isAllowedWritePath("chapter_groups.json5"));
        assertFalse(PeriodQuestFiles.isAllowedWritePath("lang/ja_jp/chapters/0123456789abcdef.json5"));
        assertFalse(PeriodQuestFiles.isAllowedWritePath("lang/fr_fr/chapters/0123456789ABCDEF.json5"));
        assertFalse(PeriodQuestFiles.isAllowedWritePath("lang/ja_jp/chapters/0123456789ABCDEF.json5.bak"));
        assertFalse(PeriodQuestFiles.isAllowedWritePath("../chapters/ivrm_period_daily.json5"));
        assertFalse(PeriodQuestFiles.isAllowedWritePath("/chapters/ivrm_period_daily.json5"));
        assertFalse(PeriodQuestFiles.isAllowedWritePath("chapters\\ivrm_period_daily.json5"));
        assertFalse(PeriodQuestFiles.isAllowedWritePath(null));
    }

    @Test
    void deleteGuardAdmitsOnlyLangFiles() {
        assertTrue(PeriodQuestFiles.isAllowedDeletePath("lang/ja_jp/chapters/FEDCBA9876543210.json5"));
        assertTrue(PeriodQuestFiles.isAllowedDeletePath("lang/en_us/chapters/FEDCBA9876543210.json5"));
        // Chapter files are overwritten, never deleted.
        assertFalse(PeriodQuestFiles.isAllowedDeletePath("chapters/ivrm_period_daily.json5"));
        assertFalse(PeriodQuestFiles.isAllowedDeletePath("chapters/welcome.json5"));
        assertFalse(PeriodQuestFiles.isAllowedDeletePath(null));
    }

    @Test
    void hashMatchesTheGeneratorCanonicalForm() {
        assertEquals(HASH_WITH_DELETES, PeriodQuestFiles.computeHash(goldenFiles(), goldenDeletes()));
        assertEquals(HASH_WITHOUT_DELETES, PeriodQuestFiles.computeHash(goldenFiles(), List.of()));
        // Deletion order in the payload does not matter: the
        // canonical form sorts it.
        List<String> reversed = List.of(goldenDeletes().get(1), goldenDeletes().get(0));
        assertEquals(HASH_WITH_DELETES, PeriodQuestFiles.computeHash(goldenFiles(), reversed));
    }

    @Test
    void hashChangesWithAnyBodyChange() {
        Map<String, String> changed = goldenFiles();
        changed.put("chapters/ivrm_period_daily.json5", DAILY_BODY + " ");
        assertFalse(PeriodQuestFiles.computeHash(changed, goldenDeletes()).equals(HASH_WITH_DELETES));
        PeriodQuestsPayload tampered = payload(changed, goldenDeletes());
        assertFalse(PeriodQuestFiles.hashMatches(tampered));
        assertTrue(PeriodQuestFiles.hashMatches(payload(goldenFiles(), goldenDeletes())));
    }

    @Test
    void validateAcceptsTheGoldenPayload() {
        PeriodQuestFiles.validate(payload(goldenFiles(), goldenDeletes()));
    }

    @Test
    void validateRejectsPathsOutsideTheGuards() {
        Map<String, String> badWrite = goldenFiles();
        badWrite.put("chapters/welcome.json5", "{}");
        assertThrows(IllegalArgumentException.class, () -> PeriodQuestFiles.validate(payload(badWrite, goldenDeletes())));

        assertThrows(
                IllegalArgumentException.class,
                () -> PeriodQuestFiles.validate(payload(goldenFiles(), List.of("chapters/ivrm_period_daily.json5"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> PeriodQuestFiles.validate(payload(goldenFiles(), List.of("chapters/welcome.json5"))));
    }

    @Test
    void validateRejectsStructuralProblems() {
        // A path cannot be written and deleted in the same payload.
        assertThrows(
                IllegalArgumentException.class,
                () -> PeriodQuestFiles.validate(payload(
                        goldenFiles(), List.of("lang/ja_jp/chapters/0123456789ABCDEF.json5"))));
        // Duplicate deletions.
        assertThrows(
                IllegalArgumentException.class,
                () -> PeriodQuestFiles.validate(payload(
                        goldenFiles(),
                        List.of(
                                "lang/ja_jp/chapters/FEDCBA9876543210.json5",
                                "lang/ja_jp/chapters/FEDCBA9876543210.json5"))));
        // Oversized body.
        Map<String, String> oversized = goldenFiles();
        oversized.put("chapters/ivrm_period_daily.json5", "x".repeat(PeriodQuestFiles.MAX_FILE_CHARS + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> PeriodQuestFiles.validate(payload(oversized, goldenDeletes())));
        // Too many files.
        Map<String, String> many = new LinkedHashMap<>();
        for (int index = 0; index <= PeriodQuestFiles.MAX_FILES; index++) {
            many.put(String.format("lang/ja_jp/chapters/%016X.json5", index), "{}");
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> PeriodQuestFiles.validate(payload(many, List.of())));
        // Too many deletions.
        List<String> manyDeletes = new ArrayList<>();
        for (int index = 0; index <= PeriodQuestFiles.MAX_DELETE_FILES; index++) {
            manyDeletes.add(String.format("lang/en_us/chapters/%016X.json5", index));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> PeriodQuestFiles.validate(payload(goldenFiles(), manyDeletes)));
    }
}
