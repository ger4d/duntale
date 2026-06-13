package com.duntale.loot;

import com.duntale.rpg.RpgStat;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A single rarity-granted RPG-stat attribute on a piece of gear (e.g. {@code STRENGTH +5}).
 *
 * <p>Attributes are rolled at generation by {@code RarityRollService} and stamped on the item under
 * the {@code duntale_attributes} metadata key (see {@code GearLevelService}) as a compact string of
 * {@code STAT:value} pairs separated by {@code ;} (e.g. {@code "STRENGTH:5;VITALITY:3"}). While the
 * gear is equipped, {@code GearAttributeService} sums these into the player's effective stats
 * without ever mutating the persisted {@code RpgProfile}.
 *
 * @param stat  the RPG stat boosted
 * @param value the flat bonus added to that stat
 */
public record GearAttribute(@Nonnull RpgStat stat, int value) {

    private static final char PAIR_SEPARATOR = ';';
    private static final char KEY_VALUE_SEPARATOR = ':';

    /**
     * Encodes a list of attributes into the compact metadata string form.
     *
     * @param attributes the attributes to encode
     * @return the encoded string (e.g. {@code "STRENGTH:5;VITALITY:3"}), empty for an empty list
     */
    @Nonnull
    public static String encode(@Nonnull List<GearAttribute> attributes) {
        StringBuilder builder = new StringBuilder();
        for (GearAttribute attribute : attributes) {
            if (!builder.isEmpty()) {
                builder.append(PAIR_SEPARATOR);
            }
            builder.append(attribute.stat().name())
                    .append(KEY_VALUE_SEPARATOR)
                    .append(attribute.value());
        }
        return builder.toString();
    }

    /**
     * Decodes the compact metadata string form back into a list of attributes.
     *
     * <p>Malformed or unknown-stat pairs are skipped so a partially corrupt tag still yields the
     * attributes it can parse.
     *
     * @param encoded the encoded string, or {@code null}
     * @return the decoded attributes, never {@code null}
     */
    @Nonnull
    public static List<GearAttribute> decode(@Nullable String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<GearAttribute> attributes = new ArrayList<>();
        for (String pair : encoded.split(String.valueOf(PAIR_SEPARATOR))) {
            int sep = pair.indexOf(KEY_VALUE_SEPARATOR);
            if (sep <= 0 || sep == pair.length() - 1) {
                continue;
            }
            RpgStat stat = parseStat(pair.substring(0, sep).trim());
            if (stat == null) {
                continue;
            }
            try {
                attributes.add(new GearAttribute(stat, Integer.parseInt(pair.substring(sep + 1).trim())));
            } catch (NumberFormatException ignored) {
                // Skip the malformed pair, keep the rest.
            }
        }
        return List.copyOf(attributes);
    }

    @Nullable
    private static RpgStat parseStat(@Nonnull String name) {
        try {
            return RpgStat.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
