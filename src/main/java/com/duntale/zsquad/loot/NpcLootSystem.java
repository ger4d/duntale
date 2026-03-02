package com.duntale.zsquad.loot;

import com.duntale.zsquad.economy.CurrencyDrop;
import com.duntale.zsquad.progression.NpcLevelRegistry;
import com.duntale.zsquad.progression.ProgressionService;
import com.duntale.zsquad.rpg.RpgService;
import com.duntale.zsquad.rpg.RpgStat;
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
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventPickup;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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

    /** Base XP per kill, scaled by NPC level. */
    private static final long BASE_XP_PER_KILL = 10;

    private final LootTableRegistry lootTableRegistry;
    private final NpcLevelRegistry npcLevelRegistry;
    private final RpgService rpgService;
    private final ProgressionService progressionService;

    /**
     * Creates a new NPC loot system.
     *
     * @param lootTableRegistry    the registry of custom loot tables
     * @param npcLevelRegistry     the registry tracking spawned NPC levels
     * @param rpgService           the RPG service for attacker stat lookups
     * @param progressionService   the progression service for granting XP on kill
     */
    public NpcLootSystem(@Nonnull LootTableRegistry lootTableRegistry,
                         @Nonnull NpcLevelRegistry npcLevelRegistry,
                         @Nonnull RpgService rpgService,
                         @Nonnull ProgressionService progressionService) {
        this.lootTableRegistry = lootTableRegistry;
        this.npcLevelRegistry = npcLevelRegistry;
        this.rpgService = rpgService;
        this.progressionService = progressionService;
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
        // ── 1. Look up the NPC's level data ──────────────────────────
        UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            return;
        }

        UUID uuid = uuidComponent.getUuid();
        NpcLevelRegistry.NpcLevelData levelData = npcLevelRegistry.get(uuid);
        if (levelData == null) {
            // Untracked NPC — leave default drops untouched
            return;
        }

        // ── 2. Suppress default NPC drops for all tracked NPCs ──────
        component.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);

        // ── 2b. Resolve attacker for Luck stat + XP grant ────────
        int luckLevel = 0;
        UUID attackerUuid = resolveAttacker(component, store);
        if (attackerUuid != null) {
            luckLevel = rpgService.getStat(attackerUuid, RpgStat.LUCK);

            // Grant XP based on NPC level
            long xpAmount = BASE_XP_PER_KILL * levelData.level();
            progressionService.grantXP(attackerUuid, xpAmount);
        }

        // ── 3. Look up the loot table for this NPC role ──────────────
        LootTable lootTable = lootTableRegistry.get(levelData.npcId());
        if (lootTable == null) {
            // No custom table — default drops already suppressed, nothing else to do
            return;
        }

        // ── 4. Roll loot ─────────────────────────────────────────────
        List<ItemStack> drops = lootTable.roll(levelData.level(), luckLevel);
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

        // ── 5b. Tag gold item entities with CurrencyDrop + PreventPickup ─
        for (Holder<EntityStore> holder : itemEntities) {
            ItemComponent itemComp = holder.getComponent(ItemComponent.getComponentType());
            if (itemComp != null && "Gold_Coin".equals(itemComp.getItemStack().getItemId())) {
                holder.addComponent(PreventPickup.getComponentType(), PreventPickup.INSTANCE);
                holder.addComponent(CurrencyDrop.getComponentType(), CurrencyDrop.INSTANCE);
            }
        }

        commandBuffer.addEntities(itemEntities, AddReason.SPAWN);

        LOGGER.atInfo().log("Dropped %d custom loot item(s) for %s Lv.%d",
                drops.size(), levelData.npcId(), levelData.level());
    }

    /**
     * Resolves the attacking player's UUID from the death component.
     *
     * @param deathComponent the death component
     * @param store          the entity store
     * @return the attacker's UUID, or {@code null} if unresolvable
     */
    @Nullable
    private UUID resolveAttacker(@Nonnull DeathComponent deathComponent,
                                 @Nonnull Store<EntityStore> store) {
        try {
            Damage damage = deathComponent.getDeathInfo();
            if (damage == null) return null;

            Damage.Source source = damage.getSource();
            if (!(source instanceof Damage.EntitySource entitySource)) return null;

            Ref<EntityStore> attackerRef = entitySource.getRef();
            if (!attackerRef.isValid()) return null;

            // Only player attackers contribute Luck
            Player player = store.getComponent(attackerRef, Player.getComponentType());
            if (player == null) return null;

            UUIDComponent uuidComp = store.getComponent(attackerRef, UUIDComponent.getComponentType());
            return uuidComp != null ? uuidComp.getUuid() : null;
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to resolve attacker from DeathComponent: %s", e.getMessage());
            return null;
        }
    }
}
