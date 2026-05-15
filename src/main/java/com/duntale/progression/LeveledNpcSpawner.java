package com.duntale.progression;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Spawns enemy NPCs with scaled stats for a given dungeon level.
 *
 * <p>Uses {@link NPCPlugin#spawnEntity} with pre/post callbacks to:
 * <ol>
 *   <li>Compute HP and damage multiplier via {@link CombatScaling}</li>
 *   <li>Apply an HP modifier via {@link EntityStatMap#putModifier}</li>
 *   <li>Attach a {@link CombatScalingComponent} for damage-time lookups</li>
 *   <li>Set a display name with level prefix and variant indicator</li>
 *   <li>Optionally scale elite NPCs visually</li>
 * </ol>
 */
public class LeveledNpcSpawner {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final NpcScalingApplicator scalingApplicator;

    /**
     * Creates a new spawner.
     *
     * @param scalingApplicator the shared NPC scaling applicator
     */
    public LeveledNpcSpawner(@Nonnull NpcScalingApplicator scalingApplicator) {
        this.scalingApplicator = Objects.requireNonNull(scalingApplicator, "scalingApplicator");
    }

    /**
     * Spawns a leveled enemy NPC at the given position.
     *
     * @param store    the entity store
     * @param roleName the NPC role name (e.g. "Zombie")
     * @param position the spawn position
     * @param level    the dungeon level (1-60)
     * @param variant  the NPC variant (NORMAL, ELITE, or BOSS)
     * @return the spawned entity pair, or {@code null} if spawning failed
     */
    @Nullable
    public Pair<Ref<EntityStore>, NPCEntity> spawn(
            @Nonnull Store<EntityStore> store,
            @Nonnull String roleName,
            @Nonnull Vector3d position,
            int level,
            @Nonnull CombatScaling.NpcVariant variant
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            LOGGER.at(Level.WARNING).log("NPCPlugin not available — cannot spawn %s", roleName);
            return null;
        }

        int roleIndex = npcPlugin.getIndex(roleName);
        if (roleIndex < 0) {
            LOGGER.at(Level.WARNING).log("Unknown NPC role: %s", roleName);
            return null;
        }

        NpcScalingProfile profile = scalingApplicator.createProfile(roleName, level, variant);

        return npcPlugin.spawnEntity(store, roleIndex, position, null, null,
                (npcEntity, holder, s) -> {
                    applyPreAdd(holder, profile);
                },
                (npcEntity, ref, s) -> {
                    applyPostSpawn(npcEntity, ref, s, profile);
                });
    }

    private void applyPreAdd(@Nonnull Holder<EntityStore> holder, @Nonnull NpcScalingProfile profile) {
        scalingApplicator.applyToHolder(holder, profile);
    }

    private void applyPostSpawn(
            @Nonnull NPCEntity npcEntity,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull NpcScalingProfile profile
    ) {
        scalingApplicator.applyHealthToSpawnedNpc(npcEntity, ref, store, profile);
    }
}
