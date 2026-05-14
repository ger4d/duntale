package com.duntale.camera;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.duntale.companion.CompanionComponent;
import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Static target-resolution utilities for the click-to-move system.
 *
 * <p>Resolves entity targets from mouse events, performs wall-click probing,
 * and provides walkability checks. All methods are pure functions with no
 * mutable state.</p>
 */
final class TargetResolver {

    /**
     * Cardinal offsets to probe when the clicked block is a wall
     * (target Y &gt; player Y). Order: +X, -X, +Z, -Z.
     */
    private static final int[][] WALL_PROBE_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    /**
     * Search radius (in blocks) for the wall-occlusion entity fallback.
     */
    private static final double WALL_ENTITY_SEARCH_RADIUS = 5.0;

    private TargetResolver() {} // utility class

    /**
     * Validates the entity reference reported by a mouse event.
     * Returns {@code null} if the reference is null, invalid, or is the player themselves.
     *
     * @param targetEntityRef the entity reference reported by the client (may be null)
     * @param playerRef       the player's own entity reference (to exclude self-targeting)
     * @return the valid entity reference, or {@code null}
     */
    @Nullable
    static Ref<EntityStore> resolveTargetEntity(@Nullable Ref<EntityStore> targetEntityRef,
                                                @Nonnull Ref<EntityStore> playerRef) {
        if (targetEntityRef == null || !targetEntityRef.isValid()) return null;
        if (targetEntityRef.equals(playerRef)) return null;
        return targetEntityRef;
    }

    /**
     * Fallback entity detection for wall occlusion. When the client's raycast hits a
     * wall before reaching an entity behind it, this method searches nearby using the
     * engine's spatial index.
     *
     * @param store       entity store
     * @param playerRef   the player's entity reference (excluded from results)
     * @param targetBlock the block the client's raycast hit (the wall)
     * @return the closest targetable entity near the wall, or {@code null}
     */
    @Nullable
    static Ref<EntityStore> findNearbyEntityFallback(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull Vector3i targetBlock) {
        Vector3d searchCenter = new Vector3d(
                targetBlock.x + 0.5,
                targetBlock.y + 0.5,
                targetBlock.z + 0.5);

        List<Ref<EntityStore>> nearby = TargetUtil.getAllEntitiesInSphere(
                searchCenter, WALL_ENTITY_SEARCH_RADIUS, store);

        Ref<EntityStore> closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (Ref<EntityStore> entityRef : nearby) {
            if (entityRef == null || !entityRef.isValid()) continue;
            if (entityRef.equals(playerRef)) continue;
            if (store.getComponent(entityRef, CompanionComponent.getComponentType()) != null) continue;
            if (store.getComponent(entityRef, Intangible.getComponentType()) != null) continue;

            BoundingBox bb = store.getComponent(entityRef, BoundingBox.getComponentType());
            if (bb == null) continue;

            TransformComponent tc = store.getComponent(entityRef, TransformComponent.getComponentType());
            if (tc == null) continue;

            Vector3d ePos = tc.getPosition();
            double dx = ePos.x - searchCenter.x;
            double dy = ePos.y - searchCenter.y;
            double dz = ePos.z - searchCenter.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = entityRef;
            }
        }

        return closest;
    }

    /**
     * When a click hits a wall (target Y &gt; player Y), probes the 4 cardinal
     * neighbours at the player's foot level to find the nearest non-air block.
     *
     * @return the walkable block position, or {@code null} if none found
     */
    @Nullable
    static Vector3i resolveWallClick(@Nonnull Store<EntityStore> store,
                                     @Nonnull Ref<EntityStore> ref,
                                     int wallX, int wallZ, int footY) {
        World world = store.getExternalData().getWorld();
        for (int[] offset : WALL_PROBE_OFFSETS) {
            int probeX = wallX + offset[0];
            int probeZ = wallZ + offset[1];

            long chunkIdx = ChunkUtil.indexChunkFromBlock(probeX, probeZ);
            WorldChunk chunk = world.getChunkIfInMemory(chunkIdx);
            if (chunk == null) continue;

            int blockId = chunk.getBlock(probeX, footY, probeZ);
            if (blockId != 0) {
                int aboveId = chunk.getBlock(probeX, footY + 1, probeZ);
                if (aboveId == 0) {
                    return new Vector3i(probeX, footY, probeZ);
                }
            }
        }
        return null;
    }

    /**
     * Checks whether a block position is walkable: solid block at {@code footY}
     * and air above it. Returns {@code false} if the chunk is not loaded.
     *
     * @param store  entity store to access the world
     * @param blockX block X coordinate
     * @param blockZ block Z coordinate
     * @param footY  Y level of the ground block (under the player's feet)
     * @return {@code true} if the position is walkable
     */
    static boolean isWalkable(@Nonnull Store<EntityStore> store,
                              int blockX, int blockZ, int footY) {
        World world = store.getExternalData().getWorld();
        long chunkIdx = ChunkUtil.indexChunkFromBlock(blockX, blockZ);
        WorldChunk chunk = world.getChunkIfInMemory(chunkIdx);
        if (chunk == null) return false;

        int blockId = chunk.getBlock(blockX, footY, blockZ);
        int aboveId = chunk.getBlock(blockX, footY + 1, blockZ);
        return blockId != 0 && aboveId == 0;
    }

    /**
     * Checks whether the block at the given position has a
     * {@link InteractionType#Use Use} interaction registered (e.g. bench, chest).
     *
     * @param store entity store to access the world
     * @param x     block X coordinate
     * @param y     block Y coordinate
     * @param z     block Z coordinate
     * @return {@code true} if the block has a Use interaction
     */
    static boolean isInteractableBlock(@Nonnull Store<EntityStore> store,
                                       int x, int y, int z) {
        World world = store.getExternalData().getWorld();
        BlockType blockType = world.getBlockType(x, y, z);
        if (blockType == null) return false;
        Map<InteractionType, String> interactions = blockType.getInteractions();
        return interactions != null && interactions.containsKey(InteractionType.Use);
    }
}
