package jp.ivrm.playerbridge.periodquests;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Pure guards and hashing for the period-quests payload
 * (ivrm-web #1752 S3). This is the safety core of the sync:
 * nothing reaches the FTB Quests book directory unless it
 * passes {@link #validate(PeriodQuestsPayload)} and its
 * recomputed hash matches the payload's declared hash.
 *
 * <p>Write guard: the payload may only write the three fixed
 * period chapter files and the per-period lang files
 * ({@code lang/<ja_jp|en_us>/chapters/<16-digit uppercase hex
 * chapter id>.json5}) — exactly the paths the S2 generator can
 * emit. Delete guard: strictly narrower — only lang files may
 * ever be deleted; chapter files have fixed names and are
 * overwritten, never removed. Any other path (a permanent-line
 * chapter, {@code chapter_groups.json5}, a traversal attempt,
 * anything unexpected the API might emit after a future change)
 * fails validation as a whole, so a bad payload changes nothing
 * on disk.</p>
 *
 * <p>The hash replicates the generator's canonical form:
 * SHA-256 over the file names and bodies (names sorted) plus
 * the sorted deletion list, joined as the API joins them. The
 * bridge therefore verifies the file set end-to-end instead of
 * trusting the declared hash.</p>
 */
public final class PeriodQuestFiles {
    /** The only chapter files the sync may write: fixed names, one per period. */
    public static final Set<String> CHAPTER_FILES = Set.of(
            "chapters/ivrm_period_daily.json5",
            "chapters/ivrm_period_weekly.json5",
            "chapters/ivrm_period_monthly.json5");

    private static final Pattern LANG_FILE =
            Pattern.compile("lang/(ja_jp|en_us)/chapters/[0-9A-F]{16}\\.json5");

    // Generous caps that only a broken or hostile payload can
    // trip: the real payload is 9 small files and 6 deletions.
    static final int MAX_FILES = 64;
    static final int MAX_DELETE_FILES = 64;
    static final int MAX_FILE_CHARS = 2_000_000;

    private PeriodQuestFiles() {
    }

    /** True when the sync may write (create or overwrite) this payload-relative path. */
    public static boolean isAllowedWritePath(String path) {
        return path != null && (CHAPTER_FILES.contains(path) || LANG_FILE.matcher(path).matches());
    }

    /** True when the sync may delete this payload-relative path. Lang files only. */
    public static boolean isAllowedDeletePath(String path) {
        return path != null && LANG_FILE.matcher(path).matches();
    }

    /**
     * Validates a payload against the guards and the size caps.
     * Throws {@link IllegalArgumentException} on the first
     * violation; the caller must then apply nothing.
     */
    public static void validate(PeriodQuestsPayload payload) {
        Map<String, String> files = payload.files();
        List<String> deleteFiles = payload.deleteFiles();
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("too many files in period quests payload");
        }
        if (deleteFiles.size() > MAX_DELETE_FILES) {
            throw new IllegalArgumentException("too many deleteFiles in period quests payload");
        }
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (!isAllowedWritePath(entry.getKey())) {
                throw new IllegalArgumentException("payload writes a path outside the period chapter guard");
            }
            String body = entry.getValue();
            if (body == null || body.length() > MAX_FILE_CHARS) {
                throw new IllegalArgumentException("period quest file body is missing or oversized");
            }
        }
        Set<String> seenDeletes = new HashSet<>();
        for (String path : deleteFiles) {
            if (!isAllowedDeletePath(path)) {
                throw new IllegalArgumentException("payload deletes a path outside the period lang guard");
            }
            if (!seenDeletes.add(path)) {
                throw new IllegalArgumentException("duplicate deleteFiles entry in period quests payload");
            }
            if (files.containsKey(path)) {
                throw new IllegalArgumentException("path is both written and deleted in period quests payload");
            }
        }
    }

    /**
     * Recomputes the payload hash exactly as the S2 generator
     * defines it: for each file name in sorted order, the name
     * and its body; then the {@code --delete--} marker and the
     * sorted deletion list joined by newlines; all parts joined
     * by newlines and hashed as UTF-8.
     */
    public static String computeHash(Map<String, String> files, List<String> deleteFiles) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(files).entrySet()) {
            parts.add(entry.getKey());
            parts.add(entry.getValue());
        }
        parts.add("--delete--");
        List<String> sortedDeletes = new ArrayList<>(deleteFiles);
        Collections.sort(sortedDeletes);
        parts.add(String.join("\n", sortedDeletes));
        return sha256Hex(String.join("\n", parts));
    }

    /** True when the payload's declared hash names exactly its own file set. */
    public static boolean hashMatches(PeriodQuestsPayload payload) {
        return computeHash(payload.files(), payload.deleteFiles()).equals(payload.hash());
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                out.append(Character.forDigit((value >> 4) & 0x0f, 16));
                out.append(Character.forDigit(value & 0x0f, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
