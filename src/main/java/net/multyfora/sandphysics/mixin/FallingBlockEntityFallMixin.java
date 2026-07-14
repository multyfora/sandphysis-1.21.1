package net.multyfora.sandphysics.mixin;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.GatherResult;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FallingBlockEntity.class)
public class FallingBlockEntityFallMixin {

    @Unique
    private static final Logger sandphysis$LOGGER = LoggerFactory.getLogger("sandphysis");

    @Invoker("<init>")
    private static FallingBlockEntity sandphysis$create(Level level, double x, double y, double z, BlockState state) {
        throw new AssertionError();
    }

    @Inject(method = "fall", at = @At("HEAD"), cancellable = true)
    private static void onFall(Level level, BlockPos pos, BlockState state, CallbackInfoReturnable<FallingBlockEntity> cir) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        try {
            GatherResult result = SubLevelAssemblyHelper.gatherConnectedBlocks(
                pos, serverLevel, 64, (o, os, c, cs, d) -> false
            );

            if (result.assemblyState() != GatherResult.State.SUCCESS) {
                sandphysis$LOGGER.warn("Assembly failed at {} (state: {}) — destroying block", pos, result.assemblyState());
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                cir.setReturnValue(sandphysis$create(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, state));
                return;
            }

            ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(
                serverLevel, pos, result.blocks(), result.boundingBox()
            );

            if (subLevel == null || subLevel.isRemoved()) {
                sandphysis$LOGGER.warn("Sub-level null/removed after assembly at {}", pos);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                cir.setReturnValue(sandphysis$create(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, state));
                return;
            }

            CompoundTag tag = new CompoundTag();
            tag.putBoolean("sandphysis_managed", true);
            tag.putBoolean("sandphysis_anvil", state.is(BlockTags.ANVIL));
            tag.putBoolean("sandphysis_dripstone", state.getBlock() instanceof PointedDripstoneBlock);
            subLevel.setUserDataTag(tag);

            sandphysis$LOGGER.info("Converted falling block at {} to sub-level {}", pos, subLevel.getUniqueId());

            cir.setReturnValue(sandphysis$create(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, state));
        } catch (Exception e) {
            sandphysis$LOGGER.error("Failed to convert falling block at {}", pos, e);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            cir.setReturnValue(sandphysis$create(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, state));
        }
    }
}
