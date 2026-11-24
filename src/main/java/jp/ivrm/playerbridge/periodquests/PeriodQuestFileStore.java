package jp.ivrm.playerbridge.periodquests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies a validated period-quests payload to the FTB Quests
 * book directory (ivrm-web #1752 S3).
 *
 * <p>The swap is staged: every file is first written in full to
 * a sibling temporary file, and only once all bodies are safely
 * on disk does the swap rename them into place (an atomic move
 * within the same directory wherever the filesystem offers
 * one), followed by the payload's deletions. A failure while
 * staging changes nothing; a failure mid-commit leaves a mix of
 * complete old and new files — never a truncated one — and the
 * caller keeps the previous applied hash, so the next poll
 * re-applies the same payload and converges.</p>
 *
 * <p>Defense in depth: {@link #apply} re-runs
 * {@link PeriodQuestFiles#validate} and additionally requires
 * every resolved target to stay inside the book directory, so
 * no caller mistake can turn this store into a writer for the
 * permanent quest line or anything outside the book.</p>
 */
public final class PeriodQuestFileStore {
    static final String TEMP_SUFFIX = ".ivrm-tmp";

    private final Path questsDir;

    public PeriodQuestFileStore(Path questsDir) {
        this.questsDir = questsDir.toAbsolutePath().normalize();
    }

    public Path questsDir() {
        return questsDir;
    }

    public void apply(PeriodQuestsPayload payload) throws IOException {
        PeriodQuestFiles.validate(payload);

        List<Map.Entry<Path, Path>> staged = new ArrayList<>();
        try {
            for (Map.Entry<String, String> entry : payload.files().entrySet()) {
                Path target = resolveInside(entry.getKey());
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
                Files.writeString(
                        temp,
                        entry.getValue(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                staged.add(Map.entry(temp, target));
            }
        } catch (IOException exception) {
            deleteQuietly(staged);
            throw exception;
        }

        for (Map.Entry<Path, Path> rename : staged) {
            moveAtomically(rename.getKey(), rename.getValue());
        }

        IOException deleteFailure = null;
        for (String relative : payload.deleteFiles()) {
            try {
                Files.deleteIfExists(resolveInside(relative));
            } catch (IOException exception) {
                if (deleteFailure == null) {
                    deleteFailure = exception;
                }
            }
        }
        if (deleteFailure != null) {
            throw deleteFailure;
        }
    }

    private Path resolveInside(String relative) throws IOException {
        Path target = questsDir.resolve(relative).normalize();
        if (!target.startsWith(questsDir)) {
            throw new IOException("period quest path escapes the book directory");
        }
        return target;
    }

    private static void moveAtomically(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(List<Map.Entry<Path, Path>> staged) {
        for (Map.Entry<Path, Path> entry : staged) {
            try {
                Files.deleteIfExists(entry.getKey());
            } catch (IOException ignored) {
                // Best effort: a stale temp file is overwritten by
                // the next apply and never matches a quest file name.
            }
        }
    }
}
