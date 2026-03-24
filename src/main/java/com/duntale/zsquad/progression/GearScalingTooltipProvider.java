package com.duntale.zsquad.progression;

import org.herolias.tooltips.api.TooltipData;
import org.herolias.tooltips.api.TooltipPriority;
import org.herolias.tooltips.api.TooltipProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tooltip provider that enriches leveled weapons and armor with scaled stat info.
 *
 * <p>Reads {@code zsquad_weapon_level} / {@code zsquad_armor_level} from item metadata
 * and renders the effective scaled damage or DR into the tooltip via DynamicTooltipsLib.
 *
 * <p>Requires DynamicTooltipsLib to be loaded. Registration is guarded by an optional
 * dependency check in {@link com.duntale.zsquad.ZSquadPlugin}.
 */
public class GearScalingTooltipProvider implements TooltipProvider {

    private static final String PROVIDER_ID = "zsquad_gear_scaling";

    private static final String COLOR_GOLD = "#FFD700";
    private static final String COLOR_CYAN = "#55FFFF";
    private static final String COLOR_GRAY = "#AAAAAA";
    private static final String COLOR_GREEN = "#55FF55";
    private static final String COLOR_WHITE = "#FFFFFF";
    private static final String COLOR_YELLOW = "#FFEE55";

    private final AssetCatalog assetCatalog;

    /**
     * Creates a new tooltip provider backed by the given asset catalog.
     *
     * @param assetCatalog the asset catalog for weapon/armor lookups
     */
    public GearScalingTooltipProvider(@Nonnull AssetCatalog assetCatalog) {
        this.assetCatalog = assetCatalog;
    }

    @Nonnull
    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public int getPriority() {
        return TooltipPriority.EARLY;
    }

    @Nullable
    @Override
    public TooltipData getTooltipData(@Nonnull String itemId, @Nullable String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }

        // Quick check — avoid parsing unless metadata plausibly contains our keys
        boolean hasWeapon = metadata.contains("zsquad_weapon_level");
        boolean hasArmor = metadata.contains("zsquad_armor_level");
        if (!hasWeapon && !hasArmor) {
            return null;
        }

        Integer level = extractLevel(metadata, hasWeapon ? "zsquad_weapon_level" : "zsquad_armor_level");
        if (level == null || level < 1 || level > 60) {
            return null;
        }

        // Extract per-item variance (defaults to 1.0 if not set)
        Float variance = extractFloat(metadata,
                hasWeapon ? "zsquad_weapon_variance" : "zsquad_armor_variance");
        float var = variance != null ? variance : 1.0f;

