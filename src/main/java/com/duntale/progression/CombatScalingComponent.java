package com.duntale.progression;

import com.duntale.DuntalePlugin;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * ECS component attached to every leveled NPC (enemy and companion) at spawn time.
 *
 * <p>Replaces the old {@code NpcLevelRegistry} UUID-based tracking.
 * Lives and dies with the entity — no manual cleanup, no stale entries.
 *
 * @since 1.5.0
 */
public class CombatScalingComponent implements Component<EntityStore> {

    /** Codec for serialization during chunk save/load. */
    @Nonnull
    public static final BuilderCodec<CombatScalingComponent> CODEC = BuilderCodec.builder(
                    CombatScalingComponent.class, CombatScalingComponent::new)
            .append(new KeyedCodec<>("Level", Codec.INTEGER),
                    (c, v) -> c.level = v, c -> c.level)
            .add()
            .append(new KeyedCodec<>("DamageMultiplier", Codec.FLOAT),
                    (c, v) -> c.damageMultiplier = v, c -> c.damageMultiplier)
            .add()
            .append(new KeyedCodec<>("Companion", Codec.BOOLEAN),
                    (c, v) -> c.companion = v, c -> c.companion)
            .add()
                .append(new KeyedCodec<>("Variant", Codec.STRING),
                    (c, v) -> c.variant = v, c -> c.variant)
                .add()
            .build();

    private int level;
    private float damageMultiplier;
    private boolean companion;
            @Nonnull
            private String variant;

    /**
     * No-arg constructor required by ECS component registration.
     */
    public CombatScalingComponent() {
        this.level = 1;
        this.damageMultiplier = 1.0f;
        this.companion = false;
        this.variant = CombatScaling.NpcVariant.NORMAL.name();
    }

    /**
     * Creates a combat scaling component with the specified values.
     *
     * @param level            the NPC's level (1-60)
     * @param damageMultiplier the pre-computed damage multiplier
     * @param companion        {@code true} if this is a companion NPC
     */
    public CombatScalingComponent(int level, float damageMultiplier, boolean companion) {
        this(level, damageMultiplier, companion, CombatScaling.NpcVariant.NORMAL);
    }

    /**
     * Creates a combat scaling component with the specified values.
     *
     * @param level            the NPC's level (1-60)
     * @param damageMultiplier the pre-computed damage multiplier
     * @param companion        {@code true} if this is a companion NPC
     * @param variant          the NPC variant recorded for downstream systems
     */
    public CombatScalingComponent(int level,
                                  float damageMultiplier,
                                  boolean companion,
                                  @Nonnull CombatScaling.NpcVariant variant) {
        this.level = level;
        this.damageMultiplier = damageMultiplier;
        this.companion = companion;
        this.variant = normalizeVariant(variant).name();
    }

    /**
     * Returns the registered component type for {@link CombatScalingComponent}.
     *
     * @return the component type
     */
    @Nonnull
    public static ComponentType<EntityStore, CombatScalingComponent> getComponentType() {
        return DuntalePlugin.get().getCombatScalingComponentType();
    }

    /** @return the NPC's level (1-60) */
    public int getLevel() {
        return level;
    }

    /** @return the pre-computed damage multiplier */
    public float getDamageMultiplier() {
        return damageMultiplier;
    }

    /** @return {@code true} if this is a companion NPC */
    public boolean isCompanion() {
        return companion;
    }

    /** @return the recorded NPC variant */
    @Nonnull
    public CombatScaling.NpcVariant getVariant() {
        try {
            return CombatScaling.NpcVariant.valueOf(normalizeVariantName(variant));
        } catch (IllegalArgumentException ignored) {
            return CombatScaling.NpcVariant.NORMAL;
        }
    }

    @Nullable
    @Override
    public Component<EntityStore> clone() {
        return new CombatScalingComponent(level, damageMultiplier, companion, getVariant());
    }

    @Nonnull
    private static CombatScaling.NpcVariant normalizeVariant(@Nullable CombatScaling.NpcVariant value) {
        return value == null ? CombatScaling.NpcVariant.NORMAL : value;
    }

    @Nonnull
    private static String normalizeVariantName(@Nullable String value) {
        return value == null ? CombatScaling.NpcVariant.NORMAL.name() : value.trim().toUpperCase();
    }
}
