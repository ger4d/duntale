package com.duntale.progression;

import com.duntale.config.asset.NpcArchetypeConfigAsset;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Runtime registry resolving an NPC role name to its archetype anchor values.
 *
 * <p>Rebuilds an immutable {@code role -> }{@link ResolvedArchetype} snapshot from
 * {@link NpcArchetypeConfigAsset} on the initial asset load and on every hot reload, falling back to
 * an empty map when no asset is present (every role then takes the legacy scaling path). Reads via
 * {@link #resolve(String)} are lock-free against a {@code volatile} reference.
 *
 * <p>Per-role flavor offsets are clamped to &plusmn;{@value #MAX_OFFSET} at load and pre-applied into
 * the resolved anchor values, so hot-path lookups need no arithmetic. The clamp is a hard product
 * bound: a role may only deviate this far from its archetype anchor, otherwise the normalization
 * (keeping same-archetype enemies comparable) would be defeated by outliers.
 *
 * <p>Lifecycle: construct in the plugin's {@code setup()} (registers the reload listener), call
 * {@link #refresh()} in {@code start()} (initial population once asset stores are loaded), and
 * {@link #shutdown()} on plugin teardown. Mirrors {@code RpgConfig}.
 */
public final class NpcArchetypeRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Hard bound on how far a role may deviate from its archetype anchor (&plusmn;15%). */
    public static final float MAX_OFFSET = 0.15f;

    /** Current snapshot. Volatile so {@link #resolve(String)} publishes refreshes to all threads. */
    private volatile Map<String, ResolvedArchetype> current = Map.of();

    @Nullable
    private final EventRegistry eventRegistry;

    /**
     * Creates the registry and subscribes to asset hot-reload events for
     * {@link NpcArchetypeConfigAsset}.
     */
    public NpcArchetypeRegistry() {
        this.eventRegistry = new EventRegistry(
                new CopyOnWriteArrayList<>(), () -> true, "NpcArchetypeRegistry",
                HytaleServer.get().getEventBus());
        this.eventRegistry.enable();
        registerReloadListener();
    }

    private NpcArchetypeRegistry(@Nonnull Map<String, ResolvedArchetype> snapshot) {
        this.eventRegistry = null;
        this.current = snapshot;
    }

    /**
     * Test hook: builds a registry with a fixed snapshot and no event subscription.
     *
     * @param snapshot the {@code role -> }{@link ResolvedArchetype} map to serve
     * @return a registry serving {@code snapshot}
     */
    @Nonnull
    static NpcArchetypeRegistry forTest(@Nonnull Map<String, ResolvedArchetype> snapshot) {
        return new NpcArchetypeRegistry(snapshot);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerReloadListener() {
        this.eventRegistry.registerGlobal((Class) LoadedAssetsEvent.class,
                (Consumer<LoadedAssetsEvent>) this::onAssetsLoaded);
    }

    private void onAssetsLoaded(@Nonnull LoadedAssetsEvent<?, ?, ?> event) {
        if (event.getAssetClass() != NpcArchetypeConfigAsset.class) {
            return;
        }
        refresh();
    }

    /**
     * Resolves the archetype mapping for an NPC role.
     *
     * @param roleName the NPC role name (e.g. "Werewolf")
     * @return the resolved archetype values, or {@code null} when the role is unmapped
     */
    @Nullable
    public ResolvedArchetype resolve(@Nonnull String roleName) {
        return current.get(roleName);
    }

    /**
     * Rebuilds the in-memory snapshot from {@link NpcArchetypeConfigAsset}, or resets it to an empty
     * map when no asset is loaded. Safe to call from any thread — it only publishes a
     * {@code volatile} reference to an immutable snapshot.
     */
    public void refresh() {
        NpcArchetypeConfigAsset asset = NpcArchetypeConfigAsset.get();
        Map<String, ResolvedArchetype> updated =
                asset != null ? build(asset.getArchetypes(), asset.getRoles()) : Map.of();
        current = updated;
        LOGGER.atInfo().log("NPC archetype registry %s (%d roles mapped)",
                asset != null ? "loaded from asset" : "reset to empty", updated.size());
    }

    /**
     * Deregisters the hot-reload listener. Call on plugin teardown.
     */
    public void shutdown() {
        if (this.eventRegistry != null) {
            this.eventRegistry.shutdownAndCleanup(false);
        }
    }

    /**
     * Builds the immutable {@code role -> }{@link ResolvedArchetype} snapshot from raw config entries,
     * clamping offsets and skipping roles whose archetype is unknown.
     *
     * @param archetypeEntries the archetype anchor entries
     * @param roleEntries      the per-role mapping entries
     * @return the resolved snapshot
     */
    @Nonnull
    static Map<String, ResolvedArchetype> build(
            @Nonnull NpcArchetypeConfigAsset.ArchetypeEntry[] archetypeEntries,
            @Nonnull NpcArchetypeConfigAsset.RoleEntry[] roleEntries) {
        Map<String, ArchetypeAnchor> anchors = new HashMap<>();
        for (NpcArchetypeConfigAsset.ArchetypeEntry entry : archetypeEntries) {
            if (entry.getName() == null || entry.getName().isBlank()) {
                continue;
            }
            anchors.put(entry.getName(), new ArchetypeAnchor(entry.getBaseHp(), entry.getBaseDamage()));
        }

        Map<String, ResolvedArchetype> resolved = new HashMap<>();
        for (NpcArchetypeConfigAsset.RoleEntry role : roleEntries) {
            String roleName = role.getRole();
            if (roleName == null || roleName.isBlank()) {
                continue;
            }
            ArchetypeAnchor anchor = anchors.get(role.getArchetype());
            if (anchor == null) {
                LOGGER.atWarning().log(
                        "NPC role %s maps to unknown archetype '%s' — treating as unmapped (legacy scaling)",
                        roleName, role.getArchetype());
                continue;
            }

            float hpOffset = clampOffset(roleName, "HpOffset", role.getHpOffset());
            float damageOffset = clampOffset(roleName, "DamageOffset", role.getDamageOffset());

            int effectiveBaseHp = Math.max(1, Math.round(anchor.baseHp() * (1.0f + hpOffset)));
            float effectiveBaseDamage = anchor.baseDamage() * (1.0f + damageOffset);

            resolved.put(roleName, new ResolvedArchetype(
                    role.getArchetype(),
                    effectiveBaseHp,
                    effectiveBaseDamage,
                    role.getAssetBaseDamage()
            ));
        }
        return Map.copyOf(resolved);
    }

    private static float clampOffset(@Nonnull String roleName, @Nonnull String field, float offset) {
        float clamped = Math.clamp(offset, -MAX_OFFSET, MAX_OFFSET);
        if (clamped != offset) {
            LOGGER.atWarning().log(
                    "NPC role %s %s %.3f out of +/-%.2f bound — clamped to %.3f",
                    roleName, field, offset, MAX_OFFSET, clamped);
        }
        return clamped;
    }

    // ============================================
    // Value types
    // ============================================

    /**
     * A raw archetype anchor as authored in the config.
     *
     * @param baseHp     the level-1 base HP anchor
     * @param baseDamage the level-1 base damage anchor
     */
    public record ArchetypeAnchor(int baseHp, float baseDamage) {
    }

    /**
     * A role's resolved archetype values, with flavor offsets pre-applied.
     *
     * @param name                the archetype name (e.g. "Heavy")
     * @param effectiveBaseHp      the offset-applied base HP feeding {@code CombatScaling.npcScaledHp}
     * @param effectiveBaseDamage  the offset-applied base damage target for the corrective ratio
     * @param assetBaseDamage      the offline-parsed average asset attack damage for this role; the
     *                             damage path falls back to legacy scaling when this is {@code <= 0}
     */
    public record ResolvedArchetype(
            @Nonnull String name,
            int effectiveBaseHp,
            float effectiveBaseDamage,
            float assetBaseDamage
    ) {
    }
}
