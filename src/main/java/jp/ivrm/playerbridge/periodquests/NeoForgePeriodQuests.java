package jp.ivrm.playerbridge.periodquests;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * NeoForge adapter for the period quest sync (ivrm-web #1752
 * S3).
 *
 * <p>The adapter is deliberately thin: it feeds the runtime
 * the connected player count (sampled from the server tick,
 * like the Presentation adapter), pulls the next poll forward
 * on login, and executes the book reload the runtime asks for.
 * The reload is the {@code ftbquests reload} command — the same
 * operation the operators ran by hand over RCON when the
 * permanent line was distributed — executed on the server
 * thread with an operator-level command source, because FTB
 * Quests reads the chapter files from disk during it. The
 * runtime calls it from its poller thread, so the call hops
 * onto the server thread and waits (bounded) for the result;
 * the server thread itself never performs HTTP or file IO for
 * this module.</p>
 *
 * <p>This class references no FTB Quests types (the reload is a
 * command string), so it loads safely without the mod; the
 * bridge entry point still only registers it when FTB Quests is
 * present, because without the book there is nothing to sync.</p>
 */
public final class NeoForgePeriodQuests {
    private static final String RELOAD_COMMAND = "ftbquests reload";
    private static final int SAMPLE_TICKS = 20;
    private static final long RELOAD_TIMEOUT_SECONDS = 30L;

    private final PeriodQuestsRuntime runtime;
    private final Logger logger;
    private final AtomicInteger onlineCount = new AtomicInteger();
    private volatile MinecraftServer server;
    private int tickCounter;

    public NeoForgePeriodQuests(PeriodQuestsRuntime runtime, Logger logger) {
        this.runtime = runtime;
        this.logger = logger;
        runtime.setOnlinePlayersSupplier(onlineCount::get);
        runtime.setReloadSupplier(this::reloadQuestBook);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        server = event.getServer();
        tickCounter++;
        if (tickCounter < SAMPLE_TICKS) {
            return;
        }
        tickCounter = 0;
        onlineCount.set(event.getServer().getPlayerList().getPlayers().size());
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        runtime.requestRefresh();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        server = null;
        onlineCount.set(0);
        runtime.close();
    }

    /**
     * Runs {@code ftbquests reload} on the server thread and
     * reports whether the command succeeded. Called on the
     * runtime's poller thread; a missing server (not started
     * yet, or already stopped) is a plain false so the runtime
     * keeps the reload pending instead of failing the sync.
     */
    private boolean reloadQuestBook() {
        MinecraftServer current = server;
        if (current == null) {
            return false;
        }
        if (current.isSameThread()) {
            return executeReload(current);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            current.execute(() -> future.complete(executeReload(current)));
        } catch (RuntimeException exception) {
            return false;
        }
        try {
            return future.get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            return false;
        }
    }

    private boolean executeReload(MinecraftServer target) {
        try {
            // This Minecraft version's performPrefixedCommand
            // returns no result; success is observed through the
            // command result callback instead. If the callback
            // has not fired by the time the call returns (the
            // execution may complete on a later tick), this reads
            // as a failure and the runtime retries the reload on
            // the next poll — a duplicate reload is harmless.
            AtomicBoolean succeeded = new AtomicBoolean();
            CommandSourceStack source = target.createCommandSourceStack()
                    .withPermission(LevelBasedPermissionSet.OWNER)
                    .withSuppressedOutput()
                    .withCallback((success, result) -> {
                        if (success && result >= 1) {
                            succeeded.set(true);
                        }
                    });
            target.getCommands().performPrefixedCommand(source, RELOAD_COMMAND);
            if (!succeeded.get()) {
                logger.warn("Period quest book reload command did not report success");
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            logger.warn(
                    "Period quest book reload command failed: reasonType={}",
                    exception.getClass().getSimpleName());
            return false;
        }
    }
}
