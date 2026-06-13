package com.duntale.loot;

import com.duntale.economy.CurrencyDrop;
import com.duntale.progression.CombatScaling;
import com.duntale.progression.CombatScalingComponent;
import com.duntale.progression.ProgressionService;
import com.duntale.rpg.RpgService;
import com.duntale.rpg.RpgStat;
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
import org.joml.Vector3d;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventPickup;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Intercepts NPC death and replaces default drops with custom loot from {@link LootRollService}.
 *
 * <p>Runs <em>before</em> the engine's {@link NPCDamageSystems.DropDeathItems} system.
 * Sets {@link DeathConfig.ItemsLossMode#NONE} on the {@link DeathComponent} to suppress
 * the default drop logic, then spawns custom items from the matching loot table.
 *
 * <p>Only applies to leveled NPCs that have a {@link CombatScalingComponent}. Untracked NPCs
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

    private final LootRollService lootRollService;
    private final RpgService rpgService;
    private final ProgressionService progressionService;

    /**
     * Creates a new NPC loot system.
     *
     * @param lootRollService      the shared loot roll service
     * @param rpgService           the RPG service for attacker stat lookups
     * @param progressionService   the progression service for granting XP on kill
     */
    public NpcLootSystem(@Nonnull LootRollService lootRollService,
                         @Nonnull RpgService rpgService,
                         @Nonnull ProgressionService progressionService) {
        this.lootRollService = lootRollService;
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
        // ── 1. Look up the NPC's scaling data from ECS component ─────
        CombatScalingComponent scalingComp = store.getComponent(ref, CombatScalingComponent.getComponentType());
        if (scalingComp == null || scalingComp.isCompanion()) {
            // Untracked NPC or companion — leave untouched
            return;
        }

        int npcLevel = scalingComp.getLevel();
        CombatScaling.NpcVariant npcVariant = scalingComp.getVariant();

        // Get the NPC role name from NPCEntity
        NPCEntity npcEntity = store.getComponent(ref, NPCEntity.getComponentType());
        if (npcEntity == null) {
            return;
        }
        String npcId = NPCPlugin.get().getName(npcEntity.getRoleIndex());
        if (npcId == null) {
            return;
        }

        LOGGER.atInfo().log("Handling death of %s (%s Lv.%d) — applying custom loot logic",
            npcId, npcVariant, npcLevel);

        // ── 2. Suppress default NPC drops for all tracked NPCs ──────
        component.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);

        // ── 2b. Resolve attacker for Luck stat + XP grant ────────
        int luckLevel = 0;
        UUID attackerUuid = AttackerResolver.resolve(component, store);
        if (attackerUuid != null) {
            luckLevel = rpgService.getEffectiveStat(attackerUuid, RpgStat.LUCK);

            // Grant XP based on NPC level
            long xpAmount = BASE_XP_PER_KILL * npcLevel;
            progressionService.grantXP(attackerUuid, xpAmount);
        }

        // ── 3. Look up the loot table for this NPC role ──────────────
        if (!lootRollService.hasTable(npcId, npcVariant)) {
            // No custom table — default drops already suppressed, nothing else to do
            return;
        }

        // ── 4. Roll loot ─────────────────────────────────────────────
        List<ItemStack> drops = lootRollService.roll(npcId, npcVariant, npcLevel, luckLevel);
        if (drops.isEmpty()) {
            return;
        }

        // ── 5. Spawn item entities at the NPC's position ─────────────
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        assert transform != null;

        HeadRotation headRotation = store.getComponent(ref, HeadRotation.getComponentType());
        assert headRotation != null;

        Vector3d dropPosition = new Vector3d(transform.getPosition()).add(0.0, 1.0, 0.0);
        Rotation3f rotation = new Rotation3f(headRotation.getRotation());

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
                drops.size(), npcId, npcLevel);
    }
}