        if (hasWeapon) {
            return buildWeaponTooltip(itemId, level, var);
        } else {
            return buildArmorTooltip(itemId, level, var);
        }
    }

    /**
     * Builds a tooltip for a leveled weapon.
     */
    @Nullable
    private TooltipData buildWeaponTooltip(@Nonnull String itemId, int level, float variance) {
        AssetCatalog.WeaponBaseRow base = assetCatalog.getWeaponBase(itemId);
        float damageMult = CombatScaling.weaponMult(level);

        // Include variance in hash so different rolls get distinct virtual IDs
        int varHash = Math.round(variance * 1000);
        TooltipData.Builder builder = TooltipData.builder()
                .hashInput("zsquad_wl:" + level + ":" + varHash);

        // Level tag
        builder.addLine(colorTag(COLOR_CYAN, "Lv." + level) + " " + colorTag(COLOR_GRAY, "Dungeon Weapon"));

        if (base != null) {
            float scaledDmg = base.baseDamage() * damageMult * variance;
            builder.addLine(
                    colorTag(COLOR_GRAY, "Power: ") +
                    colorTag(COLOR_YELLOW, String.format("%.1f", scaledDmg))
            );

            if (base.family() != null) {
                builder.addLine(colorTag(COLOR_GRAY, "Type: ") + colorTag(COLOR_WHITE, base.family()));
            }
        } else {
            builder.addLine(
                    colorTag(COLOR_GRAY, "Dmg Mult: ") +
                    colorTag(COLOR_YELLOW, String.format("×%.2f", damageMult * variance))
            );
        }

        return builder.build();
    }

    /**
     * Builds a tooltip for a leveled armor piece.
     */
    @Nullable
    private TooltipData buildArmorTooltip(@Nonnull String itemId, int level, float variance) {
        AssetCatalog.ArmorBaseRow base = assetCatalog.getArmorBase(itemId);
        float baseResist = base != null ? base.physResist() : 0f;
        float effectiveDr = CombatScaling.armorDR(baseResist, level) * variance;

        int varHash = Math.round(variance * 1000);
        TooltipData.Builder builder = TooltipData.builder()
                .hashInput("zsquad_al:" + level + ":" + varHash);

        // Level tag
        builder.addLine(colorTag(COLOR_CYAN, "Lv." + level) + " " + colorTag(COLOR_GRAY, "Dungeon Armor"));

        builder.addLine(
                colorTag(COLOR_GRAY, "DR: ") +
                colorTag(COLOR_YELLOW, String.format("%.1f%%", effectiveDr * 100))
        );

        if (base != null) {
            builder.addLine(
                    colorTag(COLOR_GRAY, "Phys Resist: ") +
                    colorTag(COLOR_WHITE, String.format("%.1f%%", base.physResist() * 100))
            );
            if (base.projResist() > 0) {
                builder.addLine(
                        colorTag(COLOR_GRAY, "Proj Resist: ") +
                        colorTag(COLOR_WHITE, String.format("%.1f%%", base.projResist() * 100))
                );
            }
            if (base.healthBonus() > 0) {
                builder.addLine(
                        colorTag(COLOR_GRAY, "Health: ") +
                        colorTag(COLOR_GREEN, "+" + base.healthBonus())
                );
            }
            if (base.slot() != null) {
                builder.addLine(colorTag(COLOR_GRAY, "Slot: ") + colorTag(COLOR_WHITE, base.slot()));
            }
        }

        return builder.build();
    }

    /**
     * Extracts an integer level value from BSON-style JSON metadata.
     *
     * <p>The metadata is a serialized BsonDocument like:
     * {@code {"zsquad_weapon_level": 15}} or
     * {@code {"zsquad_weapon_level": {"$numberInt": "15"}}}
     *
     * <p>This uses lightweight string parsing to avoid a BSON dependency.
     */
    @Nullable
    private static Integer extractLevel(@Nonnull String metadata, @Nonnull String key) {
        int keyIdx = metadata.indexOf("\"" + key + "\"");
        if (keyIdx < 0) {
            return null;
        }

        // Find the colon after the key
        int colonIdx = metadata.indexOf(':', keyIdx + key.length() + 2);
        if (colonIdx < 0) {
            return null;
        }

        // Skip whitespace
        int valueStart = colonIdx + 1;
        while (valueStart < metadata.length() && Character.isWhitespace(metadata.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= metadata.length()) {
            return null;
        }

        char firstChar = metadata.charAt(valueStart);

        // Handle direct integer: "key": 15
        if (firstChar == '-' || Character.isDigit(firstChar)) {
            return parseIntAt(metadata, valueStart);
        }

        // Handle BSON extended JSON: "key": {"$numberInt": "15"}
        if (firstChar == '{') {
            int numIntIdx = metadata.indexOf("$numberInt", valueStart);
            if (numIntIdx < 0) {
                return null;
            }
            int quoteStart = metadata.indexOf('"', numIntIdx + "$numberInt".length() + 1);
            if (quoteStart < 0) {
                return null;
            }
            quoteStart++; // skip opening quote
            return parseIntAt(metadata, quoteStart);
        }

        return null;
    }

    /**
     * Parses an integer starting at the given position in the string.
     */
    @Nullable
    private static Integer parseIntAt(@Nonnull String s, int start) {
        int end = start;
        if (end < s.length() && s.charAt(end) == '-') {
            end++;
        }
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Integer.parseInt(s.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Extracts a float value from BSON-style JSON metadata.
     *
     * <p>Handles plain numbers ({@code "key": 1.03}) and BSON extended JSON
     * ({@code "key": {"$numberDouble": "1.03"}}).
     */
    @Nullable
    private static Float extractFloat(@Nonnull String metadata, @Nonnull String key) {
        int keyIdx = metadata.indexOf("\"" + key + "\"");
        if (keyIdx < 0) {
            return null;
        }

        int colonIdx = metadata.indexOf(':', keyIdx + key.length() + 2);
        if (colonIdx < 0) {
            return null;
        }

        int valueStart = colonIdx + 1;
        while (valueStart < metadata.length() && Character.isWhitespace(metadata.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= metadata.length()) {
            return null;
        }

        char firstChar = metadata.charAt(valueStart);

        // Handle direct number: "key": 1.03
        if (firstChar == '-' || firstChar == '.' || Character.isDigit(firstChar)) {
            return parseFloatAt(metadata, valueStart);
        }

        // Handle BSON extended JSON: "key": {"$numberDouble": "1.03"} or "$numberFloat"
        if (firstChar == '{') {
            int numIdx = metadata.indexOf("$number", valueStart);
            if (numIdx < 0) {
                return null;
            }
            int quoteStart = metadata.indexOf('"', metadata.indexOf(':', numIdx) + 1);
            if (quoteStart < 0) {
                return null;
            }
            quoteStart++;
            return parseFloatAt(metadata, quoteStart);
        }

        return null;
    }

    /**
     * Parses a float starting at the given position in the string.
     */
    @Nullable
    private static Float parseFloatAt(@Nonnull String s, int start) {
        int end = start;
        if (end < s.length() && s.charAt(end) == '-') {
            end++;
        }
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Float.parseFloat(s.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Wraps text in a Hytale color markup tag.
     */
    @Nonnull
    private static String colorTag(@Nonnull String hex, @Nonnull String text) {
        return "<color is=\"" + hex + "\">" + text + "</color>";
    }
}
