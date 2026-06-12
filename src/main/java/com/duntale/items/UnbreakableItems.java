package com.duntale.items;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Utility for converting player gear stacks into engine-unbreakable stacks.
 *
 * <p>Under Economy v2 design pillar P1, all weapons and armor are fully
 * indestructible. Rather than overriding durability fields across hundreds of
 * item assets, this leverages the engine's first-class unbreakable semantics:
 * {@link ItemStack#isUnbreakable()} is {@code maxDurability <= 0}, which makes
 * {@link ItemStack#isBroken()} permanently {@code false}, clamps all durability
 * loss to a no-op, and suppresses the break message/SFX. Clients render no
 * durability bar for such stacks.
 *
 * <p>"Gear" means any item carrying a weapon or armor config; harvest tools and
 * other durable non-gear items are left untouched.
 */
public final class UnbreakableItems {

    private UnbreakableItems() {
    }

    /**
     * Returns whether the given item is player gear (a weapon or armor piece).
     *
     * @param item the item config to inspect, may be {@code null}
     * @return {@code true} if the item carries a weapon or armor config
     */
    public static boolean isGear(@Nullable Item item) {
        return item != null && (item.getWeapon() != null || item.getArmor() != null);
    }

    /**
     * Returns an unbreakable copy of the given gear stack, or the stack unchanged
     * when it is empty, not gear, or already unbreakable.
     *
     * <p>When a conversion is needed, the returned stack has {@code maxDurability == 0}
     * (and therefore {@link ItemStack#isUnbreakable()} {@code == true}). Otherwise the
     * exact same instance is returned, so callers can use reference identity as a
     * cheap "was anything changed" check.
     *
     * @param stack the stack to convert
     * @return an unbreakable copy when conversion applies, otherwise {@code stack} itself
     */
    @Nonnull
    public static ItemStack makeUnbreakable(@Nonnull ItemStack stack) {
        if (ItemStack.isEmpty(stack) || stack.getMaxDurability() <= 0.0 || !isGear(stack.getItem())) {
            return stack;
        }
        return stack.withMaxDurability(0.0);
    }
}
