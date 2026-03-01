package com.duntale.zsquad.loot;

import com.duntale.zsquad.progression.NpcLevelRegistry;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Intercepts NPC death and replaces default drops with custom loot from {@link LootTableRegistry}.
 *
 * <p>Runs <em>before</em> the engine's {@link NPCDamageSystems.DropDeathItems} system.
 * Sets {@link DeathConfig.ItemsLossMode#NONE} on the {@link DeathComponent} to suppress
 * the default drop logic, then spawns custom items from the matching {@link LootTable}.
 *
 * <p>Only applies to leveled NPCs tracked by {@link NpcLevelRegistry}. Untracked NPCs
 * are left untouched and will still have their default drops suppressed (nothing drops
 * for unregistered mobs — intentional for an RPG game mode).
 */
public class NpcLootSystem extends DeathSystems.OnDeathSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Nonnull
    private static final Query<EntityStore> QUERY = Query.and(
            NPCEntity.getComponentType(),
            TransformComponent.getComponentType(),
            HeadRotation.getComponentType(),
            Query.not(Player.getComponentType())
    );

    @Nonnull
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.BEFORE, NPCDamageSystems.DropDeathItems.class)
    );

    private final LootTableRegistry lootTableRegistry;
    private final NpcLevelRegistry npcLevelRegistry;

    /**
     * Creates a new NPC loot system.
     *
     * @param lootTableRegistry the registry of custom loot tables
     * @param npcLevelRegistry  the registry tracking spawned NPC levels
     */
    public NpcLootSystem(@Nonnull LootTableRegistry lootTableRegistry, @Nonnull NpcLevelRegistry npcLevelRegistry) {
        this.lootTableRegistry = lootTableRegistry;
        this.npcLevelRegistry = npcLevelRegistry;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        // ── 1. Suppress default NPC drops ────────────────────────────
        component.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);

        // ── 2. Look up the NPC's level data ──────────────────────────
        UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            return;
        }

        UUID uuid = uuidComponent.getUuid();
        NpcLevelRegistry.NpcLevelData levelData = npcLevelRegistry.get(uuid);
        if (levelData == null) {
            // Untracked NPC — no custom loot, default drops already suppressed
            return;
        }

        // ── 3. Look up the loot table for this NPC role ──────────────
        LootTable lootTable = lootTableRegistry.get(levelData.npcId());
        if (lootTable == null) {
            return;
        }

        // ── 4. Roll loot ─────────────────────────────────────────────
        List<ItemStack> drops = lootTable.roll(levelData.level());
        if (drops.isEmpty()) {
            return;
        }

        // ── 5. Spawn item entities at the NPC's position ─────────────
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        assert transform != null;

        HeadRotation headRotation = store.getComponent(ref, HeadRotation.getComponentType());
        assert headRotation != null;

        Vector3d dropPosition = transform.getPosition().clone().add(0.0, 1.0, 0.0);
        Vector3f rotation = headRotation.getRotation().clone();

        Holder<EntityStore>[] itemEntities = ItemComponent.generateItemDrops(store, drops, dropPosition, rotation);
        commandBuffer.addEntities(itemEntities, AddReason.SPAWN);

        LOGGER.atInfo().log("Dropped %d custom loot item(s) for %s Lv.%d",
                drops.size(), levelData.npcId(), levelData.level());
    }
}
