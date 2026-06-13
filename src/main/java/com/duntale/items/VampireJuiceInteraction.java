package com.duntale.items;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Custom Secondary (right-click) interaction for the Vampire Juice flask.
 *
 * <p>Heals {@link CustomItems#VAMPIRE_HEAL_PCT}% of max health in exchange for
 * {@link CustomItems#VAMPIRE_STAMINA_COST} stamina. If the player has less
 * stamina than the drain cost, the interaction is refused (no heal, no drain) and
 * marked {@link InteractionState#Failed}. The flask is reusable; spacing between
 * uses is enforced by the item's JSON {@code Cooldown} block.
 *
 * <p>Registered under the codec type {@code "Duntale_VampireJuice"}.
 */
public class VampireJuiceInteraction extends SimpleInstantInteraction {

    /** Stat that gates stamina regeneration; held negative to suppress regen for its magnitude in seconds. */
    private static final String STAMINA_REGEN_DELAY_STAT = "StaminaRegenDelay";

    /** Codec for the Vampire Juice interaction (no extra fields). */
    public static final BuilderCodec<VampireJuiceInteraction> CODEC = BuilderCodec.builder(
                    VampireJuiceInteraction.class, VampireJuiceInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Heals the user for a percentage of max health, draining stamina (Duntale Vampire Juice).")
            .build();

    public VampireJuiceInteraction() {
    }

    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type,
                            @Nonnull InteractionContext context,
                            @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) {
            return;
        }
        Ref<EntityStore> ref = context.getEntity();
        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        int staminaIndex = DefaultEntityStatTypes.getStamina();
        int healthIndex = DefaultEntityStatTypes.getHealth();
        EntityStatValue stamina = statMap.get(staminaIndex);
        EntityStatValue health = statMap.get(healthIndex);
        if (stamina == null || health == null) {
            return;
        }

        float staminaCost = CustomItems.VAMPIRE_STAMINA_COST;
        if (stamina.get() < staminaCost) {
            // Not enough stamina — refuse to use (no partial effect).
            context.getState().state = InteractionState.Failed;
            return;
        }

        float healAmount = CustomItems.VAMPIRE_HEAL_PCT / 100.0f * health.getMax();
        statMap.subtractStatValue(EntityStatMap.Predictable.SELF, staminaIndex, staminaCost);
        statMap.addStatValue(EntityStatMap.Predictable.SELF, healthIndex, healAmount);
        suppressStaminaRegen(statMap);
        ItemVfx.applyFollowEffect(commandBuffer, ref, CustomItems.VAMPIRE_HEAL_EFFECT);
    }

    /**
     * Suppresses natural stamina regeneration for
     * {@link CustomItems#VAMPIRE_STAMINA_REGEN_DELAY_SECONDS} seconds by driving the
     * {@code StaminaRegenDelay} stat negative, the same gate dodges/blocks/bow-draws use so
     * a stamina cost actually "sticks". The stat climbs back to {@code 0} at +1/s, after which
     * regen resumes. Only extends an existing (more negative) delay; never shortens one.
     *
     * @param statMap the acting entity's stat map (read on the {@code WorldThread})
     */
    private static void suppressStaminaRegen(@Nonnull EntityStatMap statMap) {
        int regenDelayIndex = EntityStatType.getAssetMap().getIndex(STAMINA_REGEN_DELAY_STAT);
        if (regenDelayIndex == Integer.MIN_VALUE) {
            return;
        }
        float desired = -CustomItems.VAMPIRE_STAMINA_REGEN_DELAY_SECONDS;
        EntityStatValue regenDelay = statMap.get(regenDelayIndex);
        if (regenDelay == null || regenDelay.get() > desired) {
            statMap.setStatValue(EntityStatMap.Predictable.SELF, regenDelayIndex, desired);
        }
    }

    @Nonnull
    @Override
    public String toString() {
        return "VampireJuiceInteraction{} " + super.toString();
    }
}
