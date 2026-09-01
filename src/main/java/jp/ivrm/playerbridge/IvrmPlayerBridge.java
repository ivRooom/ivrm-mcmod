package jp.ivrm.playerbridge;

import com.mojang.logging.LogUtils;
import jp.ivrm.playerbridge.activity.ActivityConfig;
import jp.ivrm.playerbridge.activity.ActivityRuntime;
import jp.ivrm.playerbridge.activity.NeoForgeActivityEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * IVRM Player Bridge entry point.
 *
 * <p>The first release is intentionally dedicated-server only. Client-side
 * installation is not required and no client classes may be referenced from
 * this package.</p>
 */
@Mod(value = IvrmPlayerBridge.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class IvrmPlayerBridge {
    public static final String MOD_ID = "ivrm_player_bridge";
    public static final Logger LOGGER = LogUtils.getLogger();

    public IvrmPlayerBridge(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("IVRM Player Bridge initialized (server-only foundation)");
        initializeActivitySender();
    }

    private static void initializeActivitySender() {
        try {
            ActivityConfig config = ActivityConfig.load(FMLPaths.GAMEDIR.get());
            if (!config.enabled()) {
                LOGGER.info("IVRM Activity sender is disabled: {}", config.summary());
                return;
            }

            ActivityRuntime runtime = new ActivityRuntime(config, LOGGER);
            NeoForge.EVENT_BUS.register(new NeoForgeActivityEvents(runtime));
            runtime.start();
        } catch (RuntimeException exception) {
            // Activity reporting must never prevent the Minecraft server from starting.
            // Never log the exception message here: configuration parsing failures can
            // contain operator-supplied values and therefore potentially credentials.
            LOGGER.error(
                    "IVRM Activity sender could not initialize and remains disabled: reasonType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
