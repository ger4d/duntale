package com.duntale.companion;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.AllLegacyLivingEntityTypesQuery;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.component.Ref;
import com.duntale.rpg.RpgDamageScalingSystem;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Prevents companion NPCs from dying by clamping lethal damage to leave 1 HP.
 * Also grants full damage immunity while the companion is in the Recovery state.
 *
 * <p>Runs after {@link RpgDamageScalingSystem} in the damage pipeline so that all
 * scaling and modifiers are applied before the death protection check.
 */
public class CompanionDeathProtectionSystem extends DamageEventSystem {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final float MIN_COMPANION_HP = 1.0f;

    @Nonnull
    @SuppressWarnings("deprecation")
    private static final Query<EntityStore> QUERY = AllLegacyLivingEntityTypesQuery.INSTANCE;

    @Nonnull
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, RpgDamageScalingSystem.class)
    );

    private final ComponentType<EntityStore, CompanionComponent> companionComponentType;

    /**
     * Creates a new companion death protection system.
     *
     * @param companionComponentType the registered companion component type
     */
    public CompanionDeathProtectionSystem(
            @Nonnull ComponentType<EntityStore, CompanionComponent> companionComponentType) {
        this.companionComponentType = companionComponentType;
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
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage
    ) {
        if (damage.isCancelled()) {
            return;
        }

        // Only protect entities with CompanionComponent
        CompanionComponent companion = archetypeChunk.getComponent(index, companionComponentType);
        if (companion == null) {
            return;
        }

        // Cancel ALL damage while the companion is in the Recovery state
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        NPCEntity npcEntity = store.getComponent(ref, NPCEntity.getComponentType());
        if (npcEntity != null) {
            Role role = npcEntity.getRole();
            String stateName = role.getStateSupport().getStateName();
            LOGGER.atFine().log("Companion %s is in state '%s'", ref, stateName);
            if (role != null && stateName.startsWith("Recovery")) {
                damage.setCancelled(true);
                return;
            }
        }

        EntityStatMap statMap = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        EntityStatValue health = statMap.get(DefaultEntityStatTypes.getHealth());
        if (health == null) {
            return;
        }

        float currentHp = health.get();
        float damageAmount = damage.getAmount();

        if (currentHp - damageAmount < MIN_COMPANION_HP) {
            if (currentHp <= MIN_COMPANION_HP) {
                // Already at or below 1 HP — cancel all further damage
                damage.setCancelled(true);
            } else {
                // Reduce damage to leave exactly 1 HP
                damage.setAmount(currentHp - MIN_COMPANION_HP);
            }
        }
    }
}
