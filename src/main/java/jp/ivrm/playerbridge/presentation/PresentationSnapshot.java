package jp.ivrm.playerbridge.presentation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable model of the Presentation Snapshot returned by
 * {@code POST /v1/minecraft/presentation/snapshot} (#1584 S2).
 *
 * <p>The snapshot is display-only: it mirrors the member Web read
 * models, so the bridge never derives progress itself. Sections
 * are nullable per player because the API fails soft per player
 * (a linked player whose derivation failed keeps
 * {@code linked: true} with null sections), and an unlinked
 * player only ever carries {@code linked: false}.</p>
 */
public final class PresentationSnapshot {
    private PresentationSnapshot() {
    }

    /** One connected player to include in a snapshot request. */
    public record PlayerRef(UUID uuid, String name) {
    }

    public record DailyItem(
            String questKey,
            String title,
            int progress,
            int target,
            boolean completed,
            int xpReward) {
    }

    public record Daily(String periodKey, int completed, int total, List<DailyItem> items) {
        public Daily {
            items = List.copyOf(items);
        }

        public List<DailyItem> incompleteItems() {
            return items.stream().filter(item -> !item.completed()).toList();
        }
    }

    public record AchievementNext(String code, String name, int progress, int target, String rank) {
    }

    public record Achievements(int unlocked, int total, List<AchievementNext> next) {
        public Achievements {
            next = List.copyOf(next);
        }
    }

    public record PlayerEntry(UUID uuid, boolean linked, Daily daily, Achievements achievements) {
    }

    public record Snapshot(
            Instant generatedAt,
            String serverRole,
            Instant dailyResetsAt,
            List<PlayerEntry> players) {
        public Snapshot {
            players = List.copyOf(players);
        }

        public Optional<PlayerEntry> player(UUID uuid) {
            return players.stream().filter(entry -> entry.uuid().equals(uuid)).findFirst();
        }
    }
}
