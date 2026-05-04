package com.duntale.zsquad.loot.config.asset;

import com.duntale.zsquad.loot.LootEntry;
import com.duntale.zsquad.loot.LootEntry.GearType;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Codec-backed DTO for one loot table entry loaded from JSON assets.
 */
public class LootEntryConfig {

    public static final BuilderCodec<LootEntryConfig> CODEC;
    public static final ArrayCodec<LootEntryConfig> ARRAY_CODEC;

    protected String type = "SIMPLE";
    protected String itemId = "";
    protected String gearType = GearType.WEAPON.name();
    protected int gearLevelMin = 1;
    protected int gearLevelMax = 1;
    protected int quantityMin = 1;
    protected int quantityMax = 1;
    protected double weight = 1.0;
    protected int minNpcLevel = 0;
    protected int maxNpcLevel = 0;

    static {
        CODEC = BuilderCodec.builder(LootEntryConfig.class, LootEntryConfig::new)
                .append(new KeyedCodec<>("Type", Codec.STRING),
                        (config, value) -> config.type = value,
                        config -> config.type)
                .add()
                .append(new KeyedCodec<>("ItemId", Codec.STRING),
                        (config, value) -> config.itemId = value,
                        config -> config.itemId)
                .add()
                .append(new KeyedCodec<>("GearType", Codec.STRING),
                        (config, value) -> config.gearType = value,
                        config -> config.gearType)
                .add()
                .append(new KeyedCodec<>("GearLevelMin", Codec.INTEGER),
                        (config, value) -> config.gearLevelMin = value,
                        config -> config.gearLevelMin)
                .add()
                .append(new KeyedCodec<>("GearLevelMax", Codec.INTEGER),
                        (config, value) -> config.gearLevelMax = value,
                        config -> config.gearLevelMax)
                .add()
                .append(new KeyedCodec<>("QuantityMin", Codec.INTEGER),
                        (config, value) -> config.quantityMin = value,
                        config -> config.quantityMin)
                .add()
                .append(new KeyedCodec<>("QuantityMax", Codec.INTEGER),
                        (config, value) -> config.quantityMax = value,
                        config -> config.quantityMax)
                .add()
                .append(new KeyedCodec<>("Weight", Codec.DOUBLE),
                        (config, value) -> config.weight = value,
                        config -> config.weight)
                .add()
                .append(new KeyedCodec<>("MinNpcLevel", Codec.INTEGER),
                        (config, value) -> config.minNpcLevel = value,
                        config -> config.minNpcLevel)
                .add()
                .append(new KeyedCodec<>("MaxNpcLevel", Codec.INTEGER),
                        (config, value) -> config.maxNpcLevel = value,
                        config -> config.maxNpcLevel)
                .add()
                .build();
        ARRAY_CODEC = new ArrayCodec<>(CODEC, LootEntryConfig[]::new);
    }

    public LootEntryConfig() {
    }

    /**
     * Converts this config entry to the runtime loot model.
     *
     * @return the converted runtime loot entry
     */
    @Nonnull
    public LootEntry toLootEntry() {
        List<String> errors = validationErrors();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }

        String normalizedType = normalizedType();
        String normalizedItemId = normalizedItemId();
        Integer normalizedMinNpcLevel = normalizeNpcLevelBound(minNpcLevel);
        Integer normalizedMaxNpcLevel = normalizeNpcLevelBound(maxNpcLevel);

        return switch (normalizedType) {
            case "SIMPLE" -> new LootEntry.Simple(
                    normalizedItemId,
                    quantityMin,
                    quantityMax,
                    weight,
                    normalizedMinNpcLevel,
                    normalizedMaxNpcLevel
            );
            case "LEVELED" -> new LootEntry.Leveled(
                    normalizedItemId,
                    GearType.valueOf(normalizedGearType()),
                    gearLevelMin,
                    gearLevelMax,
                    weight,
                    normalizedMinNpcLevel,
                    normalizedMaxNpcLevel
            );
            default -> throw new IllegalStateException("Unsupported loot entry type: " + normalizedType);
        };
    }

    @Nonnull
    List<String> validationErrors() {
        List<String> errors = new ArrayList<>();

        String normalizedType = normalizedType();
        if (!"SIMPLE".equals(normalizedType) && !"LEVELED".equals(normalizedType)) {
            errors.add("Type must be SIMPLE or LEVELED: " + type);
        }

        if (normalizedItemId().isEmpty()) {
            errors.add("ItemId must not be blank");
        }

        if (weight <= 0.0) {
            errors.add("Weight must be greater than 0.0");
        }

        if (quantityMin < 1) {
            errors.add("QuantityMin must be at least 1");
        }
        if (quantityMax < quantityMin) {
            errors.add("QuantityMax must be greater than or equal to QuantityMin");
        }

        if (minNpcLevel < 0) {
            errors.add("MinNpcLevel must be 0 or greater");
        }
        if (maxNpcLevel < 0) {
            errors.add("MaxNpcLevel must be 0 or greater");
        }
        if (minNpcLevel > 0 && maxNpcLevel > 0 && minNpcLevel > maxNpcLevel) {
            errors.add("MinNpcLevel must be less than or equal to MaxNpcLevel");
        }

        if ("LEVELED".equals(normalizedType)) {
            String normalizedGearType = normalizedGearType();
            if (!"WEAPON".equals(normalizedGearType) && !"ARMOR".equals(normalizedGearType)) {
                errors.add("GearType must be WEAPON or ARMOR for LEVELED entries: " + gearType);
            }
            if (gearLevelMin < 1) {
                errors.add("GearLevelMin must be at least 1");
            }
            if (gearLevelMax < gearLevelMin) {
                errors.add("GearLevelMax must be greater than or equal to GearLevelMin");
            }
        }

        return errors;
    }

    @Nonnull
    private String normalizedType() {
        return normalize(type);
    }

    @Nonnull
    private String normalizedGearType() {
        return normalize(gearType);
    }

    @Nonnull
    private String normalizedItemId() {
        return itemId == null ? "" : itemId.trim();
    }

    @Nullable
    private static Integer normalizeNpcLevelBound(int bound) {
        return bound == 0 ? null : bound;
    }

    @Nonnull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}