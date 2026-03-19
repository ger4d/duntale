package com.duntale.zsquad.progression;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.AllLegacyLivingEntityTypesQuery;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
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

    /** Maximum combined armor DR — hard cap at 65%. */
    private static final float MAX_ARMOR_DR = 0.65f;

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

        // ── Case 1: NPC attacker → Player target ────────────────────
        //   a) Scale NPC outgoing damage by its level multiplier.
        //   b) Reduce damage by the player's leveled armor DR.
        NPCEntity attackerNpc = store.getComponent(attackerRef, NPCEntity.getComponentType());
        if (attackerNpc != null) {
            UUIDComponent attackerUuid = store.getComponent(attackerRef, UUIDComponent.getComponentType());
            if (attackerUuid != null) {
                NpcLevelRegistry.NpcLevelData data = npcLevelRegistry.get(attackerUuid.getUuid());
                if (data != null) {
                    float amount = damage.getAmount() * data.damageMultiplier();
                    amount = Math.min(amount, 500f);

                    // Apply player's leveled armor DR
                    float armorDr = computePlayerArmorDR(targetRef, store);
                    if (armorDr > 0f) {
                        amount *= (1f - armorDr);
                    }

                    LOGGER.atInfo().log("NPC attacker damage scaling: base=%.2f, levelMult=%.2f, armorDr=%.2f, final=%.2f",
                            damage.getAmount(), data.damageMultiplier(), armorDr, amount);

                    damage.setAmount(Math.max(amount, 0f));
                }
            }
            return;
        }

        // ── Case 2: Player attacker → NPC target ────────────────────
        //   Scale damage by the player's held weapon level multiplier × variance.
        NPCEntity targetNpc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (targetNpc != null) {
            float weaponMult = computePlayerWeaponMult(attackerRef, store);
            if (weaponMult > 0f) {
                damage.setAmount(damage.getAmount() * weaponMult);
            }
        }
    }

    /**
     * Computes the weapon damage multiplier from the attacker's held item.
     *
     * <p>If the held item has {@code zsquad_weapon_level} metadata, the multiplier
     * is looked up from the scaling DB for that weapon at that level, then scaled
     * by the item's variance. If the item has no level metadata, returns {@code 0}
     * (no scaling applied).
     *
     * @param attackerRef     the attacker entity reference
     * @param commandBuffer   the command buffer for entity access
     * @return the weapon multiplier (including variance), or {@code 0} if no leveled weapon
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

        String weaponId = heldItem.getItem().getId();
        float mult = scalingCache.getWeaponMultiplier(weaponId, weaponLevel);

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
     * <p>Each armor piece with {@code zsquad_armor_level} metadata contributes its
     * DR value (from the scaling DB) scaled by the piece's variance. The values are
     * combined additively, capped at 0.65 (65%).
     *
     * @param targetRef     the target entity reference
     * @param commandBuffer the command buffer for entity access
     * @return the combined DR (0.0–0.65), or {@code 0} if no leveled armor
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

            String armorId = piece.getItem().getId();
            float dr = scalingCache.getArmorDR(armorId, armorLevel);

            Float variance = GearLevelService.getArmorVariance(piece);
            if (variance != null) {
                dr *= variance;
            }

            totalDr += dr;
        }

        return Math.min(totalDr, MAX_ARMOR_DR);
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
