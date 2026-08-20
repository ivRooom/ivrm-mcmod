package jp.ivrm.minecraft.permissioncontrol;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.TriState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(IvrmPermissionControl.MOD_ID)
public final class IvrmPermissionControl {
    public static final String MOD_ID = "ivrm_permission_control";
    private static final String PERMISSION_NAMESPACE = "ivrm";
    private static final Logger LOGGER = LoggerFactory.getLogger(IvrmPermissionControl.class);
    private static final long MESSAGE_COOLDOWN_MILLIS = 3_000L;
    private static final Component DENIED = Component.literal("この操作はメンバー承認後に利用できます。");
    private static final Map<UUID, Long> LAST_MESSAGE = new ConcurrentHashMap<>();

    public static final PermissionNode<Boolean> BUILD = permission("play.build");
    public static final PermissionNode<Boolean> CRAFT = permission("play.craft");
    public static final PermissionNode<Boolean> CONTAINER = permission("play.container");
    public static final PermissionNode<Boolean> INTERACT = permission("play.interact");
    public static final PermissionNode<Boolean> COMBAT = permission("play.combat");
    public static final PermissionNode<Boolean> PICKUP = permission("play.pickup");

    public IvrmPermissionControl() {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("IVRM Permission Controlを初期化しました");
    }

    private static PermissionNode<Boolean> permission(String node) {
        return new PermissionNode<>(PERMISSION_NAMESPACE, node, PermissionTypes.BOOLEAN,
                (player, playerUuid, context) -> false);
    }

    @SubscribeEvent
    public void registerPermissionNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(BUILD, CRAFT, CONTAINER, INTERACT, COMBAT, PICKUP);
    }

    @SubscribeEvent
    public void onBreak(BreakBlockEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && deny(player, BUILD)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && deny(player, BUILD)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        ServerPlayer player = serverPlayer(event.getEntity());
        if (player != null && deny(player, BUILD)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ServerPlayer player = serverPlayer(event.getEntity());
        if (player != null && deny(player, INTERACT)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ServerPlayer player = serverPlayer(event.getEntity());
        if (player != null && deny(player, INTERACT)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        ServerPlayer player = serverPlayer(event.getEntity());
        if (player != null && deny(player, INTERACT)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        ServerPlayer player = serverPlayer(event.getEntity());
        if (player != null && deny(player, INTERACT)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        ServerPlayer player = serverPlayer(event.getEntity());
        if (player != null && deny(player, COMBAT)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPickup(ItemEntityPickupEvent.Pre event) {
        ServerPlayer player = serverPlayer(event.getPlayer());
        if (player != null && deny(player, PICKUP)) {
            event.setCanPickup(TriState.FALSE);
        }
    }

    @SubscribeEvent
    public void onToss(ItemTossEvent event) {
        ServerPlayer player = serverPlayer(event.getPlayer());
        if (player == null || !deny(player, PICKUP)) {
            return;
        }

        ItemStack remaining = event.getEntity().getItem().copy();
        player.getInventory().add(remaining);
        restoreToCarriedSlot(player, remaining);
        player.containerMenu.broadcastChanges();

        if (remaining.isEmpty()) {
            event.setCanceled(true);
            return;
        }

        // Canceling ItemTossEvent after only a partial restore would delete the remainder.
        // Preserve data over enforcement in this exceptional case and let only the remainder enter the world.
        event.getEntity().setItem(remaining);
        LOGGER.error(
                "拒否したアイテムドロップを完全復元できなかったため残量のみワールドへ保持します: player={}, count={}",
                player.getGameProfile().name(), remaining.getCount());
    }

    private static void restoreToCarriedSlot(ServerPlayer player, ItemStack remaining) {
        if (remaining.isEmpty()) {
            return;
        }

        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            player.containerMenu.setCarried(remaining.copy());
            remaining.setCount(0);
            return;
        }

        if (!ItemStack.isSameItemSameComponents(carried, remaining)) {
            return;
        }

        int available = Math.max(0, carried.getMaxStackSize() - carried.getCount());
        int restored = Math.min(available, remaining.getCount());
        if (restored > 0) {
            carried.grow(restored);
            remaining.shrink(restored);
            player.containerMenu.setCarried(carried);
        }
    }

    private static ServerPlayer serverPlayer(Player player) {
        return player instanceof ServerPlayer serverPlayer ? serverPlayer : null;
    }

    private static boolean deny(ServerPlayer player, PermissionNode<Boolean> node) {
        boolean allowed;
        try {
            allowed = PermissionAPI.getPermission(player, node);
        } catch (RuntimeException exception) {
            LOGGER.error("権限判定に失敗したため安全側で拒否します: player={}, node={}",
                    player.getGameProfile().name(), node.getNodeName(), exception);
            allowed = false;
        }

        if (!allowed) {
            notifyDenied(player);
        }
        return !allowed;
    }

    private static void notifyDenied(ServerPlayer player) {
        long now = System.currentTimeMillis();
        UUID playerId = player.getUUID();
        Long previous = LAST_MESSAGE.get(playerId);
        if (previous == null || now - previous >= MESSAGE_COOLDOWN_MILLIS) {
            LAST_MESSAGE.put(playerId, now);
            player.sendSystemMessage(DENIED);
        }
    }
}
