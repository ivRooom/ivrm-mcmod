package jp.ivrm.playerbridge.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Thin NeoForge adapter around the loader-independent Activity runtime. */
public final class NeoForgeActivityEvents {
    private static final int ACTIVITY_SAMPLE_TICKS = 20;
    private static final double POSITION_EPSILON = 0.0001D;
    private static final float ROTATION_EPSILON = 0.01F;

    private final ActivityRuntime runtime;
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private int tickCounter;

    public NeoForgeActivityEvents(ActivityRuntime runtime) {
        this.runtime = runtime;
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !runtime.enabled()) {
            return;
        }
        Instant now = runtime.now();
        players.put(player.getUUID(), PlayerState.from(player, now));
        emit(player, "player.login", Map.of());
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !runtime.enabled()) {
            return;
        }
        emit(player, "player.logout", Map.of());
        players.remove(player.getUUID());
    }

    /**
     * NeoForge exposes XP changes as a stable player event. It is represented by
     * the generic canonical stat_delta shape instead of inventing a new event
     * type. LOWEST priority observes changes after normal-priority modifiers.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onXpChange(PlayerXpEvent.XpChange event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !runtime.enabled()
                || event.isCanceled()
                || event.getAmount() == 0) {
            return;
        }
        emit(player, "player.stat_delta", Map.of(
                "stat", "minecraft:experience_points",
                "delta", Integer.toString(event.getAmount())));
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!runtime.enabled()) {
            return;
        }
        tickCounter++;
        if (tickCounter < ACTIVITY_SAMPLE_TICKS) {
            return;
        }
        tickCounter = 0;

        Instant now = runtime.now();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            PlayerState state = players.computeIfAbsent(player.getUUID(), ignored -> PlayerState.from(player, now));
            boolean active = state.observeMovement(player, now);
            boolean nextAfk = !active
                    && Duration.between(state.lastActiveAt, now).getSeconds()
                            >= runtime.config().afkThresholdSeconds();

            if (nextAfk != state.afk) {
                state.afk = nextAfk;
                emit(player, "player.afk_changed", Map.of("afk", Boolean.toString(nextAfk)));
            }

            if (Duration.between(state.lastHeartbeatAt, now).getSeconds()
                    >= runtime.config().heartbeatSeconds()) {
                state.lastHeartbeatAt = now;
                emit(player, "player.heartbeat", Map.of("afk", Boolean.toString(state.afk)));
            }
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        // Keep the dispatcher alive through the final PlayerLoggedOutEvent events.
        // close() performs one last persistence/drain pass before termination.
        runtime.flushAsync();
        runtime.close();
        players.clear();
    }

    private void emit(ServerPlayer player, String type, Map<String, String> attributes) {
        runtime.emit(type, player.getUUID(), player.getGameProfile().name(), attributes);
    }

    private static final class PlayerState {
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        private Instant lastActiveAt;
        private Instant lastHeartbeatAt;
        private boolean afk;

        static PlayerState from(ServerPlayer player, Instant now) {
            PlayerState state = new PlayerState();
            state.x = player.getX();
            state.y = player.getY();
            state.z = player.getZ();
            state.yaw = player.getYRot();
            state.pitch = player.getXRot();
            state.lastActiveAt = now;
            state.lastHeartbeatAt = now;
            return state;
        }

        boolean observeMovement(ServerPlayer player, Instant now) {
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
                return true;
            }
            return false;
        }
    }
}
