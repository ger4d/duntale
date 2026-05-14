package com.duntale.rpg;

import com.duntale.progression.CombatScalingSystem;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.AllLegacyLivingEntityTypesQuery;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;

/**
 * Damage event system that applies RPG stat-based scaling to combat damage.
 *
 * <p>Handles two cases:
 * <ul>
 *   <li><strong>Player → NPC (Strength):</strong> Multiplies outgoing damage by the
 *       player's Strength stat multiplier via {@link RpgStatEffects#computeStrengthMultiplier(int)}.</li>
 *   <li><strong>NPC → Player (Resistance):</strong> Reduces incoming damage by the
 *       player's Resistance DR via {@link RpgStatEffects#computeResistanceDR(int)}.</li>
 * </ul>
 *
 * <p>Runs <em>after</em> {@link CombatScalingSystem} in the FilterDamage system group
 * so that base combat scaling is applied first.
 */
public class RpgDamageScalingSystem extends DamageEventSystem {

    @Nonnull
    @SuppressWarnings("deprecation")
    private static final Query<EntityStore> QUERY = AllLegacyLivingEntityTypesQuery.INSTANCE;

    @Nonnull
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, CombatScalingSystem.class)
    );

    private final RpgService rpgService;

    /**
     * Creates a new RPG damage scaling system.
     *
     * @param rpgService the RPG service for stat lookups
     */
    public RpgDamageScalingSystem(@Nonnull RpgService rpgService) {
        this.rpgService = rpgService;
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
        Damage.Source source = damage.getSource();
        if (!(source instanceof Damage.EntitySource entitySource)) {
            return;
        }

        Ref<EntityStore> attackerRef = entitySource.getRef();
        if (!attackerRef.isValid()) {
            return;
        }

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);

        // ── Case 1: Player attacker → NPC target (Strength) ─────────
        Player attackerPlayer = store.getComponent(attackerRef, Player.getComponentType());
        if (attackerPlayer != null) {
            NPCEntity targetNpc = store.getComponent(targetRef, NPCEntity.getComponentType());
            if (targetNpc != null) {
                UUIDComponent uuidComponent = store.getComponent(attackerRef, UUIDComponent.getComponentType());
                if (uuidComponent != null) {
                    UUID playerId = uuidComponent.getUuid();
                    int strengthLevel = rpgService.getStat(playerId, RpgStat.STRENGTH);
                    if (strengthLevel > 0) {
                        float multiplier = RpgStatEffects.computeStrengthMultiplier(strengthLevel);
                        damage.setAmount(damage.getAmount() * multiplier);
                    }
                }
            }
            return;
        }

        // ── Case 2: NPC attacker → Player target (Resistance) ───────
        NPCEntity attackerNpc = store.getComponent(attackerRef, NPCEntity.getComponentType());
        if (attackerNpc != null) {
            Player targetPlayer = store.getComponent(targetRef, Player.getComponentType());
            if (targetPlayer != null) {
                UUIDComponent uuidComponent = store.getComponent(targetRef, UUIDComponent.getComponentType());
                if (uuidComponent != null) {
                    UUID playerId = uuidComponent.getUuid();
                    int resistanceLevel = rpgService.getStat(playerId, RpgStat.RESISTANCE);
                    if (resistanceLevel > 0) {
                        float dr = RpgStatEffects.computeResistanceDR(resistanceLevel);
                        damage.setAmount(damage.getAmount() * (1.0f - dr));
                    }
                }
            }
        }
    }
}
