package jp.ivrm.playerbridge.presentation;

import com.mojang.brigadier.context.CommandContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jp.ivrm.playerbridge.activity.ActivityConfig;
import jp.ivrm.playerbridge.activity.WorldTime;
import jp.ivrm.playerbridge.presentation.EffectText.RenderPlan;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerEntry;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerRef;
import jp.ivrm.playerbridge.presentation.SidebarLines.LocalInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * {@code /ivrm board on|off} toggles the sidebar persistently;
 * {@code /ivrm effects on|off} toggles the S4 achievement
 * effects (Title / sound / particles) persistently — the chat
 * completion record still arrives while effects are off.</p>
 *
 * <p>S4 effects: the runtime polls the Presentation Outbox;
 * this adapter renders each event once for its player only
 * (ActionBar for a normal completion, Title + sound + particles
 * for an all-clear or an unlock), spaced at least 2 seconds per
 * player, then records it in the local delivered ledger and
 * queues the ack. Events that completed while the player was
 * offline are delivered as login catch-up: achievements get one
 * simplified Title, quest completions fold into chat only.
 * Login bonuses (#1712) are graded by their JST stat date
 * instead: today's bonus always gets its ActionBar + sound +
 * particles, a previous day's bonus is chat only.</p>
 */
public final class NeoForgePresentation {
    static final String OBJECTIVE_NAME = "ivrm_sidebar";
    /** Login-bonus stat dates are JST dates (ivrm-web #1712). */
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final int RENDER_SAMPLE_TICKS = 20;
    private static final Duration EFFECT_SPACING = Duration.ofSeconds(2);
    private static final double POSITION_EPSILON = 0.0001D;
    private static final float ROTATION_EPSILON = 0.01F;

    private final PresentationRuntime runtime;
    private final PresentationCache cache;
    private final BoardPreferences preferences;
    private final EffectsPreferences effectsPreferences;
    private final DeliveredEventsLedger deliveredLedger;
    private final ActivityConfig identity;
    private final Logger logger;
    private final Objective objective;
    private final Map<UUID, RenderState> players = new HashMap<>();
    private final Map<UUID, List<PresentationEvent>> heldEvents = new HashMap<>();
    private final Set<String> seenEventIds = new HashSet<>();
    private volatile List<PlayerRef> onlineRefs = List.of();
    private int tickCounter;

    public NeoForgePresentation(
            PresentationRuntime runtime,
            PresentationCache cache,
            BoardPreferences preferences,
            EffectsPreferences effectsPreferences,
            DeliveredEventsLedger deliveredLedger,
            ActivityConfig identity,
            Logger logger) {
        this.runtime = runtime;
        this.cache = cache;
        this.preferences = preferences;
        this.effectsPreferences = effectsPreferences;
        this.deliveredLedger = deliveredLedger;
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
                                .then(Commands.literal("off").executes(context -> runBoard(context, false))))
                        .then(Commands.literal("effects")
                                .then(Commands.literal("on").executes(context -> runEffects(context, true)))
                                .then(Commands.literal("off").executes(context -> runEffects(context, false)))));
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
        // Held (not yet rendered) events stay in heldEvents: if
        // the player returns, they are delivered as catch-up.
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

        processEvents(online, now);
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        runtime.close();
        players.clear();
        heldEvents.clear();
        seenEventIds.clear();
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

    private int runEffects(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("[IVRM] このコマンドはプレイヤーだけが使えます"));
            return 0;
        }
        effectsPreferences.setEffectsEnabled(player.getUUID(), enabled);
        source.sendSuccess(
                () -> Component.literal(enabled
                        ? "[IVRM] 達成演出を表示します"
                        : "[IVRM] 達成演出を止めました（完了のチャット記録は届きます。/ivrm effects on で再開できます）"),
                false);
        return 1;
    }

    // ------------------------------------------------------------------
    // S4: outbox effect delivery
    // ------------------------------------------------------------------

    /**
     * Moves freshly polled events into per-player queues and
     * renders at most one queued effect per player per sampling
     * tick, spaced by {@link #EFFECT_SPACING}. Runs on the server
     * thread; all HTTP stays in the runtime.
     */
    private void processEvents(List<ServerPlayer> online, Instant now) {
        for (PresentationEvent event : runtime.drainEvents()) {
            if (!seenEventIds.add(event.eventId())) {
                continue;
            }
            if (deliveredLedger.isHandled(event.eventId())) {
                // Rendered before but the ack never landed; ack
                // again so the API stops returning it.
                runtime.queueAcks(List.of(event.eventId()));
                continue;
            }
            if (!event.expiresAt().isAfter(now)) {
                // The presentation window closed between the poll
                // and this tick. Never render expired events; the
                // API sweeps the row to expired on its side.
                continue;
            }
            heldEvents.computeIfAbsent(event.uuid(), ignored -> new ArrayList<>()).add(event);
        }

        Map<UUID, ServerPlayer> onlineByUuid = new HashMap<>();
        for (ServerPlayer player : online) {
            onlineByUuid.put(player.getUUID(), player);
        }
        // Drop held events whose window closed while waiting.
        heldEvents.values().forEach(list -> list.removeIf(event -> !event.expiresAt().isAfter(now)));

        for (ServerPlayer player : online) {
            RenderState state = players.get(player.getUUID());
            if (state == null) {
                continue;
            }
            List<PresentationEvent> held = heldEvents.remove(player.getUUID());
            if (held != null) {
                boolean effectsOn = runtime.config().effectsEnabled()
                        && effectsPreferences.effectsEnabled(player.getUUID());
                for (PresentationEvent event : held) {
                    boolean catchUp = event.createdAt().isBefore(state.loggedInAt);
                    RenderPlan plan;
                    if (event.isLoginBonus()) {
                        // Graded by the bonus's JST stat date, not
                        // by the login moment (#1712).
                        plan = EffectText.planLoginBonus(
                                event, effectsOn, LocalDate.ofInstant(now, JST));
                    } else if (catchUp) {
                        plan = EffectText.planCatchUp(event, effectsOn);
                    } else {
                        plan = EffectText.planLive(event, effectsOn);
                    }
                    state.effectQueue.addLast(new QueuedEffect(event.eventId(), plan));
                }
            }
            QueuedEffect next = state.effectQueue.peekFirst();
            if (next != null && !now.isBefore(state.nextEffectAt)) {
                state.effectQueue.pollFirst();
                executePlan(player, next.plan());
                deliveredLedger.markHandled(next.eventId(), now);
                runtime.queueAcks(List.of(next.eventId()));
                state.nextEffectAt = now.plus(EFFECT_SPACING);
            }
        }
    }

    private void executePlan(ServerPlayer player, RenderPlan plan) {
        try {
            for (String line : plan.chatLines()) {
                player.sendSystemMessage(Component.literal(line));
            }
            switch (plan.presentation()) {
                case ACTION_BAR -> {
                    if (plan.actionBarText() != null) {
                        player.sendSystemMessage(Component.literal(plan.actionBarText()), true);
                    }
                }
                case TITLE -> {
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
                    player.connection.send(new ClientboundSetSubtitleTextPacket(
                            Component.literal(plan.subtitleText() == null ? "" : plan.subtitleText())));
                    player.connection.send(new ClientboundSetTitleTextPacket(
                            Component.literal(plan.titleText() == null ? "" : plan.titleText())));
                }
                case CHAT_ONLY -> {
                    // Chat lines above are the whole delivery.
                }
            }
            playSound(player, plan.sound());
            spawnParticles(player, plan.particles());
        } catch (RuntimeException exception) {
            // A disconnect racing the tick must not break delivery
            // for everyone else; the event is still acked so a
            // reconnect does not replay a stale effect.
            logger.warn(
                    "IVRM effect render failed for a player: reasonType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void playSound(ServerPlayer player, EffectText.SoundProfile profile) {
        SoundEvent sound = switch (profile) {
            case NONE -> null;
            case SMALL -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case LEVELUP, LOGIN_BONUS -> SoundEvents.PLAYER_LEVELUP;
            case CHALLENGE -> SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
        };
        if (sound == null) {
            return;
        }
        // The login-bonus jingle plays softer than a level-up
        // fanfare (#1712); every other profile is full volume.
        float volume = profile == EffectText.SoundProfile.LOGIN_BONUS ? 0.7F : 1.0F;
        player.connection.send(new ClientboundSoundPacket(
                Holder.direct(sound),
                SoundSource.MASTER,
                player.getX(),
                player.getY(),
                player.getZ(),
                volume,
                1.0F,
                0L));
    }

    private void spawnParticles(ServerPlayer player, EffectText.ParticleProfile profile) {
        ParticleOptions particle;
        int count;
        switch (profile) {
            case NONE -> {
                return;
            }
            case SMALL -> {
                particle = ParticleTypes.HAPPY_VILLAGER;
                count = 8;
            }
            case LOGIN_BONUS -> {
                particle = ParticleTypes.HAPPY_VILLAGER;
                count = 12;
            }
            case FIREWORK -> {
                particle = ParticleTypes.FIREWORK;
                count = 16;
            }
            default -> {
                return;
            }
        }
        player.connection.send(new ClientboundLevelParticlesPacket(
                particle,
                false,
                true,
                player.getX(),
                player.getY() + 1.0D,
                player.getZ(),
                0.5F,
                0.5F,
                0.5F,
                0.02F,
                count));
    }

    private record QueuedEffect(String eventId, RenderPlan plan) {
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
        private Instant loggedInAt;
        private final Deque<QueuedEffect> effectQueue = new ArrayDeque<>();
        private Instant nextEffectAt = Instant.EPOCH;

        static RenderState from(ServerPlayer player, Instant now) {
            RenderState state = new RenderState();
            state.x = player.getX();
            state.y = player.getY();
            state.z = player.getZ();
            state.yaw = player.getYRot();
            state.pitch = player.getXRot();
            state.lastActiveAt = now;
            state.loggedInAt = now;
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
