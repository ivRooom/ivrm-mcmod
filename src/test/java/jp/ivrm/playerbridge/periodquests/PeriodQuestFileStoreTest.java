package jp.ivrm.playerbridge.periodquests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PeriodQuestFileStoreTest {
    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    private static final String HASH = "0".repeat(64);

    @TempDir
    Path tempDir;

    private static PeriodQuestsPayload payload(Map<String, String> files, List<String> deletes) {
        return new PeriodQuestsPayload(NOW, HASH, files, deletes);
    }

    @Test
    void applyWritesDeletesAndLeavesThePermanentLineUntouched() throws Exception {
        Path questsDir = tempDir.resolve("quests");
        Path permanent = questsDir.resolve("chapters").resolve("welcome.json5");
        Path staleLang = questsDir.resolve("lang").resolve("ja_jp").resolve("chapters")
                .resolve("AAAAAAAAAAAAAAAA.json5");
        Files.createDirectories(permanent.getParent());
        Files.createDirectories(staleLang.getParent());
        Files.writeString(permanent, "permanent");
        Files.writeString(staleLang, "stale");

        PeriodQuestFileStore store = new PeriodQuestFileStore(questsDir);
        store.apply(payload(
                Map.of(
                        "chapters/ivrm_period_daily.json5", "daily-v1",
                        "lang/ja_jp/chapters/0123456789ABCDEF.json5", "lang-v1"),
                List.of("lang/ja_jp/chapters/AAAAAAAAAAAAAAAA.json5")));

        assertEquals("daily-v1", Files.readString(questsDir.resolve("chapters/ivrm_period_daily.json5")));
        assertEquals(
                "lang-v1",
                Files.readString(questsDir.resolve("lang/ja_jp/chapters/0123456789ABCDEF.json5")));
        assertFalse(Files.exists(staleLang));
        assertEquals("permanent", Files.readString(permanent));
        assertNoTempFiles(questsDir);

        // The next period overwrites the fixed chapter file and
        // removes the previous period's lang file.
        store.apply(payload(
                Map.of(
                        "chapters/ivrm_period_daily.json5", "daily-v2",
                        "lang/ja_jp/chapters/FEDCBA9876543210.json5", "lang-v2"),
                List.of("lang/ja_jp/chapters/0123456789ABCDEF.json5")));

        assertEquals("daily-v2", Files.readString(questsDir.resolve("chapters/ivrm_period_daily.json5")));
        assertFalse(Files.exists(questsDir.resolve("lang/ja_jp/chapters/0123456789ABCDEF.json5")));
        assertEquals(
                "lang-v2",
                Files.readString(questsDir.resolve("lang/ja_jp/chapters/FEDCBA9876543210.json5")));
        assertEquals("permanent", Files.readString(permanent));
        assertNoTempFiles(questsDir);
    }

    @Test
    void applyCreatesMissingDirectories() throws Exception {
        Path questsDir = tempDir.resolve("fresh").resolve("quests");
        PeriodQuestFileStore store = new PeriodQuestFileStore(questsDir);
        store.apply(payload(Map.of("chapters/ivrm_period_weekly.json5", "weekly"), List.of()));
        assertTrue(Files.exists(questsDir.resolve("chapters/ivrm_period_weekly.json5")));
    }

    @Test
    void rejectedPayloadChangesNothing() throws Exception {
        Path questsDir = tempDir.resolve("quests");
        Path permanent = questsDir.resolve("chapters").resolve("welcome.json5");
        Files.createDirectories(permanent.getParent());
        Files.writeString(permanent, "permanent");

        PeriodQuestFileStore store = new PeriodQuestFileStore(questsDir);
        // A deletion aimed at the permanent line fails the guard
        // before anything is staged or written.
        PeriodQuestsPayload hostile = payload(
                Map.of("chapters/ivrm_period_daily.json5", "daily"),
                List.of("chapters/welcome.json5"));
        assertThrows(IllegalArgumentException.class, () -> store.apply(hostile));
        assertEquals("permanent", Files.readString(permanent));
        assertFalse(Files.exists(questsDir.resolve("chapters/ivrm_period_daily.json5")));
        assertNoTempFiles(questsDir);
    }

    private static void assertNoTempFiles(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            assertTrue(
                    walk.noneMatch(path -> path.getFileName().toString().endsWith(PeriodQuestFileStore.TEMP_SUFFIX)),
                    "no staged temp files may remain");
        }
    }
}
