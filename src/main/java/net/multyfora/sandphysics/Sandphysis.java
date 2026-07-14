package net.multyfora.sandphysics;

import dev.ryanhcode.sable.platform.SableEventPlatform;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;

@Mod(Sandphysis.MODID)
public class Sandphysis {

    public static final String MODID = "sandphysis";

    public Sandphysis(IEventBus modEventBus, ModContainer modContainer) {
        SubLevelAutoDisassemblyManager disassemblyManager = new SubLevelAutoDisassemblyManager();
        SableEventPlatform.INSTANCE.onSubLevelContainerReady(disassemblyManager::onContainerReady);
        NeoForge.EVENT_BUS.register(disassemblyManager);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }
}
