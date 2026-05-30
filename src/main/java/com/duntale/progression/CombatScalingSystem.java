package com.duntale.progression;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemArmor;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.AllLegacyLivingEntityTypesQuery;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.ResistanceModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

/**
 * Intercepts damage events to apply level-based combat scaling.
 *
 * <p>Runs in the {@code FilterDamage} group. Reads {@link CombatScalingComponent}
 * from attacker/target to determine scaling behavior.
 *
 * <p>Damage cases:
 * <ul>
 *   <li><strong>Enemy NPC -> Player:</strong> enemy damageMult * (1 - player armor DR)</li>
 *   <li><strong>Enemy NPC -> Companion:</strong> enemy damageMult (no armor DR)</li>
 *   <li><strong>Player -> Enemy NPC:</strong> player weaponMult</li>
 *   <li><strong>Companion -> Enemy NPC:</strong> companion damageMult</li>
 *   <li><strong>Player -> Companion:</strong> no scaling (base damage only)</li>
 * </ul>
 */
public class CombatScalingSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Effectiveness multiplier applied to a fully broken (0 durability) gear piece. */
    private static final float BROKEN_GEAR_EFFECTIVENESS = 0.07f;

    @Nonnull
    @SuppressWarnings("deprecation")
    private static final Query<EntityStore> QUERY = AllLegacyLivingEntityTypesQuery.INSTANCE;

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemGroupDependency<>(Order.BEFORE, DamageModule.get().getFilterDamageGroup())
    );

    private final ComponentType<EntityStore, CombatScalingComponent> combatScalingType;

    /**
     * Creates a new combat scaling system.
     *
     * @param combatScalingType the registered combat scaling component type
     */
    public CombatScalingSystem(@Nonnull ComponentType<EntityStore, CombatScalingComponent> combatScalingType) {
        this.combatScalingType = combatScalingType;
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

        // ── Attacker has CombatScalingComponent (NPC or Companion) ───
        CombatScalingComponent attackerScaling = store.getComponent(attackerRef, combatScalingType);

        if (attackerScaling != null) {
            if (attackerScaling.isCompanion()) {
                // Companion -> Enemy NPC: apply companion's damage mult
                CombatScalingComponent targetScaling = store.getComponent(targetRef, combatScalingType);
                if (targetScaling != null && !targetScaling.isCompanion()) {
                    LOGGER.atFine().log("Applying companion damage multiplier: Base = %.2f, Mult = %.2f => Final = %.2f",
                            damage.getAmount(), attackerScaling.getDamageMultiplier(), Math.min(damage.getAmount() * attackerScaling.getDamageMultiplier(), 500f));
                    damage.setAmount(Math.min(damage.getAmount() * attackerScaling.getDamageMultiplier(), 500f));
                }
            } else {
                // Enemy NPC -> anything: apply enemy's damage mult
                float amount = Math.min(damage.getAmount() * attackerScaling.getDamageMultiplier(), 500f);

                // Armor DR only when target is a player (no CombatScalingComponent)
                CombatScalingComponent targetScaling = store.getComponent(targetRef, combatScalingType);
                if (targetScaling == null) {
                    float armorDr = computePlayerArmorDR(targetRef, store);
                    if (armorDr > 0f) {
                        amount *= (1f - armorDr);
                    }
                }

                LOGGER.atFine().log("Applying NPC enemy damage multiplier: Base = %.2f, Mult = %.2f => Final = %.2f",
                            damage.getAmount(), attackerScaling.getDamageMultiplier(), Math.min(damage.getAmount() * attackerScaling.getDamageMultiplier(), 500f));
                damage.setAmount(Math.max(amount, 0f));
            }
            return;
        }

        // ── Player -> NPC: scale by weapon mult (skip if target is companion) ─
        CombatScalingComponent targetScaling = store.getComponent(targetRef, combatScalingType);
        if (targetScaling != null && !targetScaling.isCompanion()) {
            float weaponMult = computePlayerWeaponMult(attackerRef, store);
            if (weaponMult > 0f) {
                // Step penalty: a fully broken weapon deals only 7% of its scaled damage.
                weaponMult *= brokenFactor(InventoryComponent.getItemInHand(store, attackerRef));
                damage.setAmount(damage.getAmount() * weaponMult);
            }
        }
    }

    /**
     * Returns the effectiveness multiplier for a gear piece based on its durability.
     *
     * <p>Applies a step penalty: a breakable piece at 0 durability contributes only
     * {@link #BROKEN_GEAR_EFFECTIVENESS}; all other cases (intact, unbreakable, or {@code null})
     * return {@code 1f} so they are never penalized.
     *
     * @param stack the gear piece to evaluate, may be {@code null}
     * @return {@link #BROKEN_GEAR_EFFECTIVENESS} when the piece is breakable and broken, otherwise {@code 1f}
     */
    private static float brokenFactor(@Nullable ItemStack stack) {
        if (stack != null && stack.getMaxDurability() > 0.0 && stack.getDurability() <= 0.0) {
            return BROKEN_GEAR_EFFECTIVENESS;
        }
        return 1f;
    }

    /**
     * Computes the weapon damage multiplier from the attacker's held item.
     * Formula is uniform across all weapons — only the gear level matters.
     */
    private float computePlayerWeaponMult(@Nonnull Ref<EntityStore> attackerRef,
                                          @Nonnull Store<EntityStore> store) {
        ItemStack heldItem = InventoryComponent.getItemInHand(store, attackerRef);
        if (ItemStack.isEmpty(heldItem)) {
            return 0f;
        }

        Integer weaponLevel = GearLevelService.getWeaponLevel(heldItem);
        if (weaponLevel == null) {
            return 0f;
        }

        float mult = CombatScaling.weaponMult(weaponLevel);

        Float variance = GearLevelService.getWeaponVariance(heldItem);
        if (variance != null) {
            mult *= variance;
        }

        return mult;
    }

    /**
     * Computes the combined armor damage reduction from all leveled armor pieces
     * worn by the target entity.
     *
     * <p>Reads physical resist directly from the Hytale Item API
     * ({@code DamageResistance.Physical[].Amount}), NOT {@code BaseDamageResistance}.
     */
    private float computePlayerArmorDR(@Nonnull Ref<EntityStore> targetRef,
                                       @Nonnull Store<EntityStore> store) {
        InventoryComponent.Armor armorComponent = store.getComponent(targetRef, InventoryComponent.Armor.getComponentType());
        if (armorComponent == null) {
            return 0f;
        }

        ItemContainer armorContainer = armorComponent.getInventory();
        float totalDr = 0f;

        for (short slot = 0; slot < armorContainer.getCapacity(); slot++) {
            ItemStack piece = armorContainer.getItemStack(slot);
            if (ItemStack.isEmpty(piece)) {
                continue;
            }

            Integer armorLevel = GearLevelService.getArmorLevel(piece);
            if (armorLevel == null) {
                continue;
            }

            // Read physical resist from Hytale item asset — no DB needed
            ItemArmor itemArmor = piece.getItem().getArmor();
            if (itemArmor == null) {
                continue;
            }

            float baseResist = 0f;
            Map<DamageCause, ResistanceModifier[]> resistMap = itemArmor.getDamageResistanceValues();
            DamageCause physicalCause = DamageCause.getAssetMap().getAsset("Physical");
            if (resistMap != null && physicalCause != null) {
                ResistanceModifier[] physMods = resistMap.get(physicalCause);
                if (physMods != null) {
                    for (ResistanceModifier mod : physMods) {
                        baseResist += mod.getAmount();
                    }
                }
            }

            float dr = CombatScaling.armorDR(baseResist, armorLevel);

            Float variance = GearLevelService.getArmorVariance(piece);
            if (variance != null) {
                dr *= variance;
            }

            // Step penalty: a fully broken armor piece contributes only 7% of its DR.
            dr *= brokenFactor(piece);

            totalDr += dr;
        }

        return Math.min(totalDr, CombatScaling.MAX_ARMOR_DR);
    }
}
