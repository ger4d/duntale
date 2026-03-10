package com.duntale.zsquad.spawner;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.model.SpawnerDefinition;
import com.duntale.zsquad.ZSquadPlugin;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * ECS component holding the runtime state of a dungeon spawner.
 *
 * @since 1.1.0
 */
public class SpawnerComponent implements Component<EntityStore> {

    /** Codec for serialization during chunk save/load. Persists definition and runtime progress. */
    @Nonnull
    public static final BuilderCodec<SpawnerComponent> CODEC = BuilderCodec.builder(
                    SpawnerComponent.class, SpawnerComponent::new)
            .append(new KeyedCodec<>("Definition", SpawnerDefinitionCodec.INSTANCE),
                    (c, v) -> c.definition = v,
                    c -> c.definition)
            .add()
            .append(new KeyedCodec<>("State", Codec.STRING),
                    (c, v) -> c.state = SpawnerState.valueOf(v),
                    c -> c.state.name())
            .add()
            .append(new KeyedCodec<>("SpawnedCount", Codec.INTEGER),
                    (c, v) -> c.spawnedCount = v,
                    c -> c.spawnedCount)
            .add()
            .append(new KeyedCodec<>("SpawnBudgetRemaining", Codec.INTEGER),
                    (c, v) -> c.spawnBudgetRemaining = v,
                    c -> c.spawnBudgetRemaining)
            .add()
            .append(new KeyedCodec<>("SpawnOffsetIndex", Codec.INTEGER),
                    (c, v) -> c.spawnOffsetIndex = v,
                    c -> c.spawnOffsetIndex)
            .add()
            .build();

    private SpawnerDefinition definition;
    private SpawnerState state;
    private int spawnedCount;
    private int spawnBudgetRemaining;
    private final List<Ref<EntityStore>> aliveNpcs;
    private int spawnOffsetIndex;

    /**
     * No-arg constructor required by ECS component registration.
     * Creates a placeholder component — must be replaced with a real definition before use.
     */
    public SpawnerComponent() {
        this.definition = null;
        this.state = SpawnerState.DORMANT;
        this.spawnedCount = 0;
        this.spawnBudgetRemaining = 0;
        this.aliveNpcs = new ArrayList<>();
        this.spawnOffsetIndex = 0;
    }

    /**
     * Creates a new spawner component from a dungeon-gen definition.
     *
     * @param definition the spawner definition from the blueprint
     * @since 1.1.0
     */
    public SpawnerComponent(@Nonnull SpawnerDefinition definition) {
        this.definition = definition;
        this.state = SpawnerState.DORMANT;
        this.spawnedCount = 0;
        this.spawnBudgetRemaining = 0;
        this.aliveNpcs = new ArrayList<>();
        this.spawnOffsetIndex = 0;
    }

    /**
     * Returns the registered component type for {@link SpawnerComponent}.
     *
     * @return the component type
     * @since 1.1.0
     */
    @Nonnull
    public static ComponentType<EntityStore, SpawnerComponent> getComponentType() {
        return ZSquadPlugin.get().getSpawnerComponentType();
    }

    /**
     * Returns the spawner definition from dungeon-gen.
     *
     * @return the spawner definition
     * @since 1.1.0
     */
    @Nonnull
    public SpawnerDefinition getDefinition() {
        return definition;
    }

    /**
     * Returns the current lifecycle state of this spawner.
     *
     * @return the spawner state
     * @since 1.1.0
     */
    @Nonnull
    public SpawnerState getState() {
        return state;
    }

    /**
     * Returns how many NPCs have been spawned in total by this spawner.
     *
     * @return the total spawned count
     * @since 1.1.0
     */
    public int getSpawnedCount() {
        return spawnedCount;
    }

    /**
     * Returns how many more NPCs this spawner can still spawn.
     *
     * @return the remaining spawn budget
     * @since 1.1.0
     */
    public int getSpawnBudgetRemaining() {
        return spawnBudgetRemaining;
    }

    /**
     * Returns the list of refs to currently alive NPCs spawned by this spawner.
     *
     * @return the alive NPC refs (mutable list)
     * @since 1.1.0
     */
    @Nonnull
    public List<Ref<EntityStore>> getAliveNpcs() {
        return aliveNpcs;
    }

    /**
     * Sets the lifecycle state of this spawner.
     *
     * @param state the new state
     * @since 1.1.0
     */
    public void setState(@Nonnull SpawnerState state) {
        this.state = state;
    }

    /**
     * Activate the spawner: set budget to {@code totalCount} and transition to {@link SpawnerState#ACTIVE}.
     *
     * @since 1.1.0
     */
    public void activate() {
        this.spawnBudgetRemaining = definition.totalCount();
        this.state = SpawnerState.ACTIVE;
    }

    /**
     * Record a successful spawn. Decrements budget and increments spawnedCount.
     *
     * @param npcRef ref to the spawned NPC entity
     * @since 1.1.0
     */
    public void recordSpawn(@Nonnull Ref<EntityStore> npcRef) {
        this.aliveNpcs.add(npcRef);
        this.spawnedCount++;
        this.spawnBudgetRemaining--;
    }

    /**
     * Reserve a spawn budget slot without recording a ref yet.
     * Used when the actual spawn is deferred via {@code World.execute()}.
     *
     * @since 1.1.0
     */
    public void reserveBudget() {
        this.spawnedCount++;
        this.spawnBudgetRemaining--;
    }

    /**
     * Track a spawned NPC ref for alive-pruning.
     * Called from deferred spawn callbacks after the entity is added to the store.
     *
     * @param npcRef ref to the spawned NPC entity
     * @since 1.1.0
     */
    public void addAliveNpc(@Nonnull Ref<EntityStore> npcRef) {
        this.aliveNpcs.add(npcRef);
    }

    /**
     * Get the next spawn position from the pre-computed offsets, cycling through them.
     * If no offsets are defined, returns the spawner's own position.
     *
     * @return the next spawn offset
     * @since 1.1.0
     */
    @Nonnull
    public Vec3i nextSpawnOffset() {
        List<Vec3i> offsets = definition.spawnOffsets();
        if (offsets.isEmpty()) {
            return new Vec3i(definition.x(), definition.y(), definition.z());
        }
        Vec3i offset = offsets.get(spawnOffsetIndex % offsets.size());
        spawnOffsetIndex++;
        return offset;
    }

    /**
     * Prune dead/invalid NPC refs from the alive list.
     *
     * @since 1.1.0
     */
    public void pruneDeadNpcs() {
        aliveNpcs.removeIf(ref -> !ref.isValid());
    }

    @Nullable
    @Override
    public Component<EntityStore> clone() {
        SpawnerComponent copy = new SpawnerComponent(definition);
        copy.state = this.state;
        copy.spawnedCount = this.spawnedCount;
        copy.spawnBudgetRemaining = this.spawnBudgetRemaining;
        copy.spawnOffsetIndex = this.spawnOffsetIndex;
        return copy;
    }
}
