package com.duntale.zsquad.spawner;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.model.DungeonBlueprint;
import com.duntale.dungeongen.model.SpawnerDefinition;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3d;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates spawner entities in the ECS from a {@link DungeonBlueprint}.
 * Called after world assembly to populate the dungeon with spawner entities.
 * Callers may keep the returned refs when they need explicit teardown, but normal
 * dungeon cleanup is world-based.
 *
 * @since 1.1.0
 */
public class SpawnerFactory {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Create spawner entities for all spawner definitions in the blueprint.
     *
     * @param store       the entity store
     * @param blueprint   the dungeon blueprint containing spawner definitions
     * @param worldOrigin the world-space origin offset of the dungeon
     * @return list of refs to created spawner entities
     * @since 1.1.0
     */
    @Nonnull
    public List<Ref<EntityStore>> createSpawners(
            @Nonnull Store<EntityStore> store,
            @Nonnull DungeonBlueprint blueprint,
            @Nonnull Vec3i worldOrigin) {
        return createSpawners(store, blueprint.getSpawners(), worldOrigin);
    }

    /**
     * Create spawner entities from a list of spawner definitions.
     *
     * @param store       the entity store
     * @param definitions the spawner definitions
     * @param worldOrigin the world-space origin offset of the dungeon
     * @return list of refs to created spawner entities
     * @since 1.2.0
     */
    @Nonnull
    public List<Ref<EntityStore>> createSpawners(
            @Nonnull Store<EntityStore> store,
            @Nonnull List<SpawnerDefinition> definitions,
            @Nonnull Vec3i worldOrigin) {
        List<Ref<EntityStore>> refs = new ArrayList<>(definitions.size());

        for (SpawnerDefinition def : definitions) {
            int wx = worldOrigin.x() + def.x();
            int wy = worldOrigin.y() + def.y();
            int wz = worldOrigin.z() + def.z();

            LOGGER.atInfo().log("[Spawner] Creating spawner #%d at (%d,%d,%d) room=%d pool=%d budget=%d variant=%s",
                    def.id(), wx, wy, wz, def.roomId(), def.spawnPool().size(),
                    def.totalCount(), def.variant());

            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.addComponent(SpawnerComponent.getComponentType(), new SpawnerComponent(def));
            holder.addComponent(TransformComponent.getComponentType(),
                    new TransformComponent(new Vector3d(wx + 0.5, wy, wz + 0.5), new Rotation3f()));

            Ref<EntityStore> ref = store.addEntity(holder, AddReason.SPAWN);
            refs.add(ref);
        }

        LOGGER.atInfo().log("[Spawner] Created %d spawner entities at origin (%d, %d, %d)",
                refs.size(), worldOrigin.x(), worldOrigin.y(), worldOrigin.z());

        return refs;
    }

    /**
     * Disable all spawner entities (set state to {@link SpawnerState#DISABLED}).
     * Used during dungeon teardown.
     *
     * @param store       the entity store
     * @param spawnerRefs refs to spawner entities
     * @since 1.1.0
     */
    public void disableAll(@Nonnull Store<EntityStore> store,
                           @Nonnull List<Ref<EntityStore>> spawnerRefs) {
        for (Ref<EntityStore> ref : spawnerRefs) {
            if (ref.isValid()) {
                SpawnerComponent comp = store.getComponent(ref, SpawnerComponent.getComponentType());
                if (comp != null) {
                    comp.setState(SpawnerState.DISABLED);
                }
            }
        }
    }

    /**
     * Destroy all spawner entities and their alive NPCs, then remove them from the store.
     * Used to clean up before a new dungeon generation.
     *
     * @param store       the entity store
     * @param spawnerRefs refs to spawner entities to destroy
     * @return the total number of entities removed (spawners + NPCs)
     * @since 1.3.0
     */
    public int destroyAll(@Nonnull Store<EntityStore> store,
                          @Nonnull List<Ref<EntityStore>> spawnerRefs) {
        int removed = 0;

        for (Ref<EntityStore> ref : spawnerRefs) {
            if (!ref.isValid()) continue;

            SpawnerComponent comp = store.getComponent(ref, SpawnerComponent.getComponentType());
            if (comp != null) {
                comp.setState(SpawnerState.DISABLED);

                // Remove alive NPCs spawned by this spawner
                for (Ref<EntityStore> npcRef : comp.getAliveNpcs()) {
                    if (npcRef.isValid()) {
                        store.removeEntity(npcRef, RemoveReason.REMOVE);
                        removed++;
                    }
                }
            }

            store.removeEntity(ref, RemoveReason.REMOVE);
            removed++;
        }

        if (removed > 0) {
            LOGGER.atInfo().log("[Spawner] Destroyed %d entities (%d spawners + NPCs)",
                    removed, spawnerRefs.size());
        }

        return removed;
    }
}
