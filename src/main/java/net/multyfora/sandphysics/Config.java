package net.multyfora.sandphysics;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue AUTO_DISASSEMBLY = BUILDER
            .comment("Enable auto-disassembly of falling-block sub-levels after a idle time.\n"
                    + "When enabled, sub-levels that are stationary and idle for the 3 minutes will be\n"
                    + "disassembled and their blocks placed back in the world as regular blocks.")
            .define("autoDisassembly", false);

    public static final ModConfigSpec.BooleanValue DISASSEMBLE_ON_IMPACT = BUILDER
            .comment("Disassemble falling-block sub-levels a few seconds after they hit the ground.\n"
                    + "When enabled, blocks are placed back in the world a short time after impact\n"
                    + "(anvil/dripstone damage is still applied before disassembly).")
            .define("disassembleOnImpact", false);

    public static final ModConfigSpec.IntValue ANVIL_DAMAGE_CAP = BUILDER
            .comment("Maximum damage dealt by falling anvil sub-levels on impact.\n"
                    + "Damage is calculated from impact speed and capped at this value.\n"
                    + "Default: 40")
            .defineInRange("anvilDamageCap", 40, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue DRIPSTONE_DAMAGE_CAP = BUILDER
            .comment("Maximum damage dealt by falling dripstone sub-levels on impact.\n"
                    + "Damage is calculated from impact speed and capped at this value.\n"
                    + "Default: 40")
            .defineInRange("dripstoneDamageCap", 40, 0, Integer.MAX_VALUE);

    static final ModConfigSpec SPEC = BUILDER.build();
}
