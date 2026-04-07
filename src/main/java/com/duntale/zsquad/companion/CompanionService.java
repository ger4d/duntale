package com.duntale.zsquad.companion;

import com.duntale.zsquad.progression.CombatScaling;
import com.duntale.zsquad.progression.CombatScalingComponent;
import com.duntale.zsquad.progression.ProgressionService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
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
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages player companion NPCs — spawning, dismissing, and tracking active companions.
 *
 * <p>Each player may have at most one active companion. Companions are world-scoped
 * and dismissed automatically on world leave ({@code RemovedPlayerFromWorldEvent}).
 * Companion entities are marked {@link NonSerialized} so they are never written to
 * chunk data — eliminating orphan entities entirely.
 *
 * @since 1.4.0
 */
public class CompanionService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double SPAWN_OFFSET_RANGE = 1.5;
    private static final String LEVEL_SCALE_MODIFIER_KEY = "ZSquad_LevelScale";

    public static final String DEFAULT_COMPANION_ROLE = "Companion_Skeleton_Soldier";

    private final Map<UUID, ActiveCompanion> activeCompanions = new ConcurrentHashMap<>();
    private final Map<UUID, String> companionPreferences = new ConcurrentHashMap<>();
    private final CompanionSpawner companionSpawner;
    private final ProgressionService progressionService;
    private final ComponentType<EntityStore, CompanionComponent> companionComponentType;
    private final CompanionRepository companionRepository;

    /**
     * Tracks an active companion bound to a player.
     *
     * @param npcRef   the companion NPC entity reference
     * @param flockRef the flock entity reference binding player and companion
     * @param world    the world the companion was spawned in
     */
    public record ActiveCompanion(
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull Ref<EntityStore> flockRef,
            @Nonnull World world
    ) {}

    /**
     * Creates a new companion service.
     *
     * @param companionSpawner       the companion NPC spawner
     * @param progressionService     the progression service for player levels
     * @param companionComponentType the registered companion component type
     * @param companionRepository    the companion preference repository
     */
    public CompanionService(
            @Nonnull CompanionSpawner companionSpawner,
            @Nonnull ProgressionService progressionService,
            @Nonnull ComponentType<EntityStore, CompanionComponent> companionComponentType,
            @Nonnull CompanionRepository companionRepository
    ) {
        this.companionSpawner = companionSpawner;
        this.progressionService = progressionService;
        this.companionComponentType = companionComponentType;
        this.companionRepository = companionRepository;
    }

    /**
     * Spawns a companion NPC for the given player.
     *
     * <p>The companion's level is derived from the player's progression level.
     * A flock is created with the player as leader and the companion as follower.
     * The companion entity is marked {@link NonSerialized} so it is never written
     * to chunk data.
     *
     * <p>The companion spawns near the player's current position. Use
     * {@link #summon(Store, Ref, UUID, String, Vector3d)} to override the spawn origin.
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
        return summon(store, playerRef, playerId, roleName, null);
    }

    /**
     * Spawns a companion NPC for the given player at an explicit spawn origin.
     *
     * <p>When {@code spawnOrigin} is non-null it is used as the base position for
     * companion placement (with the usual random XZ offset). When it is {@code null}
     * the player's current {@link TransformComponent} position is used instead.
     *
     * <p>Providing an authoritative origin avoids relying on the player's runtime
     * transform, which may not yet reflect the intended position after a cross-world
     * teleport (e.g. the dungeon entrance coordinate from instance metadata).
     *
     * @param store       the entity store (must be on WorldThread)
     * @param playerRef   the player's entity reference
     * @param playerId    the player's UUID
     * @param roleName    the NPC role name to spawn
     * @param spawnOrigin explicit spawn base position, or {@code null} to use the player's transform
     * @return the active companion data, or {@code null} if spawning failed or player already has one
     */
    @Nullable
    public ActiveCompanion summon(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull UUID playerId,
            @Nonnull String roleName,
            @Nullable Vector3d spawnOrigin
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

        // Resolve spawn origin
        Vector3d basePos;
        if (spawnOrigin != null) {
            basePos = spawnOrigin;
        } else {
            TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
            if (transform == null) {
                LOGGER.atWarning().log("Cannot summon companion — player has no transform");
                return null;
            }
            basePos = transform.getPosition();
        }
        double offsetX = (ThreadLocalRandom.current().nextDouble() - 0.5) * 2.0 * SPAWN_OFFSET_RANGE;
        double offsetZ = (ThreadLocalRandom.current().nextDouble() - 0.5) * 2.0 * SPAWN_OFFSET_RANGE;
        Vector3d spawnPos = new Vector3d(
                basePos.x + offsetX,
                basePos.y,
                basePos.z + offsetZ
        );

        // Spawn the companion with level scaling
        Pair<Ref<EntityStore>, NPCEntity> spawnResult = companionSpawner.spawn(store, roleName, spawnPos, level);
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

        // Mark as non-serialized — companion entities are never written to chunk data
        store.putComponent(npcRef, EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());

        // Create flock using the NPC's role for proper allowed-roles config
        NPCEntity npcEntity = spawnResult.second();
        Ref<EntityStore> flockRef = FlockPlugin.createFlock(store, npcEntity.getRole());
        FlockMembershipSystems.join(playerRef, flockRef, store);
        FlockMembershipSystems.join(npcRef, flockRef, store);

        // Get the world for tracking
        World world = store.getExternalData().getWorld();

        ActiveCompanion companion = new ActiveCompanion(npcRef, flockRef, world);
        activeCompanions.put(playerId, companion);

        LOGGER.atInfo().log("Spawned companion %s Lv.%d for player %s", roleName, level, playerId);
        return companion;
    }

    /**
     * Saves the player's companion role preference (cache + DB).
     *
     * <p>Should only be called when the player explicitly chooses a companion,
     * not during auto-spawn — to avoid overwriting a stored preference with a
     * fallback default on DB read errors.
     *
     * @param playerId the player's UUID
     * @param roleName the NPC role name to persist
     */
    public void persistPreference(@Nonnull UUID playerId, @Nonnull String roleName) {
        savePreference(playerId, roleName);
    }

    /**
     * Auto-spawns a companion for the player using their stored preference.
     *
     * <p>Must be called on the WorldThread. Reads the preference from cache or DB
     * synchronously, then spawns the companion immediately.
     *
     * @param store     the entity store (must be on WorldThread)
     * @param playerRef the player's entity reference
     * @param playerId  the player's UUID
     */
    public void spawn(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull UUID playerId
    ) {
        spawn(store, playerRef, playerId, null);
    }

    /**
     * Auto-spawns a companion for the player using their stored preference.
     *
     * <p>Must be called on the WorldThread. Reads the preference from cache or DB
     * synchronously, then spawns the companion immediately.
     *
     * @param store       the entity store (must be on WorldThread)
     * @param playerRef   the player's entity reference
     * @param playerId    the player's UUID
     * @param spawnOrigin explicit spawn base position, or {@code null} to use the player's transform
     */
    public void spawn(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull UUID playerId,
            @Nullable Vector3d spawnOrigin
    ) {
        cleanIfInvalid(playerId);
        if (hasCompanion(playerId)) return;

        String roleName = loadPreference(playerId);
        summon(store, playerRef, playerId, roleName, spawnOrigin);
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
        // from non-world threads (e.g., RemovedPlayerFromWorldEvent).
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (npcRef.isValid()) {
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

        // Remove the companion NPC entity (CombatScalingComponent dies with it)
        Ref<EntityStore> npcRef = companion.npcRef();
        if (npcRef.isValid()) {
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
     * Updates a companion's scaling when its owner levels up.
     *
     * @param store    the entity store (must be on WorldThread)
     * @param playerId the player's UUID
     * @param newLevel the player's new level
     */
    public void onPlayerLevelUp(@Nonnull Store<EntityStore> store, @Nonnull UUID playerId, int newLevel) {
        ActiveCompanion active = activeCompanions.get(playerId);
        if (active == null || !active.npcRef().isValid()) return;

        Ref<EntityStore> npcRef = active.npcRef();

        // Update CombatScalingComponent
        float newMult = CombatScaling.companionDamageMult(newLevel);
        store.putComponent(npcRef, CombatScalingComponent.getComponentType(),
                new CombatScalingComponent(newLevel, newMult, true));

        // Re-apply HP scaling
        NPCEntity npcEntity = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npcEntity == null) return;
        Role role = npcEntity.getRole();
        int baseHp = role != null ? role.getInitialMaxHealth() : 20;
        int targetHp = CombatScaling.companionScaledHp(baseHp, newLevel);

        int initialMaxHealth = role != null ? role.getInitialMaxHealth() : 20;
        if (targetHp > initialMaxHealth) {
            EntityStatMap statMap = store.getComponent(npcRef, EntityStatMap.getComponentType());
            if (statMap != null) {
                int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
                float delta = targetHp - initialMaxHealth;
                StaticModifier modifier = new StaticModifier(
                        Modifier.ModifierTarget.MAX,
                        StaticModifier.CalculationType.ADDITIVE,
                        delta
                );
                statMap.putModifier(healthIndex, LEVEL_SCALE_MODIFIER_KEY, modifier);
                statMap.maximizeStatValue(healthIndex);
            }
        }

        LOGGER.atInfo().log("Updated companion scaling for player %s to Lv.%d", playerId, newLevel);
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
        activeCompanions.put(playerId, new ActiveCompanion(npcRef, flockRef, existing.world()));

        LOGGER.atInfo().log("Reconnected companion flock for player %s", playerId);
    }

    /**
     * Loads the companion role preference for the player.
     * Checks the in-memory cache first, falls back to the database.
     *
     * @return the stored role name, or {@link #DEFAULT_COMPANION_ROLE} if none exists or on DB error
     */
    @Nonnull
    private String loadPreference(@Nonnull UUID playerId) {
        String cached = companionPreferences.get(playerId);
        if (cached != null) {
            return cached;
        }

        try {
            String dbValue = companionRepository.getPreference(playerId);
            String roleName = dbValue != null ? dbValue : DEFAULT_COMPANION_ROLE;
            companionPreferences.put(playerId, roleName);
            return roleName;
        } catch (SQLException e) {
            LOGGER.atWarning().log("Failed to load companion preference for %s: %s", playerId, e.getMessage());
            return DEFAULT_COMPANION_ROLE;
        }
    }

    /**
     * Stores the companion role preference for the player.
     * Updates the in-memory cache and writes to the database synchronously.
     */
    private void savePreference(@Nonnull UUID playerId, @Nonnull String roleName) {
        companionPreferences.put(playerId, roleName);
        try {
            companionRepository.setPreference(playerId, roleName);
        } catch (SQLException e) {
            LOGGER.atWarning().log("Failed to save companion preference for %s: %s", playerId, e.getMessage());
        }
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
