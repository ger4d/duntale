package com.duntale.companion;

import com.duntale.progression.CombatScaling;
import com.duntale.progression.CombatScalingComponent;
import com.duntale.progression.ProgressionService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

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
    private static final String LEVEL_SCALE_MODIFIER_KEY = "Duntale_LevelScale";

    public static final String DEFAULT_COMPANION_ROLE = CompanionSpawner.WOLF_BLACK_ROLE;

    private final Map<UUID, ActiveCompanion> activeCompanions = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveCompanion> pendingDismissals = new ConcurrentHashMap<>();
    private final Map<UUID, CompanionPreference> companionPreferences = new ConcurrentHashMap<>();
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
        return summon(store, playerRef, playerId, roleName, null, null);
    }

    /**
     * Spawns a companion NPC for the given player with an explicit display name.
     *
     * @param store the entity store (must be on WorldThread)
     * @param playerRef the player's entity reference
     * @param playerId the player's UUID
     * @param roleName the NPC role name to spawn
     * @param displayName the optional companion nameplate override
     * @return the active companion data, or {@code null} if spawning failed or player already has one
     */
    @Nullable
    public ActiveCompanion summon(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull UUID playerId,
            @Nonnull String roleName,
            @Nullable String displayName
    ) {
        return summon(store, playerRef, playerId, roleName, displayName, null);
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
        return summon(store, playerRef, playerId, roleName, null, spawnOrigin);
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
     * @param store the entity store (must be on WorldThread)
     * @param playerRef the player's entity reference
     * @param playerId the player's UUID
     * @param roleName the NPC role name to spawn
     * @param displayName the optional companion nameplate override
     * @param spawnOrigin explicit spawn base position, or {@code null} to use the player's transform
     * @return the active companion data, or {@code null} if spawning failed or player already has one
     */
    @Nullable
    public ActiveCompanion summon(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> playerRef,
        @Nonnull UUID playerId,
        @Nonnull String roleName,
        @Nullable String displayName,
        @Nullable Vector3d spawnOrigin
    ) {
        // Auto-clean stale entries
        prepareForSpawn(store, playerId);
        cleanIfInvalid(playerId);
        removeUntrackedCompanions(store, playerId);

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
        Pair<Ref<EntityStore>, NPCEntity> spawnResult = companionSpawner.spawn(
            store,
            roleName,
            displayName,
            spawnPos,
            level
        );
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
        savePreference(playerId, new CompanionPreference(roleName, null));
    }

    /**
     * Saves the player's companion preference (cache + DB).
     *
     * @param playerId the player's UUID
     * @param roleName the NPC role name to persist
     * @param displayName the optional companion display name to persist
     */
    public boolean persistPreference(
            @Nonnull UUID playerId,
            @Nonnull String roleName,
            @Nullable String displayName
    ) {
        return savePreference(playerId, new CompanionPreference(roleName, displayName));
    }

    /**
     * Returns whether the player has any stored companion preference row.
     *
     * @param playerId the player's UUID
     * @return {@code true} when a stored preference exists
     * @throws SQLException if the database lookup fails
     */
    public boolean hasStoredPreference(@Nonnull UUID playerId) throws SQLException {
        return companionRepository.hasPreference(playerId);
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
        if (store != null) {
            prepareForSpawn(store, playerId);
        }
        cleanIfInvalid(playerId);
        if (store != null) {
            removeUntrackedCompanions(store, playerId);
        }
        if (hasCompanion(playerId)) return;

        CompanionPreference preference = loadPreference(playerId);
        summon(store, playerRef, playerId, preference.roleName(), preference.displayName(), spawnOrigin);
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
        ActiveCompanion companion = activeCompanions.get(playerId);
        if (companion == null) {
            return false;
        }

        dismissOnWorld(companion.world(), null, playerId, companion);

        LOGGER.atInfo().log("Dismissed companion for player %s", playerId);
        return true;
    }

    /**
     * Dismisses a player's companion and cleans orphan companions in the source world.
     *
     * <p>If the store is currently inside ECS processing, entity removal is queued while
     * the active companion remains tracked. This prevents a ready event from spawning a
     * duplicate before cleanup executes.
     *
     * @param sourceStore the source world store the player is leaving
     * @param playerId the player's UUID
     * @return {@code true} if a tracked companion was dismissed, {@code false} if only orphan cleanup ran
     */
    public boolean dismissFromWorld(@Nonnull Store<EntityStore> sourceStore, @Nonnull UUID playerId) {
        ActiveCompanion companion = activeCompanions.get(playerId);
        World sourceWorld = sourceStore.getExternalData().getWorld();
        if (companion == null) {
            runOrphanCleanup(sourceWorld, playerId, null);
            return false;
        }

        if (companion.world() == sourceWorld) {
            dismissOnWorld(sourceWorld, null, playerId, companion);
        } else {
            dismissOnWorld(companion.world(), null, playerId, companion);
            runOrphanCleanup(sourceWorld, playerId, null);
        }
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

        ActiveCompanion companion = activeCompanions.get(playerId);
        if (companion == null) {
            removeUntrackedCompanions(store, playerId);
            return false;
        }

        dismissOnStore(store, playerRef, playerId, companion);

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
        ActiveCompanion companion = activeCompanions.get(playerId);
        return companion != null && pendingDismissals.get(playerId) != companion;
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
    private CompanionPreference loadPreference(@Nonnull UUID playerId) {
        CompanionPreference cached = companionPreferences.get(playerId);
        if (cached != null) {
            return cached;
        }

        try {
            CompanionPreference dbValue = companionRepository.getProfile(playerId);
            if (dbValue != null) {
                companionPreferences.put(playerId, dbValue);
                return dbValue;
            }
            return new CompanionPreference(DEFAULT_COMPANION_ROLE, null);
        } catch (SQLException e) {
            LOGGER.atWarning().log("Failed to load companion preference for %s: %s", playerId, e.getMessage());
            return new CompanionPreference(DEFAULT_COMPANION_ROLE, null);
        }
    }

    /**
     * Stores the companion role preference for the player.
     * Updates the in-memory cache and writes to the database synchronously.
     */
    private boolean savePreference(@Nonnull UUID playerId, @Nonnull CompanionPreference preference) {
        companionPreferences.put(playerId, preference);
        try {
            companionRepository.setPreference(
                    playerId,
                    preference.roleName(),
                    preference.displayName()
            );
            return true;
        } catch (SQLException e) {
            LOGGER.atWarning().log("Failed to save companion preference for %s: %s", playerId, e.getMessage());
            return false;
        }
    }

    /**
     * Auto-cleans a stale companion entry if the NPC ref is no longer valid.
     */
    private void cleanIfInvalid(@Nonnull UUID playerId) {
        ActiveCompanion companion = activeCompanions.get(playerId);
        if (companion != null && !companion.npcRef().isValid()) {
            activeCompanions.remove(playerId);
            pendingDismissals.remove(playerId, companion);
            LOGGER.atWarning().log("Auto-cleaned stale companion entry for player %s", playerId);
        }
    }

    private void dismissOnWorld(
            @Nonnull World world,
            @Nullable Ref<EntityStore> playerRef,
            @Nonnull UUID playerId,
            @Nonnull ActiveCompanion companion
    ) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (canMutateStoreNow(store)) {
            dismissNow(store, playerRef, playerId, companion);
            return;
        }

        pendingDismissals.put(playerId, companion);
        world.execute(() -> dismissNow(world.getEntityStore().getStore(), playerRef, playerId, companion));
    }

    private void dismissOnStore(
            @Nonnull Store<EntityStore> store,
            @Nullable Ref<EntityStore> playerRef,
            @Nonnull UUID playerId,
            @Nonnull ActiveCompanion companion
    ) {
        World sourceWorld = store.getExternalData().getWorld();
        if (companion.world() != sourceWorld) {
            dismissOnWorld(companion.world(), null, playerId, companion);
            runOrphanCleanup(sourceWorld, playerId, null);
            return;
        }

        if (canMutateStoreNow(store)) {
            dismissNow(store, playerRef, playerId, companion);
            return;
        }

        pendingDismissals.put(playerId, companion);
        sourceWorld.execute(() -> dismissNow(sourceWorld.getEntityStore().getStore(), playerRef, playerId, companion));
    }

    private void dismissNow(
            @Nonnull Store<EntityStore> store,
            @Nullable Ref<EntityStore> playerRef,
            @Nonnull UUID playerId,
            @Nonnull ActiveCompanion companion
    ) {
        ActiveCompanion current = activeCompanions.get(playerId);
        Ref<EntityStore> keepRef = current != null && current != companion ? current.npcRef() : null;

        if (playerRef != null && playerRef.isValid() && playerRef.getStore() == store) {
            store.tryRemoveComponent(playerRef, FlockMembership.getComponentType());
        }

        Ref<EntityStore> npcRef = companion.npcRef();
        if (npcRef.isValid() && npcRef.getStore() == store) {
            store.removeEntity(npcRef, RemoveReason.REMOVE);
        }

        Ref<EntityStore> flockRef = companion.flockRef();
        if (flockRef.isValid() && flockRef.getStore() == store) {
            store.removeEntity(flockRef, RemoveReason.REMOVE);
        }

        activeCompanions.remove(playerId, companion);
        pendingDismissals.remove(playerId, companion);
        removeOwnedCompanions(store, playerId, keepRef);
    }

    private void runOrphanCleanup(
            @Nonnull World world,
            @Nonnull UUID playerId,
            @Nullable Ref<EntityStore> keepRef
    ) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (canMutateStoreNow(store)) {
            removeOwnedCompanions(store, playerId, resolveKeepRef(world, playerId, keepRef));
            return;
        }

        world.execute(() -> removeOwnedCompanions(
                world.getEntityStore().getStore(),
                playerId,
                resolveKeepRef(world, playerId, keepRef)
        ));
    }

    private void removeUntrackedCompanions(@Nonnull Store<EntityStore> store, @Nonnull UUID playerId) {
        ActiveCompanion current = activeCompanions.get(playerId);
        removeOwnedCompanions(store, playerId, current != null ? current.npcRef() : null);
    }

    private void prepareForSpawn(@Nonnull Store<EntityStore> store, @Nonnull UUID playerId) {
        ActiveCompanion pending = pendingDismissals.get(playerId);
        if (pending == null) {
            return;
        }

        World spawnWorld = store.getExternalData().getWorld();
        if (pending.world() == spawnWorld && canMutateStoreNow(store)) {
            dismissNow(store, null, playerId, pending);
            return;
        }

        activeCompanions.remove(playerId, pending);
    }

    @Nullable
    private Ref<EntityStore> resolveKeepRef(
            @Nonnull World world,
            @Nonnull UUID playerId,
            @Nullable Ref<EntityStore> explicitKeepRef
    ) {
        if (explicitKeepRef != null) {
            return explicitKeepRef;
        }

        ActiveCompanion current = activeCompanions.get(playerId);
        return current != null && current.world() == world ? current.npcRef() : null;
    }

    private int removeOwnedCompanions(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID playerId,
            @Nullable Ref<EntityStore> keepRef
    ) {
        if (companionComponentType == null || !canMutateStoreNow(store)) {
            return 0;
        }

        AtomicInteger removed = new AtomicInteger();
        BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>> cleanup =
            (chunk, commandBuffer) -> removeOwnedCompanions(chunk, commandBuffer, playerId, keepRef, removed);
        store.forEachChunk(Query.and(companionComponentType), cleanup);
        int count = removed.get();
        if (count > 0) {
            LOGGER.atWarning().log("Removed %d orphan companion entities for player %s", count, playerId);
        }
        return count;
    }

    private void removeOwnedCompanions(
            @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull UUID playerId,
            @Nullable Ref<EntityStore> keepRef,
            @Nonnull AtomicInteger removed
    ) {
        for (int index = 0; index < chunk.size(); index++) {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            if (keepRef != null && ref == keepRef) {
                continue;
            }

            CompanionComponent companion = chunk.getComponent(index, companionComponentType);
            if (companion != null && playerId.equals(companion.getOwnerUuid())) {
                commandBuffer.removeEntity(ref, RemoveReason.REMOVE);
                removed.incrementAndGet();
            }
        }
    }

    @SuppressWarnings("deprecation")
    private boolean canMutateStoreNow(@Nonnull Store<EntityStore> store) {
        return store.isInThread() && !store.isProcessing();
    }
}
