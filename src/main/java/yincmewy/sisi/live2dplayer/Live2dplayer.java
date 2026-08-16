package yincmewy.sisi.live2dplayer;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import yincmewy.sisi.live2dplayer.live2d.Live2DClientEvents;
import yincmewy.sisi.live2dplayer.live2d.Live2DRuntime;

@Mod(Live2dplayer.MODID)
public class Live2dplayer {
    public static final String MODID = "live2dplayer";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Live2dplayer() {
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientModEvents {
        private ClientModEvents() {
        }

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("Live2D Player client setup started");
            Live2DRuntime runtime = Live2DRuntime.INSTANCE;
            runtime.init();
            boolean coreReady = runtime.renderer().prepareCore();
            LOGGER.info("Live2D models={}, coreReady={}, status={}",
                    runtime.models().size(), coreReady, runtime.renderer().loadStatus());
        }

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(Live2DClientEvents.OPEN_CONFIG);
            event.register(Live2DClientEvents.EDIT_MODE);
        }
    }
}
