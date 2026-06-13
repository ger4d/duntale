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

    @Nonnull
    @SuppressWarnings("deprecation")
    private static final Query<EntityStore> QUERY = AllLegacyLivingEntityTypesQuery.INSTANCE;

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemGroupDependency<>(Order.BEFORE, DamageModule.get().getFilterDamageGroup())
    );

    private final ComponentType<EntityStore, CombatScalingComponent> combatScalingType;
    private final AssetCatalog assetCatalog;
    private final GearCurveRegistry gearCurves;

    /**
     * Creates a new combat scaling system.
     *
     * @param combatScalingType the registered combat scaling component type
     * @param assetCatalog      the asset catalog, for resolving a weapon's family and live per-hit
     * @param gearCurves        the authored gear-curve registry (empty snapshot drives legacy fallback)
     */
    public CombatScalingSystem(@Nonnull ComponentType<EntityStore, CombatScalingComponent> combatScalingType,
                               @Nonnull AssetCatalog assetCatalog,
                               @Nonnull GearCurveRegistry gearCurves) {
        this.combatScalingType = combatScalingType;
        this.assetCatalog = assetCatalog;
        this.gearCurves = gearCurves;
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
                damage.setAmount(damage.getAmount() * weaponMult);
            }
        }
    }

    /**
     * Computes the total damage factor applied to the attacker's raw per-hit, so the final per-hit
     * follows the authored family curve rather than the weapon's own asset number.
     *
     * <p>The factor is {@code (anchor / assetPerHit) * weaponMult(level) * rarityNudge * variance}.
     * The {@code anchor / assetPerHit} corrective ratio divides out the weapon's authored damage and
     * substitutes the family anchor, so two same-level weapons of the same family land on equal power
     * regardless of how their assets were authored (e.g. an outlier "Common" longsword normalizes to
     * its family). Multi-move weapons keep their internal light/heavy ratio because the ratio is
     * applied to whatever specific move damage the engine resolved.
     *
     * <p>Falls back to the legacy {@code weaponMult * variance} when no authored curves are loaded or
     * when the weapon's asset per-hit can't be resolved (so no corrective ratio can be formed).
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

        float variance = varianceOrOne(GearLevelService.getWeaponVariance(heldItem));
        float legacyFactor = CombatScaling.weaponMult(weaponLevel) * variance;

        if (!gearCurves.isLoaded()) {
            return legacyFactor;
        }

        String weaponId = heldItem.getItem().getId();
        AssetCatalog.WeaponBaseRow row = weaponId != null ? assetCatalog.getWeaponBase(weaponId) : null;
        if (row == null || row.baseDamage() <= 0f) {
            // No authored asset per-hit to divide out — keep the legacy curve for this weapon.
            LOGGER.atFine().log("No catalog per-hit for weapon %s — using legacy scaling", weaponId);
            return legacyFactor;
        }

        float anchor = gearCurves.weaponAnchor(row.family());
        float nudge = gearCurves.rarityNudge(GearLevelService.getRarity(heldItem));
        float corrective = anchor / row.baseDamage();
        return corrective * CombatScaling.weaponMult(weaponLevel) * nudge * variance;
    }

    /**
     * Computes the combined armor damage reduction from all leveled armor pieces worn by the target.
     *
     * <p>With authored curves loaded, each piece's DR is its slot's share of the level-driven DR
     * budget ({@code slotShare * drBudget(level) * rarityNudge * variance}); the piece's own asset
     * resist is ignored, so gear power follows slot and level alone. A piece whose slot is unmapped
     * (or any piece when no curves are loaded) falls back to the legacy asset-resist DR. The summed
     * total is capped at {@link CombatScaling#MAX_ARMOR_DR}.
     */
    private float computePlayerArmorDR(@Nonnull Ref<EntityStore> targetRef,
                                       @Nonnull Store<EntityStore> store) {
        InventoryComponent.Armor armorComponent = store.getComponent(targetRef, InventoryComponent.Armor.getComponentType());
        if (armorComponent == null) {
            return 0f;
        }

        ItemContainer armorContainer = armorComponent.getInventory();
        boolean curvesLoaded = gearCurves.isLoaded();
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

            ItemArmor itemArmor = piece.getItem().getArmor();
            if (itemArmor == null) {
                continue;
            }

            float variance = varianceOrOne(GearLevelService.getArmorVariance(piece));

            Float share = null;
            if (curvesLoaded) {
                String slotName = itemArmor.getArmorSlot() != null ? itemArmor.getArmorSlot().name() : null;
                share = slotName != null ? gearCurves.slotShare(slotName) : null;
                if (share == null) {
                    LOGGER.atWarning().log(
                            "Armor %s has unmapped slot '%s' — using legacy resist scaling",
                            piece.getItem().getId(), slotName);
                }
            }

            float dr;
            if (share != null) {
                float nudge = gearCurves.rarityNudge(GearLevelService.getRarity(piece));
                dr = CombatScaling.armorBudgetDR(share, armorLevel,
                        gearCurves.drBudgetMin(), gearCurves.drBudgetMax()) * nudge * variance;
            } else {
                dr = CombatScaling.armorDR(legacyAssetResist(itemArmor), armorLevel) * variance;
            }

            totalDr += dr;
        }

        return Math.min(totalDr, CombatScaling.MAX_ARMOR_DR);
    }

    /**
     * Sums the asset's authored physical resist ({@code DamageResistance.Physical[].Amount}, NOT
     * {@code BaseDamageResistance}) for the legacy DR fallback.
     */
    private static float legacyAssetResist(@Nonnull ItemArmor itemArmor) {
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
        return baseResist;
    }

    private static float varianceOrOne(@Nullable Float variance) {
        return variance != null ? variance : 1.0f;
    }
}
