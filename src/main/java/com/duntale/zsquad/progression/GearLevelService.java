package com.duntale.zsquad.progression;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Static utility for reading and writing weapon/armor level metadata on {@link ItemStack}s.
 *
 * <p>Weapon and armor levels are stored as integer metadata on the item stack
 * using custom keys. These are read by {@link CombatScalingSystem} at damage time
 * to compute scaled damage or resistance.
 */
public final class GearLevelService {

    private static final String WEAPON_LEVEL_KEY = "zsquad_weapon_level";
    private static final String ARMOR_LEVEL_KEY = "zsquad_armor_level";
    private static final String WEAPON_VARIANCE_KEY = "zsquad_weapon_variance";
    private static final String ARMOR_VARIANCE_KEY = "zsquad_armor_variance";

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
     * @param level the weapon level (1–60)
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
     * @param level the armor level (1–60)
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
}
