package jp.ivrm.playerbridge.presentation;

import com.mojang.brigadier.context.CommandContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jp.ivrm.playerbridge.activity.ActivityConfig;
import jp.ivrm.playerbridge.activity.WorldTime;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerEntry;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerRef;
import jp.ivrm.playerbridge.presentation.SidebarLines.LocalInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.slf4j.Logger;

/**
 * NeoForge adapter for the Presentation module (#1584 S3).
 *
 * <p>Renders each player's own sidebar with vanilla scoreboard
 * packets sent only to that player: the objective is a detached
 * client-side one (never registered in the server scoreboard),
 * so no other player ever receives someone else's progress, and
 * TAB's scoreboard stays disabled and untouched. Packet sends
 * happen on the server thread from the tick handler, and only
 * for lines that actually changed. HTTP stays on the poller
 * thread inside {@link PresentationRuntime}.</p>
 *
 * <p>Commands: {@code /ivrm today} prints the full daily /
 * achievement summary to the player's own chat;
 * {@code /ivrm board on|off} toggles the sidebar persistently.
 * Achievement effects are S4 and intentionally not here.</p>
 */
public final class NeoForgePresentation {
    static final String OBJECTIVE_NAME = "ivrm_sidebar";
    private static final int RENDER_SAMPLE_TICKS = 20;
    private static final double POSITION_EPSILON = 0.0001D;
    private static final float ROTATION_EPSILON = 0.01F;

    private final PresentationRuntime runtime;
    private final PresentationCache cache;
    private final BoardPreferences preferences;
    private final ActivityConfig identity;
    private final Logger logger;
    private final Objective objective;
    private final Map<UUID, RenderState> players = new HashMap<>();
    private volatile List<PlayerRef> onlineRefs = List.of();
    private int tickCounter;

