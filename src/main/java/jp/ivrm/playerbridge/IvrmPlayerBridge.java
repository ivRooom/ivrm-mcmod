package jp.ivrm.playerbridge;

import com.mojang.logging.LogUtils;
import jp.ivrm.playerbridge.activity.ActivityConfig;
import jp.ivrm.playerbridge.activity.ActivityRuntime;
import jp.ivrm.playerbridge.activity.FtbQuestsActivityEvents;
import jp.ivrm.playerbridge.activity.NeoForgeActivityEvents;
import jp.ivrm.playerbridge.presentation.BoardPreferences;
import jp.ivrm.playerbridge.presentation.DeliveredEventsLedger;
import jp.ivrm.playerbridge.presentation.EffectsPreferences;
import jp.ivrm.playerbridge.presentation.NeoForgePresentation;
import jp.ivrm.playerbridge.presentation.PresentationCache;
import jp.ivrm.playerbridge.presentation.PresentationConfig;
import jp.ivrm.playerbridge.presentation.PresentationRuntime;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
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
        initializePresentation();
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
            registerFtbQuestsHook(runtime);
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

    /**
     * Presentation module (#1584 S3): snapshot polling, the
     * per-player sidebar and {@code /ivrm today} / {@code /ivrm board}.
     *
     * <p>Independent of the Activity sender's enable flag — it
     * reuses the sender's server identity (the API authorizes the
     * snapshot with the same per-server secret) but has its own
     * {@code IVRM_PRESENTATION_ENABLED} flag, default off. As with
     * the sender, any initialization failure only disables this
     * module and is logged by exception type, never by message.</p>
     */
    private static void initializePresentation() {
        try {
            java.nio.file.Path gameDir = FMLPaths.GAMEDIR.get();
            PresentationConfig config = PresentationConfig.load(gameDir);
            if (!config.enabled()) {
                LOGGER.info("IVRM Presentation module is disabled: {}", config.summary());
                return;
            }
            ActivityConfig identity = ActivityConfig.load(gameDir);
            if (identity.serverId().isBlank() || identity.secret().isBlank()) {
                throw new IllegalArgumentException(
                        "IVRM_ACTIVITY_SERVER_ID and IVRM_ACTIVITY_SERVER_SECRET are required"
                                + " when the presentation module is enabled");
            }

            PresentationCache cache = new PresentationCache();
            PresentationRuntime runtime = new PresentationRuntime(identity, config, cache, LOGGER);
            BoardPreferences preferences = new BoardPreferences(config.boardPreferencesPath(), LOGGER);
            EffectsPreferences effectsPreferences =
                    new EffectsPreferences(config.effectsPreferencesPath(), LOGGER);
            DeliveredEventsLedger deliveredLedger =
                    new DeliveredEventsLedger(config.deliveredLedgerPath(), LOGGER);
            NeoForge.EVENT_BUS.register(new NeoForgePresentation(
                    runtime, cache, preferences, effectsPreferences, deliveredLedger, identity, LOGGER));
            runtime.start();
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "IVRM Presentation module could not initialize and remains disabled: reasonType={}",
                    exception.getClass().getSimpleName());
        }
    }

    /**
     * FTB Quests completion hook (quest sync S2, ivrm-web #1608).
     * The listener class references FTB Quests API types, so it is
     * only touched when the mod is actually loaded; a linkage or
     * API problem must degrade to "no FTB events" and never take
     * the canonical sender (or the server) down, hence Throwable.
     * Only the exception type is logged, as above.
     */
    private static void registerFtbQuestsHook(ActivityRuntime runtime) {
        if (!ModList.get().isLoaded("ftbquests")) {
            LOGGER.info("FTB Quests not loaded; quest completion events are disabled");
            return;
        }
        try {
            FtbQuestsActivityEvents.register(runtime);
            LOGGER.info("FTB Quests completion hook registered");
        } catch (Throwable throwable) {
            LOGGER.error(
                    "FTB Quests completion hook could not register and remains disabled: reasonType={}",
                    throwable.getClass().getSimpleName());
        }
    }
}
