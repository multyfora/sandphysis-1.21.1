package net.multyfora.sandphysics;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SubLevelAutoDisassemblyManager implements SubLevelObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger("sandphysis");

    /** How long a sub-level must be completely stationary before auto-disassembly, in seconds. */
    public static final long DISASSEMBLY_IDLE_SECONDS = 100;
    /** How many consecutive ticks a sub-level must be stationary before the idle clock starts. */
    public static final long STATIONARY_TICKS_REQUIRED = 100;  // 5 seconds at 20 TPS

    private static final String SANDPHYSIS_MANAGED_KEY = "sandphysis_managed";
    private static final String SANDPHYSIS_ANVIL_KEY = "sandphysis_anvil";
    private static final String SANDPHYSIS_DRIPSTONE_KEY = "sandphysis_dripstone";
    private static final double STATIONARY_THRESHOLD_SQ = 0.001 * 0.001;
    private static final double IMPACT_VELOCITY_THRESHOLD = 3.0; // m/s minimum for impact damage

    private final Map<UUID, Long> disassemblyCandidates = new HashMap<>();
    private final Set<UUID> protectedSubLevels = new HashSet<>();
    private final Map<UUID, Vector3d> lastPositions = new HashMap<>();
    private final Map<UUID, Integer> stationaryTicks = new HashMap<>();
    private final Map<UUID, Double> prevVelocitiesY = new HashMap<>();

    public void onContainerReady(Level level, SubLevelContainer container) {
        if (level instanceof ServerLevel) {
            container.addObserver(this);
            LOGGER.info("Sub-level auto-disassembly observer registered for {}", level.dimension().location());
        }
    }

    @Override
    public void onSubLevelAdded(SubLevel subLevel) {
    }

    @Override
    public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
        UUID uuid = subLevel.getUniqueId();
        disassemblyCandidates.remove(uuid);
        protectedSubLevels.remove(uuid);
        lastPositions.remove(uuid);
        stationaryTicks.remove(uuid);
        prevVelocitiesY.remove(uuid);
    }

    @Override
    public void tick(SubLevelContainer container) {
        if (!(container instanceof ServerSubLevelContainer serverContainer)) return;

        boolean autoDisassembly = Config.AUTO_DISASSEMBLY.getAsBoolean();
        long gameTime = serverContainer.getLevel().getGameTime();

        for (ServerSubLevel subLevel : serverContainer.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;

            CompoundTag tag = subLevel.getUserDataTag();
            if (tag == null || !tag.getBoolean(SANDPHYSIS_MANAGED_KEY)) continue;

            UUID uuid = subLevel.getUniqueId();

            // --- Impact detection (always on) ---
            // Compute velocity from position delta (blocks/tick), then convert to blocks/sec
            Vector3d currentPos = new Vector3d(subLevel.logicalPose().position());
            Vector3d lastPos = lastPositions.get(uuid);

            double velY = 0;
            if (lastPos != null) {
                velY = (currentPos.y - lastPos.y) * 20.0; // convert blocks/tick → blocks/sec
            }
            lastPositions.put(uuid, currentPos);

            double prevVelY = prevVelocitiesY.getOrDefault(uuid, 0.0);

            // Impact when velocity goes from fast-downward to stopped/bouncing
            // (velY increases by >1 m/s in one tick — catches both sudden stop and bounce)
            if (prevVelY < -IMPACT_VELOCITY_THRESHOLD && velY > prevVelY + 1.0) {
                LOGGER.info("Impact detected for sub-level {}: prevVelY={}, velY={}", uuid.toString().substring(0, 8), String.format("%.3f", prevVelY), String.format("%.3f", velY));
                handleImpact(subLevel, tag, -prevVelY);
            }
            prevVelocitiesY.put(uuid, velY);

            // --- Auto-disassembly (config-gated) ---
            if (!autoDisassembly) continue;
            if (protectedSubLevels.contains(uuid)) continue;

            if (!stationaryTicks.containsKey(uuid)) {
                stationaryTicks.put(uuid, 0);
                continue;
            }

            double distSq = currentPos.distanceSquared(lastPos);

            if (distSq < STATIONARY_THRESHOLD_SQ) {
                int ticks = stationaryTicks.get(uuid) + 1;
                stationaryTicks.put(uuid, ticks);

                if (ticks == STATIONARY_TICKS_REQUIRED) {
                    disassemblyCandidates.put(uuid, gameTime);
                }

                if (ticks >= STATIONARY_TICKS_REQUIRED && disassemblyCandidates.containsKey(uuid)) {
                    long idleStart = disassemblyCandidates.get(uuid);
                    long idleElapsed = gameTime - idleStart;
                    if (idleElapsed >= DISASSEMBLY_IDLE_SECONDS * 20) {
                        LOGGER.info("Disassembling idle sub-level {} at {}", uuid, currentPos);
                        disassembleSubLevel(subLevel);
                    }
                }
            } else {
                stationaryTicks.put(uuid, 0);
                disassemblyCandidates.remove(uuid);
            }
        }
    }

    private void handleImpact(ServerSubLevel subLevel, CompoundTag tag, double speed) {
        boolean hasAnvil = tag.getBoolean(SANDPHYSIS_ANVIL_KEY);
        boolean hasDripstone = tag.getBoolean(SANDPHYSIS_DRIPSTONE_KEY);
        if (!hasAnvil && !hasDripstone) return;

        ServerLevel level = subLevel.getLevel();
        BoundingBox3dc worldBounds = subLevel.boundingBox();
        AABB damageBox = new AABB(
            worldBounds.minX(), worldBounds.minY() - 1, worldBounds.minZ(),
            worldBounds.maxX(), worldBounds.maxY(), worldBounds.maxZ()
        );

        if (hasAnvil) {
            int damage = Math.min((int) Math.ceil(speed / 2.0), 40);
            if (damage <= 0) return;
            hurtEntities(level, damageBox, level.damageSources().anvil(null), damage);
            degradeAnvilBlocks(subLevel);
        }

        if (hasDripstone) {
            int damage = Math.min((int) Math.ceil(speed * 1.5), 40);
            if (damage <= 0) return;
            hurtEntities(level, damageBox, level.damageSources().fallingStalactite(null), damage);
        }
    }

    private static void hurtEntities(ServerLevel level, AABB box, net.minecraft.world.damagesource.DamageSource source, int damage) {
        for (Entity entity : level.getEntities((Entity) null, box,
                EntitySelector.NO_CREATIVE_OR_SPECTATOR.and(EntitySelector.LIVING_ENTITY_STILL_ALIVE))) {
            entity.hurt(source, damage);
        }
    }

    private static void degradeAnvilBlocks(ServerSubLevel subLevel) {
        ServerLevelPlot plot = subLevel.getPlot();
        BoundingBox3ic localBounds = plot.getBoundingBox();
        if (localBounds == BoundingBox3i.EMPTY || localBounds.volume() <= 0) return;

        BoundingBox3dc worldBounds = subLevel.boundingBox();
        double tx = worldBounds.minX() - localBounds.minX();
        double ty = worldBounds.minY() - localBounds.minY();
        double tz = worldBounds.minZ() - localBounds.minZ();

        ServerLevel level = subLevel.getLevel();

        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;

            ChunkPos chunkPos = chunk.getPos();
            int chunkMinX = chunkPos.getMinBlockX();
            int chunkMinZ = chunkPos.getMinBlockZ();

            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                if (section.hasOnlyAir()) continue;

                int sectionY = chunk.getSectionYFromSectionIndex(i);
                int sectionMinY = sectionY << 4;

                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (!(state.getBlock() instanceof AnvilBlock)) continue;

                            BlockState degraded = AnvilBlock.damage(state);
                            int plotX = chunkMinX + x;
                            int plotY = sectionMinY + y;
                            int plotZ = chunkMinZ + z;
                            BlockPos plotPos = new BlockPos(plotX, plotY, plotZ);

                            if (degraded == null) {
                                level.setBlock(plotPos, Blocks.AIR.defaultBlockState(), 3);
                                int worldX = (int) Math.round(plotX + tx);
                                int worldY = (int) Math.round(plotY + ty);
                                int worldZ = (int) Math.round(plotZ + tz);

                                Block.popResource(level, new BlockPos(worldX, worldY, worldZ), state.getBlock().asItem().getDefaultInstance());
                            } else {
                                level.setBlock(plotPos, degraded, 3);
                            }
                        }
                    }
                }
            }
        }
    }


    private void disassembleSubLevel(ServerSubLevel subLevel) {
        ServerLevelPlot plot = subLevel.getPlot();
        BoundingBox3ic localBounds = plot.getBoundingBox();

        if (localBounds == BoundingBox3i.EMPTY || localBounds.volume() <= 0) {
            subLevel.markRemoved();
            return;
        }

        BoundingBox3dc worldBounds = subLevel.boundingBox();
        double tx = worldBounds.minX() - localBounds.minX();
        double ty = worldBounds.minY() - localBounds.minY();
        double tz = worldBounds.minZ() - localBounds.minZ();

        ServerLevel level = subLevel.getLevel();

        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;

            ChunkPos chunkPos = chunk.getPos();
            int chunkMinX = chunkPos.getMinBlockX();
            int chunkMinZ = chunkPos.getMinBlockZ();

            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                if (section.hasOnlyAir()) continue;

                int sectionY = chunk.getSectionYFromSectionIndex(i);
                int sectionMinY = sectionY << 4;

                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.isAir()) continue;

                            int plotX = chunkMinX + x;
                            int plotY = sectionMinY + y;
                            int plotZ = chunkMinZ + z;

                            int worldX = (int) Math.round(plotX + tx);
                            int worldY = (int) Math.round(plotY + ty);
                            int worldZ = (int) Math.round(plotZ + tz);

                            BlockPos worldPos = new BlockPos(worldX, worldY, worldZ);
                            BlockPos plotPos = new BlockPos(plotX, plotY, plotZ);

                            level.setBlock(worldPos, state, 3);
                            level.setBlock(plotPos, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            }
        }

        subLevel.markRemoved();
    }

    @SubscribeEvent
    public void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!Config.AUTO_DISASSEMBLY.getAsBoolean()) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        ServerSubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
        if (container == null) return;

        BlockPos pos = event.getPos();
        BoundingBox3d queryBox = new BoundingBox3d(
            pos.getX(), pos.getY(), pos.getZ(),
            pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1
        );

        for (SubLevel subLevel : container.queryIntersecting(queryBox)) {
            if (!(subLevel instanceof ServerSubLevel serverSubLevel)) continue;
            CompoundTag tag = serverSubLevel.getUserDataTag();
            if (tag == null || !tag.getBoolean(SANDPHYSIS_MANAGED_KEY)) continue;

            UUID uuid = subLevel.getUniqueId();
            if (!protectedSubLevels.contains(uuid)) {
                protectedSubLevels.add(uuid);
                disassemblyCandidates.remove(uuid);
                stationaryTicks.remove(uuid);
                lastPositions.remove(uuid);
                LOGGER.info("Protected sub-level {} (block placed at {})", uuid, pos);
            }
        }
    }
}
