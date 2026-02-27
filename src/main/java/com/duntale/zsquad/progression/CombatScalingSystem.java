package com.duntale.zsquad.progression;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.AllLegacyLivingEntityTypesQuery;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Intercepts damage events to apply level-based scaling.
 *
 * <p>Runs in the {@code FilterDamage} group, <em>after</em> vanilla
 * {@link DamageSystems.ArmorDamageReduction} so base armor DR is applied first.
 *
 * <p>Handles two cases:
 * <ul>
 *   <li><strong>NPC → Player:</strong> Multiplies damage by the NPC's
 *       pre-computed {@code damageMultiplier} from {@link NpcLevelRegistry}.</li>
 *   <li><strong>Player → NPC:</strong> Multiplies damage by the player's
 *       equipped weapon's level multiplier from {@link ScalingDataCache}.</li>
 * </ul>
 */
public class CombatScalingSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Nonnull
    private static final Query<EntityStore> QUERY = AllLegacyLivingEntityTypesQuery.INSTANCE;

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemGroupDependency<>(Order.BEFORE, DamageModule.get().getFilterDamageGroup())
    );

    private final NpcLevelRegistry npcLevelRegistry;
    private final ScalingDataCache scalingCache;

    /**
     * Creates a new combat scaling system.
     *
     * @param npcLevelRegistry the NPC level registry
     * @param scalingCache     the scaling data cache
     */
    public CombatScalingSystem(@Nonnull NpcLevelRegistry npcLevelRegistry, @Nonnull ScalingDataCache scalingCache) {
        this.npcLevelRegistry = npcLevelRegistry;
        this.scalingCache = scalingCache;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage
    ) {
        Damage.Source source = damage.getSource();
        if (!(source instanceof Damage.EntitySource entitySource)) {
            return;
        }

        Ref<EntityStore> attackerRef = entitySource.getRef();
        if (!attackerRef.isValid()) {
            return;
        }

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);

        // ── Case 1: NPC attacker → scale outgoing damage ────────────
        NPCEntity attackerNpc = store.getComponent(attackerRef, NPCEntity.getComponentType());
        if (attackerNpc != null) {
            UUIDComponent attackerUuid = store.getComponent(attackerRef, UUIDComponent.getComponentType());
            if (attackerUuid != null) {
                NpcLevelRegistry.NpcLevelData data = npcLevelRegistry.get(attackerUuid.getUuid());
                if (data != null) {
                    float newAmount = damage.getAmount() * data.damageMultiplier();
                    damage.setAmount(Math.min(newAmount, 500f));
                }
            }
            return;
        }

        // ── Case 2: Player attacker → NPC target, scale weapon damage
        NPCEntity targetNpc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (targetNpc != null) {
            UUIDComponent targetUuid = store.getComponent(targetRef, UUIDComponent.getComponentType());
            if (targetUuid != null) {
                NpcLevelRegistry.NpcLevelData targetData = npcLevelRegistry.get(targetUuid.getUuid());
                if (targetData != null) {
                    // For now, apply a base weapon scaling based on the NPC's level
                    // This can be enhanced to read weapon metadata when gear leveling is fully implemented
                    int npcLevel = targetData.level();
                    float weaponMult = scalingCache.getWeaponMultiplier("Default_Weapon", npcLevel);
                    if (weaponMult > 1.0f) {
                        damage.setAmount(damage.getAmount() * weaponMult);
                    }
                }
            }
        }
    }

    /**
     * Called when a tracked NPC dies — removes their entry from the registry.
     *
     * @param uuid the dead NPC's UUID
     */
    public void onNpcDeath(@Nonnull UUID uuid) {
        npcLevelRegistry.remove(uuid);
    }
}
