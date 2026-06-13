package com.duntale.progression;

import com.duntale.loot.GearAttribute;
import com.duntale.loot.Rarity;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Static utility for reading and writing weapon/armor level metadata on {@link ItemStack}s.
 *
 * <p>Weapon and armor levels are stored as integer metadata on the item stack
 * using custom keys. These are read by {@link CombatScalingSystem} at damage time
 * to compute scaled damage or resistance.
 */
public final class GearLevelService {

    private static final String WEAPON_LEVEL_KEY = "duntale_weapon_level";
    private static final String ARMOR_LEVEL_KEY = "duntale_armor_level";
    private static final String WEAPON_VARIANCE_KEY = "duntale_weapon_variance";
    private static final String ARMOR_VARIANCE_KEY = "duntale_armor_variance";
    private static final String RARITY_KEY = "duntale_rarity";
    private static final String ATTRIBUTES_KEY = "duntale_attributes";

    /** Default variance range: ±5% (0.95–1.05). */
    public static final float VARIANCE_MIN = 0.95f;
    /** Default variance range: ±5% (0.95–1.05). */
    public static final float VARIANCE_MAX = 1.05f;

    private GearLevelService() {
        // Static utility — do not instantiate
    }

    /**
     * Generates a random variance factor within the default range.
     *
     * @return a float between {@link #VARIANCE_MIN} and {@link #VARIANCE_MAX}
     */
    public static float rollVariance() {
        return VARIANCE_MIN + (float) Math.random() * (VARIANCE_MAX - VARIANCE_MIN);
    }

    /**
     * Returns a copy of the item stack with the weapon level set.
     *
     * @param stack the original item stack
     * @param level the weapon level within supported combat bounds
     * @return a new item stack with the metadata applied
     */
    @Nonnull
    public static ItemStack setWeaponLevel(@Nonnull ItemStack stack, int level) {
        return stack.withMetadata(WEAPON_LEVEL_KEY, Codec.INTEGER, level);
    }

    /**
     * Reads the weapon level from an item stack.
     *
     * @param stack the item stack to inspect
     * @return the weapon level, or {@code null} if unleveled
     */
    @Nullable
    public static Integer getWeaponLevel(@Nonnull ItemStack stack) {
        return stack.getFromMetadataOrNull(WEAPON_LEVEL_KEY, Codec.INTEGER);
    }

    /**
     * Returns a copy of the item stack with the weapon variance set.
     *
     * @param stack    the original item stack
     * @param variance the variance multiplier (e.g. 0.95–1.05)
     * @return a new item stack with the metadata applied
     */
    @Nonnull
    public static ItemStack setWeaponVariance(@Nonnull ItemStack stack, float variance) {
        return stack.withMetadata(WEAPON_VARIANCE_KEY, Codec.FLOAT, variance);
    }

    /**
     * Reads the weapon variance from an item stack.
     *
     * @param stack the item stack to inspect
     * @return the variance multiplier, or {@code null} if not set
     */
    @Nullable
    public static Float getWeaponVariance(@Nonnull ItemStack stack) {
        return stack.getFromMetadataOrNull(WEAPON_VARIANCE_KEY, Codec.FLOAT);
    }

    /**
     * Returns a copy of the item stack with the armor level set.
     *
     * @param stack the original item stack
     * @param level the armor level within supported combat bounds
     * @return a new item stack with the metadata applied
     */
    @Nonnull
    public static ItemStack setArmorLevel(@Nonnull ItemStack stack, int level) {
        return stack.withMetadata(ARMOR_LEVEL_KEY, Codec.INTEGER, level);
    }

    /**
     * Reads the armor level from an item stack.
     *
     * @param stack the item stack to inspect
     * @return the armor level, or {@code null} if unleveled
     */
    @Nullable
    public static Integer getArmorLevel(@Nonnull ItemStack stack) {
        return stack.getFromMetadataOrNull(ARMOR_LEVEL_KEY, Codec.INTEGER);
    }

    /**
     * Returns a copy of the item stack with the armor variance set.
     *
     * @param stack    the original item stack
     * @param variance the variance multiplier (e.g. 0.95–1.05)
     * @return a new item stack with the metadata applied
     */
    @Nonnull
    public static ItemStack setArmorVariance(@Nonnull ItemStack stack, float variance) {
        return stack.withMetadata(ARMOR_VARIANCE_KEY, Codec.FLOAT, variance);
    }

    /**
     * Reads the armor variance from an item stack.
     *
     * @param stack the item stack to inspect
     * @return the variance multiplier, or {@code null} if not set
     */
    @Nullable
    public static Float getArmorVariance(@Nonnull ItemStack stack) {
        return stack.getFromMetadataOrNull(ARMOR_VARIANCE_KEY, Codec.FLOAT);
    }

    /**
     * Reads the rarity tier from an item stack, used to look up the gear-curve rarity nudge.
     *
     * <p>Nothing stamps this key yet, so it currently returns {@code null} for all gear and the
     * power nudge resolves to the no-op default. The read seam is in place so a later rarity system
     * can stamp items without touching the damage-time scaling path.
     *
     * @param stack the item stack to inspect
     * @return the rarity name (e.g. "Legendary"), or {@code null} if unstamped
     */
    @Nullable
    public static String getRarity(@Nonnull ItemStack stack) {
        return stack.getFromMetadataOrNull(RARITY_KEY, Codec.STRING);
    }

    /**
     * Returns a copy of the item stack stamped with the given rarity tier.
     *
     * @param stack  the original item stack
     * @param rarity the rarity to stamp
     * @return a new item stack with the {@code duntale_rarity} metadata applied
     */
    @Nonnull
    public static ItemStack setRarity(@Nonnull ItemStack stack, @Nonnull Rarity rarity) {
        return stack.withMetadata(RARITY_KEY, Codec.STRING, rarity.id());
    }

    /**
     * Returns a copy of the item stack stamped with the given rarity-granted attributes.
     *
     * <p>An empty list clears the attribute tag.
     *
     * @param stack      the original item stack
     * @param attributes the attributes to encode
     * @return a new item stack with the {@code duntale_attributes} metadata applied
     */
    @Nonnull
    public static ItemStack setAttributes(@Nonnull ItemStack stack, @Nonnull List<GearAttribute> attributes) {
        if (attributes.isEmpty()) {
            return stack.withMetadata(ATTRIBUTES_KEY, Codec.STRING, null);
        }
        return stack.withMetadata(ATTRIBUTES_KEY, Codec.STRING, GearAttribute.encode(attributes));
    }

    /**
     * Reads the rarity-granted attributes from an item stack.
     *
     * @param stack the item stack to inspect
     * @return the decoded attributes, or an empty list when unstamped/malformed
     */
    @Nonnull
    public static List<GearAttribute> getAttributes(@Nonnull ItemStack stack) {
        return GearAttribute.decode(stack.getFromMetadataOrNull(ATTRIBUTES_KEY, Codec.STRING));
    }
}
