package com.duntale.dungeon;

/**
 * Lifecycle states for a persisted dungeon instance.
 *
 * <p>The dungeon instance service owns transitions between these states as worlds are created,
 * advanced, and eventually ended.
 *
 * <pre>
 * CREATING -> ACTIVE -> TRANSITIONING -> ACTIVE
 * CREATING -> ENDED
 * ACTIVE -> ENDED
 * TRANSITIONING -> ENDED
 * </pre>
 *
 * @since 1.6.0
 */
public enum DungeonInstanceState {
    CREATING,
    ACTIVE,
    TRANSITIONING,
    ENDED
}
