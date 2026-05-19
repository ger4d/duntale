package com.duntale.camera;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.world.ServerSetBlock;
import com.hypixel.hytale.protocol.packets.world.ServerSetBlocks;
import com.hypixel.hytale.protocol.packets.world.SetBlockCmd;
import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends player-scoped fake block overrides for structural wall and ceiling
 * cells inside a camera-to-player occlusion cone so overhead views can see
 * through them without mutating shared world state.
 *
 * <h3>Algorithm — 3D Occlusion Cone</h3>
 * <ol>
 *   <li>Reconstruct the camera position from yaw, pitch, and distance.</li>
 *   <li>Build a cone from that camera position to the player's focus point.</li>
 *   <li>Scan the cone's bounding box and test each structural block cell's
 *       center against the cone volume.</li>
 *   <li>Inside-cone wall blocks are overridden to fake barrier blocks for that
 *       player only.</li>
 *   <li>If debug rendering is enabled, a synthetic cone outline is overlaid
 *       with fake marker blocks for the same player.</li>
 *   <li>Previously overridden cells that leave the cone are restored by
 *       resending the authoritative world state to that player.</li>
 * </ol>
 *
 * <p>The fake-empty packets are resent every tick so chunk reloads do not
 * permanently reintroduce occluders while xray remains enabled.</p>
 */
