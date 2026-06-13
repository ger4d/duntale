package com.duntale.progression;

import com.duntale.loot.GearAttribute;
import com.duntale.loot.Rarity;
import com.duntale.loot.RarityRegistry;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import org.herolias.tooltips.api.ItemVisualOverrides;
import org.herolias.tooltips.api.TooltipData;
import org.herolias.tooltips.api.TooltipPriority;
import org.herolias.tooltips.api.TooltipProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Tooltip provider that enriches leveled weapons and armor with scaled stat info.
 *
 * <p>Reads {@code duntale_weapon_level} / {@code duntale_armor_level} from item metadata
 * and renders the effective scaled damage or DR into the tooltip via DynamicTooltipsLib.
 *
 * <p>Requires DynamicTooltipsLib to be loaded. Registration is guarded by an optional
 * dependency check in {@link com.duntale.DuntalePlugin}.
 */
public class GearScalingTooltipProvider implements TooltipProvider {

    private static final String PROVIDER_ID = "duntale_gear_scaling";

    private static final String COLOR_CYAN = "#55FFFF";
    private static final String COLOR_GRAY = "#AAAAAA";
    private static final String COLOR_GREEN = "#55FF55";
    private static final String COLOR_WHITE = "#FFFFFF";
    private static final String COLOR_YELLOW = "#FFEE55";

    private final AssetCatalog assetCatalog;
    private final GearCurveRegistry gearCurves;
    private final RarityRegistry rarityRegistry;

    /**
     * Creates a new tooltip provider backed by the given asset catalog, gear curves, and rarity
     * tuning.
     *
     * @param assetCatalog   the asset catalog for weapon/armor lookups
     * @param gearCurves     the authored gear-curve registry (empty snapshot drives legacy display)
     * @param rarityRegistry the rarity registry for rarity color/name styling
     */
    public GearScalingTooltipProvider(@Nonnull AssetCatalog assetCatalog,
                                      @Nonnull GearCurveRegistry gearCurves,
                                      @Nonnull RarityRegistry rarityRegistry) {
        this.assetCatalog = assetCatalog;
        this.gearCurves = gearCurves;
        this.rarityRegistry = rarityRegistry;
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
        boolean hasWeapon = metadata.contains("duntale_weapon_level");
        boolean hasArmor = metadata.contains("duntale_armor_level");
        if (!hasWeapon && !hasArmor) {
            return null;
        }

        Integer level = extractLevel(metadata, hasWeapon ? "duntale_weapon_level" : "duntale_armor_level");
        if (level == null || !CombatScaling.isSupportedLevel(level)) {
            return null;
        }

        // Extract per-item variance (defaults to 1.0 if not set)
        Float variance = extractFloat(metadata,
                hasWeapon ? "duntale_weapon_variance" : "duntale_armor_variance");
        float var = variance != null ? variance : 1.0f;

        Rarity rarity = Rarity.fromId(extractString(metadata, "duntale_rarity"));
        List<GearAttribute> attributes = GearAttribute.decode(extractString(metadata, "duntale_attributes"));

        if (hasWeapon) {
            return buildWeaponTooltip(itemId, level, var, rarity, attributes);
        } else {
            return buildArmorTooltip(itemId, level, var, rarity, attributes);
        }
    }

    /**
     * Builds a tooltip for a leveled weapon.
     */
    @Nullable
    private TooltipData buildWeaponTooltip(@Nonnull String itemId, int level, float variance,
                                           @Nullable Rarity rarity, @Nonnull List<GearAttribute> attributes) {
        AssetCatalog.WeaponBaseRow base = assetCatalog.getWeaponBase(itemId);

        // Include variance + rarity + attributes in hash so different rolls get distinct virtual IDs
        int varHash = Math.round(variance * 1000);
        TooltipData.Builder builder = TooltipData.builder()
                .hashInput("duntale_wl:" + level + ":" + varHash + ":" + rarityHash(rarity, attributes));

        applyRarityVisuals(builder, rarity);
        addRarityLine(builder, rarity);
        // Level tag
        builder.addLine(colorTag(COLOR_CYAN, "Lv." + level) + " " + colorTag(COLOR_GRAY, "Dungeon Weapon"));

        if (gearCurves.isLoaded()) {
            // Authored per-hit: family anchor on the level curve, severed from the asset number,
            // nudged by the stamped rarity (1.0 default when unstamped).
            String family = base != null ? base.family() : null;
            float anchor = gearCurves.weaponAnchor(family);
            float nudge = gearCurves.rarityNudge(rarity != null ? rarity.id() : null);
            float perHit = CombatScaling.weaponAuthoredPerHit(anchor, level, nudge) * variance;
            builder.addLine(
                    colorTag(COLOR_GRAY, "Power: ") +
                    colorTag(COLOR_YELLOW, String.format("%.1f", perHit))
            );
            if (family != null) {
                builder.addLine(colorTag(COLOR_GRAY, "Type: ") + colorTag(COLOR_WHITE, family));
            }
        } else if (base != null) {
            float scaledDmg = base.baseDamage() * CombatScaling.weaponMult(level) * variance;
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
                    colorTag(COLOR_YELLOW, String.format("×%.2f", CombatScaling.weaponMult(level) * variance))
            );
        }

