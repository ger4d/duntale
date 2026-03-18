package com.duntale.zsquad.companion;

import com.duntale.zsquad.progression.LeveledNpcSpawner;
import com.duntale.zsquad.progression.NpcLevelRegistry;
import com.duntale.zsquad.progression.ProgressionService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.flock.FlockMembershipSystems;
import com.hypixel.hytale.server.flock.FlockPlugin;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages player companion NPCs — spawning, dismissing, and tracking active companions.
 *
 * <p>Each player may have at most one active companion. Companions are world-scoped
 * and dismissed automatically on disconnect or world change.
 *
 * @since 1.4.0
 */
public class CompanionService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double SPAWN_OFFSET_RANGE = 1.5;

    private final Map<UUID, ActiveCompanion> activeCompanions = new HashMap<>();
    private final LeveledNpcSpawner npcSpawner;
    private final ProgressionService progressionService;
    private final NpcLevelRegistry npcLevelRegistry;
    private final ComponentType<EntityStore, CompanionComponent> companionComponentType;

    /**
     * Tracks an active companion bound to a player.
     *
     * @param npcRef   the companion NPC entity reference
     * @param flockRef the flock entity reference binding player and companion
     * @param world    the world the companion was spawned in
     * @param roleName the NPC role name
     * @param level    the level the companion was spawned at
     */
    public record ActiveCompanion(
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull Ref<EntityStore> flockRef,
            @Nonnull World world,
            @Nonnull String roleName,
            int level
    ) {}

    /**
     * Creates a new companion service.
     *
     * @param npcSpawner           the leveled NPC spawner
     * @param progressionService   the progression service for player levels
     * @param npcLevelRegistry     the NPC level registry for cleanup
     * @param companionComponentType the registered companion component type
     */
    public CompanionService(
            @Nonnull LeveledNpcSpawner npcSpawner,
            @Nonnull ProgressionService progressionService,
            @Nonnull NpcLevelRegistry npcLevelRegistry,
            @Nonnull ComponentType<EntityStore, CompanionComponent> companionComponentType
    ) {
        this.npcSpawner = npcSpawner;
        this.progressionService = progressionService;
        this.npcLevelRegistry = npcLevelRegistry;
        this.companionComponentType = companionComponentType;
    }

    /**
     * Spawns a companion NPC for the given player.
     *
     * <p>The companion's level is derived from the player's progression level.
     * A flock is created with the player as leader and the companion as follower.
     *
     * @param store     the entity store (must be on WorldThread)
     * @param playerRef the player's entity reference
     * @param playerId  the player's UUID
     * @param roleName  the NPC role name to spawn
     * @return the active companion data, or {@code null} if spawning failed or player already has one
     */
    @Nullable
    public ActiveCompanion summon(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull UUID playerId,
            @Nonnull String roleName
    ) {
        // Auto-clean stale entries
        cleanIfInvalid(playerId);

        if (hasCompanion(playerId)) {
            return null;
        }

        // Validate role exists
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null || npcPlugin.getIndex(roleName) < 0) {
            LOGGER.atWarning().log("Cannot summon companion — unknown role: %s", roleName);
            return null;
        }

        // Get player level
        int level = Math.max(1, progressionService.getLevel(playerId));

        // Get spawn position near the player
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            LOGGER.atWarning().log("Cannot summon companion — player has no transform");
            return null;
        }
        Vector3d playerPos = transform.getPosition();
        double offsetX = (ThreadLocalRandom.current().nextDouble() - 0.5) * 2.0 * SPAWN_OFFSET_RANGE;
        double offsetZ = (ThreadLocalRandom.current().nextDouble() - 0.5) * 2.0 * SPAWN_OFFSET_RANGE;
        Vector3d spawnPos = new Vector3d(
                playerPos.getX() + offsetX,
                playerPos.getY(),
                playerPos.getZ() + offsetZ
        );

        // Spawn the NPC with level scaling
        Pair<Ref<EntityStore>, NPCEntity> spawnResult = npcSpawner.spawn(store, roleName, spawnPos, level, false);
        if (spawnResult == null) {
            LOGGER.atWarning().log("Failed to spawn companion NPC: %s at level %d", roleName, level);
            return null;
        }

        Ref<EntityStore> npcRef = spawnResult.first();
        if (npcRef == null || !npcRef.isValid()) {
            return null;
        }

        // Attach CompanionComponent to the NPC
        store.putComponent(npcRef, companionComponentType,
                new CompanionComponent(playerId, roleName, level));

        // Create flock using the NPC's role for proper allowed-roles config
        NPCEntity npcEntity = spawnResult.second();
        Ref<EntityStore> flockRef = FlockPlugin.createFlock(store, npcEntity.getRole());
        FlockMembershipSystems.join(playerRef, flockRef, store);
        FlockMembershipSystems.join(npcRef, flockRef, store);

        // Get the world for tracking
        World world = store.getExternalData().getWorld();

        ActiveCompanion companion = new ActiveCompanion(npcRef, flockRef, world, roleName, level);
        activeCompanions.put(playerId, companion);

        LOGGER.atInfo().log("Spawned companion %s Lv.%d for player %s", roleName, level, playerId);
        return companion;
    }

    /**
     * Dismisses a player's active companion, removing it from the world.
     *
     * <p>Safe to call from any thread — store operations are dispatched to the WorldThread.
     *
     * @param playerId the player's UUID
     * @return {@code true} if a companion was dismissed, {@code false} if none existed
     */
    public boolean dismiss(@Nonnull UUID playerId) {
        ActiveCompanion companion = activeCompanions.remove(playerId);
        if (companion == null) {
            return false;
        }

        World world = companion.world();
        Ref<EntityStore> npcRef = companion.npcRef();

        // Dispatch store operations to WorldThread — this method may be called
        // from non-world threads (e.g., PlayerDisconnectEvent on ServerWorkerGroup).
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (npcRef.isValid()) {
                UUIDComponent uuidComp = store.getComponent(npcRef, UUIDComponent.getComponentType());
                if (uuidComp != null) {
                    npcLevelRegistry.remove(uuidComp.getUuid());
                }
                store.removeEntity(npcRef, RemoveReason.REMOVE);
            }
        });

        LOGGER.atInfo().log("Dismissed companion for player %s", playerId);
        return true;
    }

    /**
     * Dismisses a player's active companion using a specific store reference.
     * Used when the player's entity reference is available (e.g., from a command context).
     *
     * @param store     the entity store (must be on WorldThread)
     * @param playerRef the player's entity reference
     * @param playerId  the player's UUID
     * @return {@code true} if a companion was dismissed
     */
    public boolean dismiss(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef,
                           @Nonnull UUID playerId) {

        LOGGER.atInfo().log("Attempting to dismiss companion for player %s", playerId);

        ActiveCompanion companion = activeCompanions.remove(playerId);
        if (companion == null) {
            return false;
        }

        // Remove player from flock
        store.tryRemoveComponent(playerRef, FlockMembership.getComponentType());

        // Remove the companion NPC entity
        Ref<EntityStore> npcRef = companion.npcRef();
        if (npcRef.isValid()) {
            UUIDComponent uuidComp = store.getComponent(npcRef, UUIDComponent.getComponentType());
            if (uuidComp != null) {
                npcLevelRegistry.remove(uuidComp.getUuid());
            }
            store.removeEntity(npcRef, RemoveReason.REMOVE);
        }

        LOGGER.atInfo().log("Dismissed companion for player %s", playerId);
        return true;
    }

    /**
     * Checks whether a player has an active companion.
     * Auto-cleans stale entries where the NPC ref has become invalid (e.g., companion died).
     *
     * @param playerId the player's UUID
     * @return {@code true} if the player has a living companion
     */
    public boolean hasCompanion(@Nonnull UUID playerId) {
        cleanIfInvalid(playerId);
        return activeCompanions.containsKey(playerId);
    }

    /**
     * Returns the active companion for a player, or {@code null} if none exists.
     *
     * @param playerId the player's UUID
     * @return the active companion data, or {@code null}
     */
    @Nullable
    public ActiveCompanion getActiveCompanion(@Nonnull UUID playerId) {
        cleanIfInvalid(playerId);
        return activeCompanions.get(playerId);
    }

    /**
     * Reconnects a player's companion flock after flock dissolution (e.g., player death/respawn
     * or same-world re-join). Requires an existing {@code activeCompanions} entry.
     *
     * <p>No-op if the player has no tracked companion, the companion NPC ref is invalid,
     * or the player and companion are already in the same flock.
     *
     * @param store     the entity store (must be on WorldThread)
     * @param playerRef the player's entity reference
     * @param playerId  the player's UUID
     */
    public void reconnect(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull UUID playerId
    ) {
        ActiveCompanion existing = activeCompanions.get(playerId);
        if (existing == null) { 
            LOGGER.atWarning().log("No existing companion to reconnect for player %s", playerId);
            return;
        }

        Ref<EntityStore> npcRef = existing.npcRef();
        if (!npcRef.isValid()) {
            LOGGER.atWarning().log("Unable to reconnect to NPC Entity (no longer valid)");
            activeCompanions.remove(playerId);
            return;
        }

        // Already in same flock? No-op.
        FlockMembership npcMembership = store.getComponent(npcRef, FlockMembership.getComponentType());
        FlockMembership playerMembership = store.getComponent(playerRef, FlockMembership.getComponentType());
        if (npcMembership != null && playerMembership != null
                && npcMembership.getFlockId().equals(playerMembership.getFlockId())) {
            LOGGER.atInfo().log("Player and companion are already in the same flock - no reconnect needed");
            return;
        }

        // Verify companion still belongs to this player
        CompanionComponent comp = store.getComponent(npcRef, companionComponentType);
        if (comp == null || !playerId.equals(comp.getOwnerUuid())) {
            LOGGER.atWarning().log("Companion NPC no longer has a valid CompanionComponent - removing from tracking");
            activeCompanions.remove(playerId);
            return;
        }

        // Create new flock
        NPCEntity npcEntity = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npcEntity == null || npcEntity.getRole() == null) {
            LOGGER.atWarning().log("Companion NPC no longer has a valid NPCEntity or role - removing from tracking");
            activeCompanions.remove(playerId);
            return;
        }

        Ref<EntityStore> flockRef = FlockPlugin.createFlock(store, npcEntity.getRole());
        FlockMembershipSystems.join(playerRef, flockRef, store);
        FlockMembershipSystems.join(npcRef, flockRef, store);

        // Update cache
        activeCompanions.put(playerId, new ActiveCompanion(
                npcRef, flockRef, existing.world(), existing.roleName(), existing.level()));

        LOGGER.atInfo().log("Reconnected companion %s for player %s", existing.roleName(), playerId);
    }

    /**
     * Rebuilds companion tracking and flock from live entity data, used when no
     * {@code activeCompanions} entry exists (e.g., server restart, state desync).
     *
     * <p>If an entry already exists, delegates to {@link #reconnect(Store, Ref, UUID)}.
     *
     * @param store        the entity store (must be on WorldThread)
     * @param companionRef the companion NPC's entity reference
     * @param playerRef    the owner player's entity reference
     * @param comp         the companion component read from the NPC entity
     */
    public void reconnectFromEntity(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull CompanionComponent comp
    ) {
        UUID playerId = comp.getOwnerUuid();

        // Already in same flock? Just rebuild tracking, don't create new flock.
        FlockMembership npcMembership = store.getComponent(companionRef, FlockMembership.getComponentType());
        FlockMembership playerMembership = store.getComponent(playerRef, FlockMembership.getComponentType());

        Ref<EntityStore> flockRef;
        if (npcMembership != null && playerMembership != null
                && npcMembership.getFlockId().equals(playerMembership.getFlockId())) {
            flockRef = npcMembership.getFlockRef();
        } else {
            // Create new flock
            NPCEntity npcEntity = store.getComponent(companionRef, NPCEntity.getComponentType());
            if (npcEntity == null || npcEntity.getRole() == null) {
                LOGGER.atWarning().log("Companion NPC no longer has a valid NPCEntity or role - cannot reconnect");
                activeCompanions.remove(playerId);
                return;
            }

            flockRef = FlockPlugin.createFlock(store, npcEntity.getRole());
            FlockMembershipSystems.join(playerRef, flockRef, store);
            FlockMembershipSystems.join(companionRef, flockRef, store);
        }

        // Build cache entry from entity data
        World world = store.getExternalData().getWorld();
        activeCompanions.put(playerId, new ActiveCompanion(
                companionRef, flockRef, world, comp.getRoleName(), comp.getLevel()));

        LOGGER.atInfo().log("Recovered companion %s for player %s from entity data",
                comp.getRoleName(), playerId);
    }

    /**
     * Auto-cleans a stale companion entry if the NPC ref is no longer valid.
     */
    private void cleanIfInvalid(@Nonnull UUID playerId) {
        ActiveCompanion companion = activeCompanions.get(playerId);
        if (companion != null && !companion.npcRef().isValid()) {
            activeCompanions.remove(playerId);
            LOGGER.atWarning().log("Auto-cleaned stale companion entry for player %s", playerId);
        }
    }
}
