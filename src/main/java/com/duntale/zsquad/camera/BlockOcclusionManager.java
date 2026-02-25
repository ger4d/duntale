package com.duntale.zsquad.camera;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Continuously removes blocks <b>between the camera and the player</b> so the
 * player is visible in overhead camera modes. Only clears blocks in the
 * direction the camera is looking from (e.g., for an SE camera, only blocks
 * to the south-east and above are cleared).
 *
 * <p>The clear zone is biased 1 extra block in the player's movement direction
 * for smoother visual transitions.</p>
 *
 * <p><b>Warning:</b> This modifies actual world state visible to all players.
 * A production solution would need per-player fake block sending.</p>
 */
public class BlockOcclusionManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** How many blocks above the player to clear (main zone). */
    private static final int CLEAR_HEIGHT = 10;

    /** Base horizontal radius around the player to clear. */
    private static final int CLEAR_RADIUS = 3;

    /** Block ID for air — used to detect already-empty positions. */
    private static final int AIR_BLOCK_ID = 0;

    /** Asset key for the built-in barrier block (solid but invisible). */
    private static final String TRANSPARENT_BLOCK_KEY = "Barrier";

    /** Tick interval in ms (~4 Hz). */
    private static final long TICK_INTERVAL_MS = 250;

    /** Resolved block ID for the transparent occluder block (lazy-init). */
    private int transparentBlockId = -1;

    /** Per-player original block state tracking for restoration. */
    private final Map<UUID, Map<Vector3i, Integer>> playerOriginalBlocks = new ConcurrentHashMap<>();

    /** Tracks which players have occlusion removal enabled. */
    private final Set<UUID> enabledPlayers = ConcurrentHashMap.newKeySet();

    /** Per-player camera direction offsets (dx, dz from player toward camera). */
    private final Map<UUID, int[]> playerCameraDirection = new ConcurrentHashMap<>();

    /** Per-player last known position for movement direction tracking. */
    private final Map<UUID, Vector3d> playerLastPosition = new ConcurrentHashMap<>();

    /** Per-player movement direction bias (dx, dz). */
    private final Map<UUID, int[]> playerMoveDirection = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickFuture;

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
     * @param uuid     the player's UUID
     * @param cameraYaw the camera yaw in radians (0=north, PI/2=east, etc.)
     */
    public void enable(@Nonnull UUID uuid, float cameraYaw) {
        enabledPlayers.add(uuid);
        playerOriginalBlocks.putIfAbsent(uuid, new HashMap<>());

        // Compute the direction from player toward camera (camera is "behind" the view).
        // The camera looks FROM this direction, so blocks in this direction occlude the player.
        // cameraYaw: 0=N(+Z), PI/4=NE, PI/2=E(+X), 3PI/4=SE, PI=S(-Z), etc.
        int camDx = (int) Math.round(Math.sin(cameraYaw));
        int camDz = (int) Math.round(Math.cos(cameraYaw));
        playerCameraDirection.put(uuid, new int[]{camDx, camDz});
    }

    /**
     * Disables occlusion removal and restores all original blocks.
     *
     * @param uuid  the player's UUID
     * @param world the world to restore blocks in
     */
    public void disable(@Nonnull UUID uuid, @Nonnull World world) {
        enabledPlayers.remove(uuid);
        restoreAllBlocks(uuid, world);
        playerOriginalBlocks.remove(uuid);
        playerCameraDirection.remove(uuid);
        playerLastPosition.remove(uuid);
        playerMoveDirection.remove(uuid);
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
     * Shuts down the tick loop and restores all blocks for all players.
     *
     * @param world the world to restore blocks in
     */
    public void shutdown(@Nonnull World world) {
        if (this.tickFuture != null) {
            this.tickFuture.cancel(false);
        }
        this.scheduler.shutdownNow();
        for (UUID uuid : enabledPlayers) {
            restoreAllBlocks(uuid, world);
        }
        enabledPlayers.clear();
        playerOriginalBlocks.clear();
        playerCameraDirection.clear();
        playerLastPosition.clear();
        playerMoveDirection.clear();
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

    // ── Core Logic ───────────────────────────────────────────────────────

    /**
     * Resolves the transparent occluder block ID from the asset map on first use.
     *
     * @return the integer block ID for the transparent occluder block
     */
    private int getTransparentBlockId() {
        if (transparentBlockId == -1) {
            transparentBlockId = BlockType.getAssetMap().getIndex(TRANSPARENT_BLOCK_KEY);
            LOGGER.atInfo().log("Resolved transparent occluder block ID: {}", transparentBlockId);
        }
        return transparentBlockId;
    }

    private void update(@Nonnull Ref<EntityStore> ref,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull World world) {
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        if (!isEnabled(uuid)) return;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d playerPos = transform.getPosition();
        int playerX = (int) Math.floor(playerPos.x);
        int playerY = (int) Math.floor(playerPos.y);
        int playerZ = (int) Math.floor(playerPos.z);

        // Track movement direction for biased clearing
        updateMoveDirection(uuid, playerPos);

        Map<Vector3i, Integer> originalBlocks = playerOriginalBlocks.computeIfAbsent(uuid, k -> new HashMap<>());
        Set<Vector3i> previousPositions = new HashSet<>(originalBlocks.keySet());

        // Get camera direction (blocks between camera and player to clear)
        int[] camDir = playerCameraDirection.getOrDefault(uuid, new int[]{1, 1}); // default SE
        int camDx = camDir[0];
        int camDz = camDir[1];

        // Get movement direction bias
        int[] moveDir = playerMoveDirection.getOrDefault(uuid, new int[]{0, 0});
        int moveDx = moveDir[0];
        int moveDz = moveDir[1];

        // Clear zone: y+1 (just above feet) to y+CLEAR_HEIGHT+1
        // Never touch floor blocks (playerY or below)
        int yStart = playerY + 1;
        int yEnd = playerY + CLEAR_HEIGHT + 1;

        // Build directional clear zone:
        // Only clear blocks on the camera side (between camera and player)
        Set<Vector3i> newPositions = new HashSet<>();
        for (int y = yStart; y <= yEnd; y++) {
            for (int dx = -CLEAR_RADIUS; dx <= CLEAR_RADIUS; dx++) {
                for (int dz = -CLEAR_RADIUS; dz <= CLEAR_RADIUS; dz++) {
                    // Only clear blocks on the camera side of the player.
                    // Directly above (dx=0, dz=0) is always cleared.
                    if (dx != 0 || dz != 0) {
                        if (camDx != 0 && camDz != 0) {
                            // Diagonal camera (NE, SE, SW, NW)
                            boolean inCamQuadrant = (dx * camDx >= 0) && (dz * camDz >= 0);
                            if (!inCamQuadrant) continue;
                        } else if (camDx != 0) {
                            // Cardinal E or W
                            if (dx * camDx < 0) continue;
                        } else if (camDz != 0) {
                            // Cardinal N or S
                            if (dz * camDz < 0) continue;
                        }
                    }

                    int bx = playerX + dx;
                    int bz = playerZ + dz;

                    newPositions.add(new Vector3i(bx, y, bz));
                }
            }

            // Extra block in movement direction
            if (moveDx != 0 || moveDz != 0) {
                int extraX = playerX + (CLEAR_RADIUS + 1) * moveDx;
                int extraZ = playerZ + (CLEAR_RADIUS + 1) * moveDz;
                newPositions.add(new Vector3i(extraX, y, extraZ));

                // Also add corners for diagonal movement
                if (moveDx != 0 && moveDz != 0) {
                    newPositions.add(new Vector3i(playerX + (CLEAR_RADIUS + 1) * moveDx, y, playerZ));
                    newPositions.add(new Vector3i(playerX, y, playerZ + (CLEAR_RADIUS + 1) * moveDz));
                }
            }
        }

        // Restore blocks no longer in the clear zone
        for (Vector3i pos : previousPositions) {
            if (!newPositions.contains(pos)) {
                Integer originalBlockId = originalBlocks.remove(pos);
                if (originalBlockId != null) {
                    setBlock(world, pos.getX(), pos.getY(), pos.getZ(), originalBlockId);
                }
            }
        }

        // Clear new blocks in the zone
        int replaceId = getTransparentBlockId();
        for (Vector3i pos : newPositions) {
            if (!originalBlocks.containsKey(pos)) {
                int currentBlockId = getBlock(world, pos.getX(), pos.getY(), pos.getZ());
                if (currentBlockId != AIR_BLOCK_ID && currentBlockId != replaceId) {
                    originalBlocks.put(pos, currentBlockId);
                    setBlock(world, pos.getX(), pos.getY(), pos.getZ(), replaceId);
                }
            }
        }
    }

    /**
     * Updates the predominant movement direction for a player.
     */
    private void updateMoveDirection(@Nonnull UUID uuid, @Nonnull Vector3d currentPos) {
        Vector3d lastPos = playerLastPosition.get(uuid);
        playerLastPosition.put(uuid, new Vector3d(currentPos.x, currentPos.y, currentPos.z));

        if (lastPos == null) return;

        double dx = currentPos.x - lastPos.x;
        double dz = currentPos.z - lastPos.z;

        // Only update if there's meaningful movement
        if (Math.abs(dx) < 0.1 && Math.abs(dz) < 0.1) return;

        // Determine predominant direction
        int moveDx = 0;
        int moveDz = 0;
        if (Math.abs(dx) > Math.abs(dz)) {
            moveDx = dx > 0 ? 1 : -1;
        } else {
            moveDz = dz > 0 ? 1 : -1;
        }
        playerMoveDirection.put(uuid, new int[]{moveDx, moveDz});
    }

    private void restoreAllBlocks(@Nonnull UUID uuid, @Nonnull World world) {
        Map<Vector3i, Integer> originalBlocks = playerOriginalBlocks.get(uuid);
        if (originalBlocks == null) return;

        for (Map.Entry<Vector3i, Integer> entry : originalBlocks.entrySet()) {
            Vector3i pos = entry.getKey();
            setBlock(world, pos.getX(), pos.getY(), pos.getZ(), entry.getValue());
        }
        originalBlocks.clear();
    }

    // ── Block access helpers ─────────────────────────────────────────────

    private static int getBlock(@Nonnull World world, int x, int y, int z) {
        WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null) return AIR_BLOCK_ID;
        return chunk.getBlock(x, y, z);
    }

    private static void setBlock(@Nonnull World world, int x, int y, int z, int blockId) {
        WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk != null) {
            chunk.setBlock(x, y, z, blockId);
        }
    }
}
