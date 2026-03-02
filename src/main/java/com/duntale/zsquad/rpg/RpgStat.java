package com.duntale.zsquad.rpg;

/**
 * Enumeration of all RPG stats that can be levelled per player.
 */
public enum RpgStat {
    /** Affects CTM movement velocity. */
    SPEED,
    /** Affects outgoing damage (multiplier). */
    STRENGTH,
    /** Affects loot drop chance and bonus rolls. */
    LUCK,
    /** Affects max Stamina entity stat. */
    STAMINA,
    /** Affects CTM attack throttle interval. */
    AGILITY,
    /** Reduces incoming damage (percentage damage reduction). */
    RESISTANCE,
    /** Increases max Health. */
    VITALITY
}
