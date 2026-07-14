package net.multyfora.sandphysics;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue AUTO_DISASSEMBLY = BUILDER
            .comment("Enable auto-disassembly of falling-block sub-levels back into world blocks.\n"
                    + "When enabled, sub-levels that are stationary and idle for the configured timeout will be\n"
                    + "disassembled and their blocks placed back in the world as regular blocks.")
            .define("autoDisassembly", false);

    static final ModConfigSpec SPEC = BUILDER.build();
}
