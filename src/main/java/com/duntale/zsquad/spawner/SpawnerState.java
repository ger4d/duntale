package com.duntale.zsquad.spawner;

/**
 * Lifecycle state of a spawner entity.
 *
 * @since 1.1.0
 */
public enum SpawnerState {
    /** Waiting for trigger condition (e.g. player proximity). */
    DORMANT,
    /** Trigger fired, actively spawning enemies. */
    ACTIVE,
    /** Fixed spawner has spawned all its enemies. */
    DEPLETED,
    /** Manually disabled (e.g. dungeon teardown). */
    DISABLED
}