    public NeoForgePresentation(
            PresentationRuntime runtime,
            PresentationCache cache,
            BoardPreferences preferences,
            ActivityConfig identity,
            Logger logger) {
        this.runtime = runtime;
        this.cache = cache;
        this.preferences = preferences;
        this.identity = identity;
        this.logger = logger;
        this.objective = new Objective(
                new Scoreboard(),
                OBJECTIVE_NAME,
                ObjectiveCriteria.DUMMY,
                Component.literal("いゔる。ーむ"),
                ObjectiveCriteria.RenderType.INTEGER,
                false,
                BlankFormat.INSTANCE);
        runtime.setPlayersSupplier(() -> onlineRefs);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("ivrm")
                        .then(Commands.literal("today").executes(this::runToday))
                        .then(Commands.literal("board")
                                .then(Commands.literal("on").executes(context -> runBoard(context, true)))
                                .then(Commands.literal("off").executes(context -> runBoard(context, false)))));
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        players.put(player.getUUID(), RenderState.from(player, runtime.now()));
        runtime.requestRefresh();
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        RenderState state = players.remove(player.getUUID());
        if (state != null) {
            removeSidebar(player, state);
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        if (tickCounter < RENDER_SAMPLE_TICKS) {
            return;
        }
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        updateOnlineRefs(online);

        Instant now = runtime.now();
        int active = 0;
        for (ServerPlayer player : online) {
            RenderState state = players.computeIfAbsent(
                    player.getUUID(), ignored -> RenderState.from(player, now));
            if (!state.observeAfk(player, now, identity.afkThresholdSeconds())) {
                active++;
            }
        }

        boolean usable = cache.usable(now, runtime.config().staleAfter());
        Optional<Instant> resetsAt = cache.dailyResetsAt();
        for (ServerPlayer player : online) {
            RenderState state = players.get(player.getUUID());
            if (state == null) {
                continue;
            }
            if (!preferences.boardEnabled(player.getUUID())) {
                removeSidebar(player, state);
                continue;
            }
            LocalInfo local = new LocalInfo(
                    online.size(),
                    server.getMaxPlayers(),
                    active,
                    WorldTime.worldDay(player.level().getGameTime()));
            PlayerEntry entry = cache.player(player.getUUID()).orElse(null);
            List<String> lines = SidebarLines.renderSidebar(
                    local, entry, usable, resetsAt.orElse(null), now);
            applyLines(player, state, lines);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        runtime.close();
        players.clear();
    }

    private void updateOnlineRefs(List<ServerPlayer> online) {
        List<PlayerRef> refs = new ArrayList<>(online.size());
        for (ServerPlayer player : online) {
            refs.add(new PlayerRef(player.getUUID(), player.getGameProfile().name()));
        }
        boolean membershipChanged = refs.size() != onlineRefs.size()
                || !refs.stream().map(PlayerRef::uuid).toList()
                        .containsAll(onlineRefs.stream().map(PlayerRef::uuid).toList());
        onlineRefs = List.copyOf(refs);
        if (membershipChanged) {
            runtime.requestRefresh();
        }
    }

    private int runToday(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("[IVRM] このコマンドはプレイヤーだけが使えます"));
            return 0;
        }
        MinecraftServer server = source.getServer();
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        Instant now = runtime.now();
        int active = 0;
        for (ServerPlayer other : online) {
            RenderState state = players.get(other.getUUID());
            if (state == null || !state.afk) {
                active++;
            }
        }
        LocalInfo local = new LocalInfo(
                online.size(),
                server.getMaxPlayers(),
                active,
                WorldTime.worldDay(player.level().getGameTime()));
        boolean usable = cache.usable(now, runtime.config().staleAfter());
        PlayerEntry entry = cache.player(player.getUUID()).orElse(null);
        for (String line : SidebarLines.renderToday(local, entry, usable)) {
            player.sendSystemMessage(Component.literal(line));
        }
        return 1;
    }

    private int runBoard(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("[IVRM] このコマンドはプレイヤーだけが使えます"));
            return 0;
        }
        preferences.setBoardEnabled(player.getUUID(), enabled);
        if (!enabled) {
            RenderState state = players.get(player.getUUID());
            if (state != null) {
                removeSidebar(player, state);
            }
        } else {
            runtime.requestRefresh();
        }
        source.sendSuccess(
                () -> Component.literal(enabled
                        ? "[IVRM] サイドバーを表示します"
                        : "[IVRM] サイドバーを非表示にしました（/ivrm board on で再表示できます）"),
                false);
        return 1;
    }

    private void applyLines(ServerPlayer player, RenderState state, List<String> lines) {
        if (state.objectiveShown && lines.equals(state.lastLines)) {
            return;
        }
        try {
            if (!state.objectiveShown) {
                player.connection.send(
                        new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
                player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
                state.objectiveShown = true;
                state.lastLines = List.of();
            }
            List<String> old = state.lastLines;
            boolean lengthChanged = old.size() != lines.size();
            int max = Math.max(old.size(), lines.size());
            for (int index = 0; index < max; index++) {
                String owner = "ivrm_" + index;
                if (index >= lines.size()) {
                    player.connection.send(new ClientboundResetScorePacket(owner, OBJECTIVE_NAME));
                } else if (lengthChanged || index >= old.size() || !lines.get(index).equals(old.get(index))) {
                    player.connection.send(new ClientboundSetScorePacket(
                            owner,
                            OBJECTIVE_NAME,
                            lines.size() - index,
                            Optional.of(Component.literal(lines.get(index))),
                            Optional.of(BlankFormat.INSTANCE)));
                }
            }
            state.lastLines = lines;
        } catch (RuntimeException exception) {
            // A disconnect racing the tick must not break rendering
            // for everyone else; the next login starts fresh.
            logger.warn(
                    "IVRM sidebar update failed for a player: reasonType={}",
                    exception.getClass().getSimpleName());
            state.objectiveShown = false;
            state.lastLines = List.of();
        }
    }

    private void removeSidebar(ServerPlayer player, RenderState state) {
        if (!state.objectiveShown) {
            return;
        }
        try {
            player.connection.send(
                    new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
        } catch (RuntimeException exception) {
            logger.warn(
                    "IVRM sidebar removal failed for a player: reasonType={}",
                    exception.getClass().getSimpleName());
        }
        state.objectiveShown = false;
        state.lastLines = List.of();
    }

    /**
     * Per-player rendering and AFK sampling state. AFK sampling
     * mirrors the activity adapter's movement watch (same
     * epsilons and threshold) but is independent of it, so the
     * presentation module works even while the activity sender
     * is disabled.
     */
    private static final class RenderState {
        private boolean objectiveShown;
        private List<String> lastLines = List.of();
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        private Instant lastActiveAt;
        private boolean afk;

        static RenderState from(ServerPlayer player, Instant now) {
            RenderState state = new RenderState();
            state.x = player.getX();
            state.y = player.getY();
            state.z = player.getZ();
            state.yaw = player.getYRot();
            state.pitch = player.getXRot();
            state.lastActiveAt = now;
            return state;
        }

        /** Samples movement and returns the current AFK state. */
        boolean observeAfk(ServerPlayer player, Instant now, int afkThresholdSeconds) {
            double dx = player.getX() - x;
            double dy = player.getY() - y;
            double dz = player.getZ() - z;
            boolean moved = dx * dx + dy * dy + dz * dz > POSITION_EPSILON;
            boolean looked = Math.abs(player.getYRot() - yaw) > ROTATION_EPSILON
                    || Math.abs(player.getXRot() - pitch) > ROTATION_EPSILON;
            if (moved || looked) {
                x = player.getX();
                y = player.getY();
                z = player.getZ();
                yaw = player.getYRot();
                pitch = player.getXRot();
                lastActiveAt = now;
                afk = false;
            } else if (Duration.between(lastActiveAt, now).getSeconds() >= afkThresholdSeconds) {
                afk = true;
            }
            return afk;
        }
    }
}
