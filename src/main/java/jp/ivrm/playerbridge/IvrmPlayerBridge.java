package jp.ivrm.playerbridge;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
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
    }
}