        addAttributeLines(builder, attributes);
        return builder.build();
    }

    /**
     * Builds a tooltip for a leveled armor piece.
     */
    @Nullable
    private TooltipData buildArmorTooltip(@Nonnull String itemId, int level, float variance,
                                          @Nullable Rarity rarity, @Nonnull List<GearAttribute> attributes) {
        AssetCatalog.ArmorBaseRow base = assetCatalog.getArmorBase(itemId);
        String slot = base != null ? base.slot() : null;

        // Authored DR: slot share of the level-driven budget, severed from the asset resist. Falls
        // back to legacy asset-resist DR when no curves are loaded or the slot is unmapped.
        Float share = slot != null && gearCurves.isLoaded() ? gearCurves.slotShare(slot) : null;
        float effectiveDr;
        if (share != null) {
            float nudge = gearCurves.rarityNudge(rarity != null ? rarity.id() : null);
            effectiveDr = CombatScaling.armorBudgetDR(share, level,
                    gearCurves.drBudgetMin(), gearCurves.drBudgetMax()) * nudge * variance;
        } else {
            float baseResist = base != null ? base.physResist() : 0f;
            effectiveDr = CombatScaling.armorDR(baseResist, level) * variance;
        }

        int varHash = Math.round(variance * 1000);
        TooltipData.Builder builder = TooltipData.builder()
                .hashInput("duntale_al:" + level + ":" + varHash + ":" + rarityHash(rarity, attributes));

        applyRarityVisuals(builder, rarity);
        addRarityLine(builder, rarity);
        // Level tag
        builder.addLine(colorTag(COLOR_CYAN, "Lv." + level) + " " + colorTag(COLOR_GRAY, "Dungeon Armor"));

        builder.addLine(
                colorTag(COLOR_GRAY, "DR: ") +
                colorTag(COLOR_YELLOW, String.format("%.1f%%", effectiveDr * 100))
        );

        if (slot != null) {
            builder.addLine(colorTag(COLOR_GRAY, "Slot: ") + colorTag(COLOR_WHITE, slot));
        }
        // With the authored armor-HP curve loaded, the engine HP is suppressed and replaced by the
        // slot/level budget; otherwise the engine still grants the asset's flat armor HP.
        Float hpShare = slot != null && gearCurves.hasArmorHp() ? gearCurves.armorHpShare(slot) : null;
        int healthBonus;
        if (hpShare != null) {
            healthBonus = Math.round(CombatScaling.armorBudgetHp(hpShare, level,
                    gearCurves.hpBudgetMin(), gearCurves.hpBudgetMax()));
        } else {
            healthBonus = base != null ? base.healthBonus() : 0;
        }
        if (healthBonus > 0) {
            builder.addLine(
                    colorTag(COLOR_GRAY, "Health: ") +
                    colorTag(COLOR_GREEN, "+" + healthBonus)
            );
        }

        addAttributeLines(builder, attributes);
        return builder.build();
    }

    /**
     * Adds a colored rarity name line at the top of the tooltip when a rarity is stamped.
     */
    private void addRarityLine(@Nonnull TooltipData.Builder builder, @Nullable Rarity rarity) {
        if (rarity == null) {
            return;
        }
        builder.addLine(colorTag(rarityRegistry.displayColor(rarity), rarityRegistry.displayName(rarity)));
    }

    /**
     * Recolors the item's quality "chrome" (tooltip border, inventory slot/icon background, name +
     * quality-word color) to match the stamped Duntale rarity, via DynamicTooltipsLib's per-stack
     * {@link ItemVisualOverrides}.
     *
     * <p>The engine derives that chrome from the item <em>type's</em> static quality, so two stacks
     * of the same item id can't normally differ. DTL works around this by minting a per-stack
     * virtual item: pointing {@code qualityIndex} at the engine quality tier of the same name gives
     * the matching border/slot textures, while {@code nameColor}/{@code qualityLabel} force our exact
     * palette and rarity word on the title and top-right label. The per-stack hash already varies by
     * rarity (see {@link #rarityHash}), so promoted/demoted stacks recolor independently.
     */
    private void applyRarityVisuals(@Nonnull TooltipData.Builder builder, @Nullable Rarity rarity) {
        if (rarity == null) {
            return;
        }
        ItemVisualOverrides.Builder visuals = ItemVisualOverrides.builder()
                .nameColor(rarityRegistry.displayColor(rarity))
                .qualityLabel(rarityRegistry.displayName(rarity));
        int qualityIndex = engineQualityIndex(rarity);
        if (qualityIndex >= 0) {
            visuals.qualityIndex(qualityIndex);
        }
        builder.visualOverrides(visuals.build());
    }

    /**
     * Resolves the engine {@code ItemQuality} tier index whose id matches the rarity (e.g. "Rare";
     * "Relic"/"Abyssal" resolve to the WansWonderWeapon/ZetsMysticWeapons quality assets when those
     * mods are loaded). Falls back to the {@code Legendary} tier for above-Legendary rarities whose
     * own quality isn't registered, so Relic/Abyssal still get top-tier chrome (with our name
     * color/label) on a vanilla server. Returns {@code -1} only when even that lookup fails, in which
     * case the item keeps its own tier textures and just the name color/label change.
     */
    private static int engineQualityIndex(@Nonnull Rarity rarity) {
        try {
            int index = ItemQuality.getAssetMap().getIndexOrDefault(rarity.id(), -1);
            if (index < 0 && rarity.tierIndex() > Rarity.LEGENDARY.tierIndex()) {
                index = ItemQuality.getAssetMap().getIndexOrDefault(Rarity.LEGENDARY.id(), -1);
            }
            return index;
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    /**
     * Adds one colored line per rarity-granted attribute (e.g. {@code +5 Strength}).
     */
    private static void addAttributeLines(@Nonnull TooltipData.Builder builder,
                                          @Nonnull List<GearAttribute> attributes) {
        for (GearAttribute attribute : attributes) {
            String sign = attribute.value() >= 0 ? "+" : "";
            builder.addLine(colorTag(COLOR_GREEN,
                    sign + attribute.value() + " " + titleCase(attribute.stat().name())));
        }
    }

    /**
     * Builds a stable hash fragment from the stamped rarity and attributes so distinct rolls get
     * distinct virtual tooltip IDs.
     */
    @Nonnull
    private static String rarityHash(@Nullable Rarity rarity, @Nonnull List<GearAttribute> attributes) {
        return (rarity != null ? rarity.id() : "none") + ":" + GearAttribute.encode(attributes);
    }

    @Nonnull
    private static String titleCase(@Nonnull String upper) {
        if (upper.isEmpty()) {
            return upper;
        }
        return upper.charAt(0) + upper.substring(1).toLowerCase();
    }

    /**
     * Extracts an integer level value from BSON-style JSON metadata.
     *
     * <p>The metadata is a serialized BsonDocument like:
    * {@code {"duntale_weapon_level": 15}} or
    * {@code {"duntale_weapon_level": {"$numberInt": "15"}}}
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
     * Extracts a string value from BSON-style JSON metadata.
     *
     * <p>Handles the plain form ({@code "key": "value"}) and the BSON extended-JSON object form
     * ({@code "key": {"$symbol": "value"}}) by reading the quoted string after the (inner) colon.
     * The Duntale string keys ({@code duntale_rarity}, {@code duntale_attributes}) never embed a
     * quote, so a simple quote-to-quote read is sufficient.
     */
    @Nullable
    private static String extractString(@Nonnull String metadata, @Nonnull String key) {
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
        int searchFrom = colonIdx + 1;
        if (metadata.charAt(valueStart) == '{') {
            // BSON object form: read the value string after the inner ("$type": ) colon.
            int innerColon = metadata.indexOf(':', valueStart + 1);
            if (innerColon < 0) {
                return null;
            }
            searchFrom = innerColon + 1;
        }
        int quoteStart = metadata.indexOf('"', searchFrom);
        if (quoteStart < 0) {
            return null;
        }
        int quoteEnd = metadata.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) {
            return null;
        }
        return metadata.substring(quoteStart + 1, quoteEnd);
    }

    /**
     * Wraps text in a Hytale color markup tag.
     */
    @Nonnull
    private static String colorTag(@Nonnull String hex, @Nonnull String text) {
        return "<color is=\"" + hex + "\">" + text + "</color>";
    }
}
