package jp.ivrm.playerbridge.activity;

import dev.ftb.mods.ftbquests.api.event.progress.ProgressEventData;
import dev.ftb.mods.ftbquests.api.event.progress.ProgressType;
import dev.ftb.mods.ftbquests.api.event.progress.QuestProgressEvent;
import dev.ftb.mods.ftbquests.api.neoforge.FTBQuestsEvent;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * FTB Quests adapter for {@code player.ftb_quest_completed}
 * (ivrm-web #1605 / #1608, quest sync S2).
 *
 * <p>This class references FTB Quests API types directly, so it
 * must only be loaded when the {@code ftbquests} mod is present.
 * {@link #register(ActivityRuntime)} is called from the bridge
 * entry point behind a {@code ModList.isLoaded("ftbquests")}
 * check; nothing else in the bridge references this class, so a
 * server without FTB Quests never loads it and the sender keeps
 * working with the canonical event types only.</p>
 *
 * <p>Only quest completions are reported (the event's
 * {@code ProgressType.COMPLETED}); quest starts and task/chapter
 * progress are not part of the sync contract. The event is
 * emitted through the same durable queue / HMAC sender as every
 * other activity event, and attribution follows the conservative
 * rule in {@link FtbQuestCompletion}: FTB Quests progress is
 * team-scoped, so a completion is attributed only when a single
 * team member is online.</p>
 */
public final class FtbQuestsActivityEvents {

    private final ActivityRuntime runtime;

    private FtbQuestsActivityEvents(ActivityRuntime runtime) {
        this.runtime = runtime;
    }

    /** Registers the listener on the NeoForge event bus. FTB Quests must be loaded. */
    public static void register(ActivityRuntime runtime) {
        NeoForge.EVENT_BUS.register(new FtbQuestsActivityEvents(runtime));
    }

    @SubscribeEvent
    public void onQuestProgress(FTBQuestsEvent.QuestProgress event) {
        if (!runtime.enabled()) {
            return;
        }
        QuestProgressEvent.Data data = event.getEventData();
        if (data == null || data.type() != ProgressType.COMPLETED) {
            return;
        }
        ProgressEventData<Quest> progress = data.progressData();
        if (progress == null || progress.object() == null) {
            return;
        }
        Quest quest = progress.object();

        List<ServerPlayer> onlineMembers = progress.onlineMembers().stream()
                .filter(player -> player != null)
                .toList();
        Optional<UUID> attributed = FtbQuestCompletion.attributeTo(
                onlineMembers.stream().map(ServerPlayer::getUUID).toList());
        if (attributed.isEmpty()) {
            // Nobody online (a forced/offline completion) or
            // several members online: the player who caused the
            // completion cannot be told apart, and the sync
            // contract attributes completions to that player
            // only, so the completion is not reported.
            return;
        }
        ServerPlayer player = onlineMembers.stream()
                .filter(candidate -> candidate.getUUID().equals(attributed.get()))
                .findFirst()
                .orElse(null);
        if (player == null) {
            return;
        }

        Chapter chapter = quest.getQuestChapter();
        String chapterCode = chapter == null ? null : chapter.getCodeString();
        runtime.emit(
                FtbQuestCompletion.EVENT_TYPE,
                player.getUUID(),
                player.getGameProfile().name(),
                FtbQuestCompletion.attributes(quest.getCodeString(), chapterCode));
    }
}
