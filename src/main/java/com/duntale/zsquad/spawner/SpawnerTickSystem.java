package com.duntale.zsquad.spawner;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.model.SpawnEntry;
import com.duntale.zsquad.progression.LeveledNpcSpawner;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ECS system that drives spawner logic at ~3Hz (0.33s interval).
 *
 * <p>Checks {@link SpawnerState#DORMANT} spawners against cached player positions and
 * spawns enemies for {@link SpawnerState#ACTIVE} spawners using {@link LeveledNpcSpawner}.</p>
 *
 * @since 1.1.0
 */
public class SpawnerTickSystem extends DelayedEntitySystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final float INTERVAL = 0.33f;
    private static final int MAX_SPAWNS_PER_TICK = 10;

    /** Guard interval (ns) for refreshing the player position cache. */
    private static final long CACHE_REFRESH_NS = 100_000_000L; // 100ms

    private static final Query<EntityStore> QUERY = Query.and(
            SpawnerComponent.getComponentType(),
            TransformComponent.getComponentType()
    );

    private final LeveledNpcSpawner npcSpawner;

    // Cached per delayed-tick interval — populated lazily on first entity each cycle
    private long lastRefreshNano;
    private List<Vector3d> cachedPlayerPositions;
    private int spawnBudgetThisTick;

    /**
     * Creates a new spawner tick system.
     *
     * @param npcSpawner the leveled NPC spawner used to create enemy entities
     * @since 1.1.0
     */
    public SpawnerTickSystem(@Nonnull LeveledNpcSpawner npcSpawner) {
        super(INTERVAL);
        this.npcSpawner = npcSpawner;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        ensureCache(store);

        SpawnerComponent spawner = archetypeChunk.getComponent(index, SpawnerComponent.getComponentType());
        if (spawner == null) return;

        switch (spawner.getState()) {
            case DORMANT -> handleDormant(spawner, archetypeChunk, index, store);
            case ACTIVE -> handleActive(spawner, archetypeChunk, index, store);
            case DEPLETED -> spawner.pruneDeadNpcs();
            case DISABLED -> { /* no-op, will be removed by teardown */ }
        }
    }

    /**
     * Lazily refresh player position cache and per-tick spawn budget.
     * Uses a nano-time guard so the cache is populated at most once per delayed tick cycle.
     */
    private void ensureCache(@Nonnull Store<EntityStore> store) {
        long now = System.nanoTime();
        if (now - lastRefreshNano > CACHE_REFRESH_NS) {
            lastRefreshNano = now;
            spawnBudgetThisTick = MAX_SPAWNS_PER_TICK;

            World world = store.getExternalData().getWorld();
            Collection<PlayerRef> playerRefs = world.getPlayerRefs();
            cachedPlayerPositions = new ArrayList<>(playerRefs.size());

            for (PlayerRef pr : playerRefs) {
                Ref<EntityStore> ref = pr.getReference();
                if (ref != null && ref.isValid()) {
                    TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
                    if (tc != null) {
                        cachedPlayerPositions.add(tc.getPosition());
                    }
                }
            }
        }
    }

    private void handleDormant(@Nonnull SpawnerComponent spawner,
                               @Nonnull ArchetypeChunk<EntityStore> chunk,
                               int index,
                               @Nonnull Store<EntityStore> store) {
        if (cachedPlayerPositions == null || cachedPlayerPositions.isEmpty()) return;

        TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d spawnerPos = transform.getPosition();
        double activationRadius = spawner.getDefinition().trigger().activationRadius();
        double radiusSq = activationRadius * activationRadius;

        for (Vector3d playerPos : cachedPlayerPositions) {
            double dx = spawnerPos.x - playerPos.x;
            double dy = spawnerPos.y - playerPos.y;
            double dz = spawnerPos.z - playerPos.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= radiusSq) {
                spawner.activate();
                LOGGER.atInfo().log("[Spawner] Activated spawner #%d (room %d, budget %d)",
                        spawner.getDefinition().id(), spawner.getDefinition().roomId(),
                        spawner.getSpawnBudgetRemaining());
                return;
            }
        }
    }

    private void handleActive(@Nonnull SpawnerComponent spawner,
                              @Nonnull ArchetypeChunk<EntityStore> chunk,
                              int index,
                              @Nonnull Store<EntityStore> store) {
        // Prune dead NPCs first
        spawner.pruneDeadNpcs();

        if (spawner.getSpawnBudgetRemaining() <= 0) {
            spawner.setState(SpawnerState.DEPLETED);
            LOGGER.atInfo().log("[Spawner] Spawner #%d depleted (%d total spawned)",
                    spawner.getDefinition().id(), spawner.getSpawnedCount());
            return;
        }

        if (spawnBudgetThisTick <= 0) return; // global budget exhausted

        // Get the spawner's world position from its TransformComponent
        TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
        if (transform == null) return;
        Vector3d spawnerWorldPos = transform.getPosition();

        int toSpawn = Math.min(spawner.getSpawnBudgetRemaining(), spawnBudgetThisTick);
        List<SpawnEntry> pool = spawner.getDefinition().spawnPool();
        if (pool.isEmpty()) {
            spawner.setState(SpawnerState.DEPLETED);
            return;
        }

        // Pre-compute total weight for weighted selection
        double totalWeight = 0;
        for (SpawnEntry entry : pool) {
            totalWeight += entry.weight();
        }

        // Collect spawn requests synchronously, then batch into a single World.execute().
        // NPCPlugin.spawnEntity() calls store.addEntity() which is illegal during
        // system processing — must defer to outside tick systems.
        record SpawnRequest(String npcRole, Vector3d position, int level, boolean boss, int spawnerId) {}
        List<SpawnRequest> deferredSpawns = new ArrayList<>();

        // Level = floorLevel ± levelVariance, clamped to [1, 60]
        int floorLevel = spawner.getDefinition().floorLevel();
        int variance = spawner.getDefinition().levelVariance();
        int minLevel = Math.max(1, floorLevel - variance);
        int maxLevel = Math.min(60, floorLevel + variance);

        for (int i = 0; i < toSpawn; i++) {
            SpawnEntry picked = weightedPick(pool, totalWeight);
            if (picked == null) break;

            // Random level in [floorLevel - variance, floorLevel + variance], clamped
            int level = minLevel == maxLevel
                    ? minLevel
                    : ThreadLocalRandom.current().nextInt(minLevel, maxLevel + 1);

            // Get spawn position: spawner world pos + relative offset
            Vec3i offset = spawner.nextSpawnOffset();
            Vector3d spawnPos = new Vector3d(
                    spawnerWorldPos.x + offset.x(),
                    spawnerWorldPos.y + offset.y(),
                    spawnerWorldPos.z + offset.z());

            // Reserve budget synchronously
            spawner.reserveBudget();
            spawnBudgetThisTick--;

            deferredSpawns.add(new SpawnRequest(
                    picked.npcRole(), spawnPos, level,
                    spawner.getDefinition().isBoss(), spawner.getDefinition().id()));

            if (spawnBudgetThisTick <= 0) break;
        }

        if (!deferredSpawns.isEmpty()) {
            World world = store.getExternalData().getWorld();
            world.execute(() -> {
                Store<EntityStore> entityStore = world.getEntityStore().getStore();
                for (SpawnRequest req : deferredSpawns) {
                    Pair<Ref<EntityStore>, NPCEntity> result = npcSpawner.spawn(
                            entityStore, req.npcRole(), req.position(), req.level(), req.boss()
                    );

                    if (result != null) {
                        spawner.addAliveNpc(result.first());
                        LOGGER.atInfo().log("[Spawner] Spawned %s Lv.%d at (%.1f,%.1f,%.1f) for spawner #%d",
                                req.npcRole(), req.level(),
                                req.position().x, req.position().y, req.position().z, req.spawnerId());
                    } else {
                        LOGGER.atWarning().log("[Spawner] Failed to spawn %s Lv.%d at (%.1f,%.1f,%.1f) for spawner #%d",
                                req.npcRole(), req.level(),
                                req.position().x, req.position().y, req.position().z, req.spawnerId());
                    }
                }
            });
        }

        if (spawner.getSpawnBudgetRemaining() <= 0) {
            spawner.setState(SpawnerState.DEPLETED);
            LOGGER.atInfo().log("[Spawner] Spawner #%d depleted (%d total spawned)",
                    spawner.getDefinition().id(), spawner.getSpawnedCount());
        }
    }

    /**
     * Weighted random pick from the spawn pool.
     *
     * @param pool        the spawn entries
     * @param totalWeight pre-computed sum of weights
     * @return a randomly selected entry, or {@code null} if pool is empty
     */
    @Nullable
    private SpawnEntry weightedPick(@Nonnull List<SpawnEntry> pool, double totalWeight) {
        if (pool.isEmpty()) return null;
        double roll = ThreadLocalRandom.current().nextDouble() * totalWeight;
        double cumulative = 0;
        for (SpawnEntry entry : pool) {
            cumulative += entry.weight();
            if (roll <= cumulative) return entry;
        }
        return pool.getLast(); // fallback
    }
}
