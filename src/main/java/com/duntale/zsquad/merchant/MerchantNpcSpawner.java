package com.duntale.zsquad.merchant;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.model.MerchantDefinition;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.vector.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates merchant NPC entities from dungeon blueprint {@link MerchantDefinition}s.
 * Each NPC is placed at the blueprint position offset by the world origin,
 * and receives a {@link MerchantComponent} storing the floor level.
 *
 * @since 1.3.0
 */
public class MerchantNpcSpawner {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** NPC role name for dungeon merchants. */
    private static final String MERCHANT_ROLE = "Klops_Merchant";

    private List<Ref<EntityStore>> activeRefs = List.of();

    /**
     * Spawn merchant NPC entities from the given definitions.
     *
     * @param store       the entity store
     * @param definitions the merchant definitions from the dungeon blueprint
     * @param worldOrigin the world-space origin offset of the dungeon
     * @return list of refs to created merchant NPC entities
     */
    @Nonnull
    public List<Ref<EntityStore>> spawnMerchants(
            @Nonnull Store<EntityStore> store,
            @Nonnull List<MerchantDefinition> definitions,
            @Nonnull Vec3i worldOrigin) {

        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            LOGGER.atWarning().log("[MerchantSpawner] NPCPlugin not available");
            return List.of();
        }

        int roleIndex = npcPlugin.getIndex(MERCHANT_ROLE);
        if (roleIndex < 0) {
            LOGGER.atWarning().log("[MerchantSpawner] Unknown NPC role: %s", MERCHANT_ROLE);
            return List.of();
        }

        List<Ref<EntityStore>> refs = new ArrayList<>(definitions.size());

        for (MerchantDefinition def : definitions) {
            int wx = worldOrigin.x() + def.x();
            int wy = worldOrigin.y() + def.y();
            int wz = worldOrigin.z() + def.z();

            Vector3d position = new Vector3d(wx + 0.5, wy, wz + 0.5);

            var result = npcPlugin.spawnEntity(store, roleIndex, position, null, null,
                    (npcEntity, holder, s) -> {
                        // Attach merchant component with floor level
                        holder.addComponent(MerchantComponent.getComponentType(),
                                new MerchantComponent(def.floorLevel()));
                    },
                    null);

            if (result != null) {
                refs.add(result.first());
                LOGGER.atInfo().log("[MerchantSpawner] Spawned merchant at (%d, %d, %d) floorLevel=%d",
                        wx, wy, wz, def.floorLevel());
            } else {
                LOGGER.atWarning().log("[MerchantSpawner] Failed to spawn merchant at (%d, %d, %d)",
                        wx, wy, wz);
            }
        }

        LOGGER.atInfo().log("[MerchantSpawner] Created %d merchant NPCs", refs.size());
        activeRefs = refs;
        return refs;
    }

    /**
     * Destroy all active merchant NPC entities.
     *
     * @param store the entity store
     * @return number of entities destroyed
     */
    public int destroyActive(@Nonnull Store<EntityStore> store) {
        int count = 0;
        for (Ref<EntityStore> ref : activeRefs) {
            if (ref.isValid()) {
                store.removeEntity(ref, RemoveReason.REMOVE);
                count++;
            }
        }
        activeRefs = List.of();
        return count;
    }
}