public class BlockOcclusionManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // ── Configuration ────────────────────────────────────────────────────

    /** Block ID for the built-in empty block used for world reads and restores. */
    private static final int AIR_BLOCK_ID = BlockType.EMPTY_ID;

    /** Asset key for the invisible collision-preserving block used for xray overrides. */
    private static final String OCCLUSION_BLOCK_KEY = "Barrier";

        /** Asset key for the visible debug marker used to outline the occlusion cone. */
        private static final String DEBUG_CONE_BLOCK_KEY = "Editor_Anchor";

        /** Player focus point offset above transform position. */
        private static final double PLAYER_FOCUS_HEIGHT_OFFSET = 1.0D;

        /** Small cone thickness near the camera to avoid voxel gaps. */
        private static final double CONE_APEX_RADIUS = 1.70D;

        /** Cone radius at the player focus point. */
        private static final double CONE_END_RADIUS = 2.0D;

    /** Tick interval in ms. */
    private static final long TICK_INTERVAL_MS = 500;

        /** Number of sampled cross-sections used for the debug cone skeleton. */
        private static final int DEBUG_CONE_SLICE_COUNT = 6;

        /** Number of sampled points around each debug ring. */
        private static final int DEBUG_CONE_RING_SEGMENTS = 12;

        /** Number of longitudinal debug edges around the cone. */
        private static final int DEBUG_CONE_LONGITUDE_COUNT = 6;

        /** Number of samples used for the debug cone center axis. */
        private static final int DEBUG_CONE_AXIS_SEGMENTS = 8;

        /**
         * Block asset keys that should remain visible even when they fall inside the
         * occlusion cone.
         *
         * <p>Derived from dungeon-gen theme resources:
         * lights under {@code Themes/*.json -> Lights.*} and loot container props with
         * {@code ChestTier} entries.</p>
         */
        private static final Set<String> EXCLUDED_OCCLUSION_BLOCK_KEYS = Set.of(
            "Deco_Lantern_Ceiling",
            "Furniture_Ancient_Chest_Small",
            "Furniture_Ancient_Crate",
            "Furniture_Crude_Brazier",
            "Furniture_Crude_Chest_Large",
            "Furniture_Crude_Chest_Small",
            "Furniture_Desert_Torch",
            "Furniture_Dungeon_Chest_Epic",
            "Furniture_Dungeon_Chest_Epic_Large",
            "Furniture_Feran_Torch",
            "Furniture_Frozen_Castle_Lamp",
            "Furniture_Frozen_Castle_Secondary_Lamp",
            "Furniture_Human_Ruins_Torch",
            "Furniture_Jungle_Brazier",
            "Furniture_Jungle_Chest_Small",
            "Furniture_Scarak_Hive_Lamp",
            "Furniture_Temple_Dark_Brazier",
            "Furniture_Temple_Dark_Chest_Large",
            "Furniture_Temple_Light_Brazier",
            "Furniture_Temple_Light_Lantern",
            "Furniture_Temple_Scarak_Chest_Small",
            "Plant_Crop_Mushroom_Glowing_Purple",
            "Wood_Torch_Wall"
        );

    // ── Camera parameters per player ─────────────────────────────────────

    /**
    * Camera-backed occlusion settings per player.
     */
    private final Map<UUID, CameraOcclusionSettings> playerOcclusionSettings = new ConcurrentHashMap<>();

    // ── State ────────────────────────────────────────────────────────────

    /** Resolved exclusion block IDs (lazy-init from EXCLUDED_OCCLUSION_BLOCK_KEYS). */
    private Set<Integer> excludedOcclusionBlockIds = null;

    /** Resolved block ID for the barrier replacement block. */
    private int occlusionBlockId = -1;

    /** Whether the barrier replacement block lookup has already been attempted. */
    private boolean occlusionBlockResolved;

    /** Resolved block ID for the debug marker block. */
    private int debugBlockId = -1;

    /** Whether the debug marker block lookup has already been attempted. */
    private boolean debugBlockResolved;

    /** Per-player fake block overrides currently active on the client. */
    private final Map<UUID, Map<Vector3i, BlockVisual>> playerVisualOverrides = new ConcurrentHashMap<>();

    /** Tracks which players have occlusion removal enabled. */
    private final Set<UUID> enabledPlayers = ConcurrentHashMap.newKeySet();

    /** Tracks players temporarily paused during the death screen. */
    private final Set<UUID> pausedPlayers = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickFuture;

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Creates a new BlockOcclusionManager with a periodic tick loop.
     */
    public BlockOcclusionManager() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BOM-Tick");
            t.setDaemon(true);
            return t;
        });
        this.tickFuture = scheduler.scheduleAtFixedRate(
                this::tick, TICK_INTERVAL_MS, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS
        );
    }

    /**
     * Enables occlusion removal for a player.
     *
     * @param uuid           the player's UUID
     * @param cameraYaw      camera yaw in radians (0=north, PI/2=east, etc.)
     * @param cameraPitch    camera pitch in radians (negative = looking down)
     * @param cameraDistance  camera distance from the player in blocks
     * @param debugCone      whether to render the cone outline with debug blocks
     */
    public void enable(@Nonnull UUID uuid,
                       float cameraYaw,
                       float cameraPitch,
                       float cameraDistance,
                       boolean debugCone) {
        pausedPlayers.remove(uuid);
        enabledPlayers.add(uuid);
        playerVisualOverrides.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>());
        playerOcclusionSettings.put(uuid, new CameraOcclusionSettings(cameraYaw, cameraPitch, cameraDistance, debugCone));
    }

    /**
     * Disables occlusion removal and restores all original blocks.
     *
     * @param uuid  the player's UUID
     * @param world the world to restore blocks in
     */
    public void disable(@Nonnull UUID uuid, @Nonnull World world) {
        enabledPlayers.remove(uuid);
        pausedPlayers.remove(uuid);
        restoreAllBlocks(uuid, world);
        playerVisualOverrides.remove(uuid);
        playerOcclusionSettings.remove(uuid);
    }

    /**
     * Disables occlusion removal without block restoration when the player's world
     * is no longer available.
     *
     * <p>This is a disconnect fallback used to drop tracking state and avoid
     * leaking per-player camera data.</p>
     *
     * @param uuid the player's UUID
     */
    public void disable(@Nonnull UUID uuid) {
        enabledPlayers.remove(uuid);
        pausedPlayers.remove(uuid);
        playerVisualOverrides.remove(uuid);
        playerOcclusionSettings.remove(uuid);
    }

    /**
     * Checks if occlusion removal is enabled for a player.
     *
     * @param uuid the player's UUID
     * @return true if enabled
     */
    public boolean isEnabled(@Nonnull UUID uuid) {
        return enabledPlayers.contains(uuid);
    }

    /**
     * Temporarily suspends occlusion removal for a dead player and restores any
     * cleared blocks until respawn.
     *
     * @param uuid the player's UUID
     * @param world the world containing the cleared blocks
     */
    public void pauseForDeath(@Nonnull UUID uuid, @Nonnull World world) {
        if (!enabledPlayers.remove(uuid)) {
            return;
        }
        restoreAllBlocks(uuid, world);
        pausedPlayers.add(uuid);
    }

    /**
     * Re-enables occlusion removal after the player's death screen finishes.
     *
     * @param uuid the player's UUID
     */
    public void resumeAfterRespawn(@Nonnull UUID uuid) {
        if (!pausedPlayers.remove(uuid)) {
            return;
        }
        if (!playerOcclusionSettings.containsKey(uuid)) {
            return;
        }
        playerVisualOverrides.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>());
        enabledPlayers.add(uuid);
    }

    /**
     * Shuts down the tick loop and restores all blocks for all players.
     *
     * @param world the world to restore blocks in
     */
    public void shutdown(@Nonnull World world) {
        if (this.tickFuture != null) {
            this.tickFuture.cancel(false);
        }
        this.scheduler.shutdownNow();
        for (UUID uuid : new HashSet<>(playerVisualOverrides.keySet())) {
            restoreAllBlocks(uuid, world);
        }
        enabledPlayers.clear();
        pausedPlayers.clear();
        playerVisualOverrides.clear();
        playerOcclusionSettings.clear();
    }

    // ── Tick Loop ────────────────────────────────────────────────────────

    private void tick() {
        if (enabledPlayers.isEmpty()) return;

        for (UUID uuid : enabledPlayers) {
            PlayerRef playerRef = Universe.get().getPlayer(uuid);
            if (playerRef == null) continue;

            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) continue;

            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();

            world.execute(() -> {
                if (!ref.isValid()) return;
                update(ref, store, world);
            });
        }
    }

    // ── Lazy-init helpers ────────────────────────────────────────────────

    /**
     * Resolves the block IDs that should not be occluded.
     */
    @Nonnull
    private Set<Integer> getExcludedOcclusionBlockIds() {
        if (excludedOcclusionBlockIds == null) {
            Set<Integer> ids = new HashSet<>();
            for (String key : EXCLUDED_OCCLUSION_BLOCK_KEYS) {
                int id = BlockType.getAssetMap().getIndex(key);
                if (id > 0) {
                    ids.add(id);
                } else {
                    LOGGER.atWarning().log("Excluded occlusion block key not found in asset map: {}", key);
                }
            }
            excludedOcclusionBlockIds = ids;
            LOGGER.atInfo().log("Resolved {} excluded occlusion block IDs", ids.size());
        }
        return excludedOcclusionBlockIds;
    }

    @Nullable
    private BlockVisual getOcclusionBlock() {
        if (!occlusionBlockResolved) {
            occlusionBlockId = BlockType.getAssetMap().getIndex(OCCLUSION_BLOCK_KEY);
            occlusionBlockResolved = true;
            if (occlusionBlockId > 0) {
                LOGGER.atInfo().log("Resolved xray occlusion block ID: {}", occlusionBlockId);
            } else {
                LOGGER.atWarning().log("Xray occlusion block key not found in asset map: {}", OCCLUSION_BLOCK_KEY);
            }
        }

        if (occlusionBlockId <= 0) {
            return null;
        }
        return new BlockVisual(occlusionBlockId, (short) 0, (byte) 0);
    }

    private int getDebugBlockId() {
        if (!debugBlockResolved) {
            debugBlockId = BlockType.getAssetMap().getIndex(DEBUG_CONE_BLOCK_KEY);
            debugBlockResolved = true;
            if (debugBlockId > 0) {
                LOGGER.atInfo().log("Resolved xray debug cone block ID: {}", debugBlockId);
            } else {
                LOGGER.atWarning().log("Xray debug cone block key not found in asset map: {}", DEBUG_CONE_BLOCK_KEY);
            }
        }
        return debugBlockId;
    }

    // ── Core cone test ───────────────────────────────────────────────────

    private void update(@Nonnull Ref<EntityStore> ref,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull World world) {
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        if (!isEnabled(uuid)) return;

        CameraOcclusionSettings cameraSettings = playerOcclusionSettings.get(uuid);
        if (cameraSettings == null) return;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d playerPos = transform.getPosition();
        int playerFootY = (int) Math.floor(playerPos.y);
        Vector3d playerFocus = new Vector3d(playerPos.x, playerPos.y + PLAYER_FOCUS_HEIGHT_OFFSET, playerPos.z);
        Vector3d cameraPosition = computeCameraPosition(playerFocus, cameraSettings);
        ConeGeometry cone = createConeGeometry(cameraPosition, playerFocus);
        if (cone == null) {
            restoreAllBlocks(uuid, world);
            return;
        }

        BlockVisual occlusionBlock = getOcclusionBlock();
        if (occlusionBlock == null) {
            restoreAllBlocks(uuid, world);
            return;
        }

        Set<Integer> excludedIds = getExcludedOcclusionBlockIds();

        Map<Vector3i, BlockVisual> desiredOverrides = new HashMap<>();
        for (Vector3i pos : collectConeOccluderBlocks(world, excludedIds, cone, playerFootY)) {
            desiredOverrides.put(pos, occlusionBlock);
        }

        if (cameraSettings.debugCone()) {
            int resolvedDebugBlockId = getDebugBlockId();
            if (resolvedDebugBlockId > 0) {
                BlockVisual debugBlock = new BlockVisual(resolvedDebugBlockId, (short) 0, (byte) 0);
                for (Vector3i pos : buildDebugConeOutline(cone)) {
                    if (pos.y < playerFootY) {
                        continue;
                    }
                    desiredOverrides.put(pos, debugBlock);
                }
            }
        }

        Map<Vector3i, BlockVisual> trackedOverrides =
                playerVisualOverrides.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>());
        Set<Vector3i> restorePositions = new HashSet<>(trackedOverrides.keySet());
        restorePositions.removeAll(desiredOverrides.keySet());
        if (!restorePositions.isEmpty()) {
            sendCurrentWorldBlocks(playerRef, world, restorePositions);
        }

        trackedOverrides.clear();
        trackedOverrides.putAll(desiredOverrides);

        if (!trackedOverrides.isEmpty()) {
            sendOverrideBlocks(playerRef, trackedOverrides);
        }
    }

    @Nonnull
    private static Set<Vector3i> collectConeOccluderBlocks(@Nonnull World world,
                                                            @Nonnull Set<Integer> excludedBlockIds,
                                                            @Nonnull ConeGeometry cone,
                                                            int minOcclusionY) {
        Set<Vector3i> positions = new HashSet<>();
        for (int x = cone.minX(); x <= cone.maxX(); x++) {
            for (int y = Math.max(cone.minY(), minOcclusionY); y <= cone.maxY(); y++) {
                for (int z = cone.minZ(); z <= cone.maxZ(); z++) {
                    if (!isOccluderBlock(world, excludedBlockIds, x, y, z)) {
                        continue;
                    }
                    if (isBlockCenterInsideCone(x, y, z, cone)) {
                        positions.add(new Vector3i(x, y, z));
                    }
                }
            }
        }
        return positions;
    }

    private static boolean isBlockCenterInsideCone(int x,
                                                   int y,
                                                   int z,
                                                   @Nonnull ConeGeometry cone) {
        double relX = x + 0.5D - cone.apex().x;
        double relY = y + 0.5D - cone.apex().y;
        double relZ = z + 0.5D - cone.apex().z;

        double t = relX * cone.axisDir().x + relY * cone.axisDir().y + relZ * cone.axisDir().z;
        if (t < 0.0D || t > cone.axisLength()) {
            return false;
        }

        double alpha = t / cone.axisLength();
        double radius = cone.apexRadius() + (cone.endRadius() - cone.apexRadius()) * alpha;
        double radialDistanceSq = Math.max(0.0D, relX * relX + relY * relY + relZ * relZ - t * t);
        return radialDistanceSq <= radius * radius;
    }

    @Nonnull
    private static Set<Vector3i> buildDebugConeOutline(@Nonnull ConeGeometry cone) {
        Set<Vector3i> positions = new HashSet<>();

        Vector3d axisDir = cone.axisDir();
        Vector3d referenceAxis = Math.abs(axisDir.y) < 0.99D
                ? new Vector3d(0.0D, 1.0D, 0.0D)
                : new Vector3d(1.0D, 0.0D, 0.0D);
        Vector3d basisU = axisDir.cross(referenceAxis, new Vector3d());
        if (basisU.lengthSquared() < 1.0E-6D) {
            basisU.set(1.0D, 0.0D, 0.0D);
        } else {
            basisU.normalize();
        }
        Vector3d basisV = axisDir.cross(basisU, new Vector3d()).normalize();

        for (int slice = 0; slice <= DEBUG_CONE_SLICE_COUNT; slice++) {
            double alpha = slice / (double) DEBUG_CONE_SLICE_COUNT;
            addDebugRing(positions, cone, basisU, basisV, alpha);
        }

        for (int longitude = 0; longitude < DEBUG_CONE_LONGITUDE_COUNT; longitude++) {
            double angle = (Math.PI * 2.0D * longitude) / DEBUG_CONE_LONGITUDE_COUNT;
            addDebugLongitude(positions, cone, basisU, basisV, angle);
        }

        addDebugAxis(positions, cone);
        return positions;
    }

    private static void addDebugRing(@Nonnull Set<Vector3i> positions,
                                     @Nonnull ConeGeometry cone,
                                     @Nonnull Vector3d basisU,
                                     @Nonnull Vector3d basisV,
                                     double alpha) {
        Vector3d center = pointAlongAxis(cone, alpha * cone.axisLength());
        double radius = cone.apexRadius() + (cone.endRadius() - cone.apexRadius()) * alpha;

        if (radius < 0.15D) {
            addDebugBlock(positions, center.x, center.y, center.z);
            return;
        }

        for (int segment = 0; segment < DEBUG_CONE_RING_SEGMENTS; segment++) {
            double angle = (Math.PI * 2.0D * segment) / DEBUG_CONE_RING_SEGMENTS;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            addDebugBlock(
                    positions,
                    center.x + basisU.x * cos * radius + basisV.x * sin * radius,
                    center.y + basisU.y * cos * radius + basisV.y * sin * radius,
                    center.z + basisU.z * cos * radius + basisV.z * sin * radius
            );
        }
    }

    private static void addDebugLongitude(@Nonnull Set<Vector3i> positions,
                                          @Nonnull ConeGeometry cone,
                                          @Nonnull Vector3d basisU,
                                          @Nonnull Vector3d basisV,
                                          double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        for (int slice = 0; slice <= DEBUG_CONE_SLICE_COUNT; slice++) {
            double alpha = slice / (double) DEBUG_CONE_SLICE_COUNT;
            Vector3d center = pointAlongAxis(cone, alpha * cone.axisLength());
            double radius = cone.apexRadius() + (cone.endRadius() - cone.apexRadius()) * alpha;
            addDebugBlock(
                    positions,
                    center.x + basisU.x * cos * radius + basisV.x * sin * radius,
                    center.y + basisU.y * cos * radius + basisV.y * sin * radius,
                    center.z + basisU.z * cos * radius + basisV.z * sin * radius
            );
        }
    }

    private static void addDebugAxis(@Nonnull Set<Vector3i> positions, @Nonnull ConeGeometry cone) {
        for (int step = 0; step <= DEBUG_CONE_AXIS_SEGMENTS; step++) {
            double alpha = step / (double) DEBUG_CONE_AXIS_SEGMENTS;
            Vector3d point = pointAlongAxis(cone, alpha * cone.axisLength());
            addDebugBlock(positions, point.x, point.y, point.z);
        }
    }

    @Nonnull
    private static Vector3d pointAlongAxis(@Nonnull ConeGeometry cone, double distanceAlongAxis) {
        return new Vector3d(
                cone.apex().x + cone.axisDir().x * distanceAlongAxis,
                cone.apex().y + cone.axisDir().y * distanceAlongAxis,
                cone.apex().z + cone.axisDir().z * distanceAlongAxis
        );
    }

    private static void addDebugBlock(@Nonnull Set<Vector3i> positions,
                                      double x,
                                      double y,
                                      double z) {
        int blockY = (int) Math.round(y);
        if (blockY < 0 || blockY >= 320) {
            return;
        }

        positions.add(new Vector3i(
                (int) Math.round(x),
                blockY,
                (int) Math.round(z)
        ));
    }

    @Nonnull
    private static Vector3d computeCameraPosition(@Nonnull Vector3d playerFocus,
                                                  @Nonnull CameraOcclusionSettings cameraSettings) {
        double cosPitch = Math.cos(cameraSettings.cameraPitch());
        double offsetX = Math.sin(cameraSettings.cameraYaw()) * cosPitch * cameraSettings.cameraDistance();
        double offsetY = -Math.sin(cameraSettings.cameraPitch()) * cameraSettings.cameraDistance();
        double offsetZ = Math.cos(cameraSettings.cameraYaw()) * cosPitch * cameraSettings.cameraDistance();
        return new Vector3d(playerFocus).add(offsetX, offsetY, offsetZ);
    }

    @Nullable
    private static ConeGeometry createConeGeometry(@Nonnull Vector3d cameraPosition,
                                                   @Nonnull Vector3d playerFocus) {
        Vector3d axis = new Vector3d(playerFocus).sub(cameraPosition);
        double axisLength = axis.length();
        if (axisLength < 1.0E-4D) {
            return null;
        }

        axis.div(axisLength);

        double maxRadius = Math.max(CONE_APEX_RADIUS, CONE_END_RADIUS) + 1.0D;
        int minX = (int) Math.floor(Math.min(cameraPosition.x, playerFocus.x) - maxRadius);
        int maxX = (int) Math.ceil(Math.max(cameraPosition.x, playerFocus.x) + maxRadius);
        int minY = Math.max(0, (int) Math.floor(Math.min(cameraPosition.y, playerFocus.y) - maxRadius));
        int maxY = Math.min(319, (int) Math.ceil(Math.max(cameraPosition.y, playerFocus.y) + maxRadius));
        int minZ = (int) Math.floor(Math.min(cameraPosition.z, playerFocus.z) - maxRadius);
        int maxZ = (int) Math.ceil(Math.max(cameraPosition.z, playerFocus.z) + maxRadius);

        return new ConeGeometry(cameraPosition, axis, axisLength, CONE_APEX_RADIUS, CONE_END_RADIUS,
                minX, maxX, minY, maxY, minZ, maxZ);
    }

    // ── Restoration ──────────────────────────────────────────────────────

    private void restoreAllBlocks(@Nonnull UUID uuid, @Nonnull World world) {
        Map<Vector3i, BlockVisual> overriddenBlocks = playerVisualOverrides.get(uuid);
        if (overriddenBlocks == null || overriddenBlocks.isEmpty()) {
            return;
        }

        PlayerRef playerRef = Universe.get().getPlayer(uuid);
        if (playerRef != null) {
            sendCurrentWorldBlocks(playerRef, world, new HashSet<>(overriddenBlocks.keySet()));
        }
        overriddenBlocks.clear();
    }

    // ── Packet helpers ───────────────────────────────────────────────────

    private void sendOverrideBlocks(@Nonnull PlayerRef playerRef,
                                    @Nonnull Map<Vector3i, BlockVisual> overrides) {
        Map<SectionKey, List<SetBlockCmd>> batches = new HashMap<>();
        for (Map.Entry<Vector3i, BlockVisual> entry : overrides.entrySet()) {
            addBatchCommand(batches, entry.getKey(), entry.getValue());
        }
        sendBatches(playerRef, batches);
    }

    private void sendCurrentWorldBlocks(@Nonnull PlayerRef playerRef,
                                        @Nonnull World world,
                                        @Nonnull Set<Vector3i> positions) {
        Map<SectionKey, List<SetBlockCmd>> batches = new HashMap<>();
        for (Vector3i pos : positions) {
            BlockVisual blockVisual = readBlockVisual(world, pos.x, pos.y, pos.z);
            if (blockVisual == null) {
                continue;
            }
            addBatchCommand(batches, pos, blockVisual);
        }
        sendBatches(playerRef, batches);
    }

    private static void addBatchCommand(@Nonnull Map<SectionKey, List<SetBlockCmd>> batches,
                                        @Nonnull Vector3i pos,
                                        @Nonnull BlockVisual blockVisual) {
        SectionKey sectionKey = new SectionKey(
                ChunkUtil.chunkCoordinate(pos.x),
                ChunkUtil.chunkCoordinate(pos.y),
                ChunkUtil.chunkCoordinate(pos.z)
        );
        SetBlockCmd command = new SetBlockCmd(
                (short) ChunkUtil.indexBlock(pos.x, pos.y, pos.z),
                blockVisual.blockId(),
                blockVisual.filler(),
                blockVisual.rotation()
        );
        batches.computeIfAbsent(sectionKey, ignored -> new ArrayList<>()).add(command);
    }

    private static void sendBatches(@Nonnull PlayerRef playerRef,
                                    @Nonnull Map<SectionKey, List<SetBlockCmd>> batches) {
        if (batches.isEmpty()) {
            return;
        }

        ChunkTracker tracker = playerRef.getChunkTracker();
        if (tracker == null) {
            return;
        }

        for (Map.Entry<SectionKey, List<SetBlockCmd>> entry : batches.entrySet()) {
            SectionKey sectionKey = entry.getKey();
            long chunkIndex = ChunkUtil.indexChunk(sectionKey.x(), sectionKey.z());
            if (!tracker.isLoaded(chunkIndex)) {
                continue;
            }

            List<SetBlockCmd> commands = entry.getValue();
            if (commands.isEmpty()) {
                continue;
            }

            if (commands.size() == 1) {
                SetBlockCmd command = commands.get(0);
                int x = ChunkUtil.minBlock(sectionKey.x()) + ChunkUtil.xFromIndex(command.index);
                int y = ChunkUtil.minBlock(sectionKey.y()) + ChunkUtil.yFromIndex(command.index);
                int z = ChunkUtil.minBlock(sectionKey.z()) + ChunkUtil.zFromIndex(command.index);
                playerRef.getPacketHandler().writeNoCache(
                        new ServerSetBlock(x, y, z, command.blockId, command.filler, command.rotation)
                );
                continue;
            }

            playerRef.getPacketHandler().writeNoCache(
                    new ServerSetBlocks(
                            sectionKey.x(),
                            sectionKey.y(),
                            sectionKey.z(),
                            commands.toArray(SetBlockCmd[]::new)
                    )
            );
        }
    }

    private static boolean isOccluderBlock(@Nonnull World world,
                                           @Nonnull Set<Integer> excludedBlockIds,
                                           int x,
                                           int y,
                                           int z) {
        int blockId = getBlock(world, x, y, z);
        return blockId != AIR_BLOCK_ID && !excludedBlockIds.contains(blockId);
    }

    // ── Block access helpers ─────────────────────────────────────────────

    private static int getBlock(@Nonnull World world, int x, int y, int z) {
        WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null) return AIR_BLOCK_ID;
        return chunk.getBlock(x, y, z);
    }

    private static BlockVisual readBlockVisual(@Nonnull World world, int x, int y, int z) {
        WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null) {
            return null;
        }

        return new BlockVisual(
                chunk.getBlock(x, y, z),
                (short) chunk.getFiller(x, y, z),
                (byte) chunk.getRotationIndex(x, y, z)
        );
    }

    private record BlockVisual(int blockId, short filler, byte rotation) {
    }

    private record CameraOcclusionSettings(float cameraYaw,
                                           float cameraPitch,
                                           float cameraDistance,
                                           boolean debugCone) {
    }

    private record ConeGeometry(@Nonnull Vector3d apex,
                                @Nonnull Vector3d axisDir,
                                double axisLength,
                                double apexRadius,
                                double endRadius,
                                int minX,
                                int maxX,
                                int minY,
                                int maxY,
                                int minZ,
                                int maxZ) {
    }

    private record SectionKey(int x, int y, int z) {
    }
}
