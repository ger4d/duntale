package com.duntale.progression;

import com.duntale.config.asset.GearCurveConfigAsset;
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
 * Runtime registry resolving authored gear-power curves for the combat hot path.
 *
 * <p>Rebuilds an immutable {@link Snapshot} from {@link GearCurveConfigAsset} on the initial asset
 * load and on every hot reload, falling back to an {@link Snapshot#EMPTY empty snapshot} when no
 * asset is present. While empty, {@link #isLoaded()} reports {@code false} and callers take the
 * pre-authored-curve legacy scaling path, so the feature degrades safely. Reads via the
 * {@code resolve}-style accessors are lock-free against a {@code volatile} reference and never tear:
 * an in-flight read sees either the whole old snapshot or the whole new one.
 *
 * <p>Lifecycle mirrors {@link NpcArchetypeRegistry}: construct in the plugin's {@code setup()}
 * (registers the reload listener), call {@link #refresh()} in {@code start()} (initial population
 * once asset stores are loaded), and {@link #shutdown()} on plugin teardown.
 */
public final class GearCurveRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Multiplier applied when a rarity is absent or unmapped: no nudge. */
    private static final float DEFAULT_RARITY_NUDGE = 1.0f;

    /** Current snapshot. Volatile so refreshes publish to all threads without locking. */
    private volatile Snapshot current = Snapshot.EMPTY;

    @Nullable
    private final EventRegistry eventRegistry;

    /**
     * Creates the registry and subscribes to asset hot-reload events for
     * {@link GearCurveConfigAsset}.
     */
    public GearCurveRegistry() {
        this.eventRegistry = new EventRegistry(
                new CopyOnWriteArrayList<>(), () -> true, "GearCurveRegistry",
                HytaleServer.get().getEventBus());
        this.eventRegistry.enable();
        registerReloadListener();
    }

    private GearCurveRegistry(@Nonnull Snapshot snapshot) {
        this.eventRegistry = null;
        this.current = snapshot;
    }

    /**
     * Test hook: builds a registry with a fixed snapshot and no event subscription.
     *
     * @param snapshot the snapshot to serve
     * @return a registry serving {@code snapshot}
     */
    @Nonnull
    public static GearCurveRegistry forTest(@Nonnull Snapshot snapshot) {
        return new GearCurveRegistry(snapshot);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerReloadListener() {
        this.eventRegistry.registerGlobal((Class) LoadedAssetsEvent.class,
                (Consumer<LoadedAssetsEvent>) this::onAssetsLoaded);
    }

    private void onAssetsLoaded(@Nonnull LoadedAssetsEvent<?, ?, ?> event) {
        if (event.getAssetClass() != GearCurveConfigAsset.class) {
            return;
        }
        refresh();
    }

    /**
     * Returns whether authored curves are loaded. When {@code false}, callers must fall back to the
     * legacy asset-stat scaling path for every item.
     *
     * @return {@code true} when a non-empty asset snapshot is loaded
     */
    public boolean isLoaded() {
        return current.loaded();
    }

    /**
     * Resolves the level-1 per-hit anchor for a weapon family.
     *
     * @param family the weapon family (e.g. "Sword"), or {@code null} when unknown
     * @return the mapped anchor, or the configured default for unmapped/unknown families
     */
    public float weaponAnchor(@Nullable String family) {
        Snapshot snapshot = current;
        if (family == null) {
            return snapshot.defaultWeaponAnchor();
        }
        Float anchor = snapshot.weaponAnchors().get(family);
        return anchor != null ? anchor : snapshot.defaultWeaponAnchor();
    }

    /**
     * Resolves the power nudge for a rarity tier.
     *
     * @param rarity the rarity name (e.g. "Legendary"), or {@code null} when unstamped
     * @return the mapped multiplier, or {@code 1.0} when absent/unmapped
     */
    public float rarityNudge(@Nullable String rarity) {
        if (rarity == null) {
            return DEFAULT_RARITY_NUDGE;
        }
        Float nudge = current.rarityNudges().get(rarity);
        return nudge != null ? nudge : DEFAULT_RARITY_NUDGE;
    }

    /**
     * Resolves an armor slot's share of the total DR budget.
     *
     * @param slot the armor slot name (e.g. "Chest")
     * @return the slot's DR share, or {@code null} when the slot is unmapped (legacy fallback)
     */
    @Nullable
    public Float slotShare(@Nonnull String slot) {
        return current.slotShares().get(slot);
    }

    /**
     * Returns the total on-level DR at the level floor (level 1).
     *
     * @return the minimum DR budget
     */
    public float drBudgetMin() {
        return current.drBudgetMin();
    }

    /**
     * Returns the total on-level DR at the level ceiling.
     *
     * @return the maximum DR budget
     */
    public float drBudgetMax() {
        return current.drBudgetMax();
    }

    /**
     * Returns whether authored armor flat-HP curves are loaded. When {@code false}, callers must
     * leave the engine's native per-piece armor HP untouched (no authoring, no suppression).
     *
     * @return {@code true} when at least one armor HP slot share is mapped
     */
    public boolean hasArmorHp() {
        return !current.armorHpShares().isEmpty();
    }

    /**
     * Resolves an armor slot's share of the total authored HP budget.
     *
     * @param slot the armor slot name (e.g. "Chest")
     * @return the slot's HP share, or {@code null} when the slot is unmapped
     */
    @Nullable
    public Float armorHpShare(@Nonnull String slot) {
        return current.armorHpShares().get(slot);
    }

    /**
     * Returns the total on-level authored armor HP at the level floor (level 1).
     *
     * @return the minimum armor HP budget
     */
    public float hpBudgetMin() {
        return current.hpBudgetMin();
    }

    /**
     * Returns the total on-level authored armor HP at the level ceiling.
     *
     * @return the maximum armor HP budget
     */
    public float hpBudgetMax() {
        return current.hpBudgetMax();
    }

    /**
     * Rebuilds the in-memory snapshot from {@link GearCurveConfigAsset}, or resets it to
     * {@link Snapshot#EMPTY} when no asset is loaded. Safe to call from any thread — it only
     * publishes a {@code volatile} reference to an immutable snapshot.
     */
    public void refresh() {
        GearCurveConfigAsset asset = GearCurveConfigAsset.get();
        Snapshot updated = asset != null
                ? build(asset.getWeaponFamilies(), asset.getDefaultWeaponAnchor(),
                        asset.getRarityNudges(), asset.getArmorSlots(),
                        asset.getArmorDrBudgetMin(), asset.getArmorDrBudgetMax(),
                        asset.getArmorHpPerSlot(), asset.getArmorHpBudgetMin(),
                        asset.getArmorHpBudgetMax())
                : Snapshot.EMPTY;
        current = updated;
        LOGGER.atInfo().log("Gear curve registry %s (%d families, %d slots, %d rarities)",
                asset != null ? "loaded from asset" : "reset to empty",
                updated.weaponAnchors().size(), updated.slotShares().size(),
                updated.rarityNudges().size());
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
     * Builds the immutable snapshot from raw config entries, skipping blank-named entries.
     *
     * @param families            the weapon family anchor entries
     * @param defaultWeaponAnchor the fallback per-hit for unmapped families
     * @param rarities            the rarity nudge entries
     * @param slots               the armor slot share entries
     * @param drBudgetMin         total on-level DR at level 1
     * @param drBudgetMax         total on-level DR at the level ceiling
     * @return the resolved snapshot
     */
    @Nonnull
    static Snapshot build(
            @Nonnull GearCurveConfigAsset.WeaponFamilyEntry[] families,
            float defaultWeaponAnchor,
            @Nonnull GearCurveConfigAsset.RarityNudgeEntry[] rarities,
            @Nonnull GearCurveConfigAsset.ArmorSlotEntry[] slots,
            float drBudgetMin,
            float drBudgetMax) {
        return build(families, defaultWeaponAnchor, rarities, slots, drBudgetMin, drBudgetMax,
                new GearCurveConfigAsset.ArmorHpSlotEntry[0], 0f, 0f);
    }

    /**
     * Rebuilds the immutable snapshot, including the authored armor flat-HP curve.
     *
     * @param families            the weapon family anchor entries
     * @param defaultWeaponAnchor the fallback per-hit for unmapped families
     * @param rarities            the rarity nudge entries
     * @param slots               the armor slot DR share entries
     * @param drBudgetMin         total on-level DR at level 1
     * @param drBudgetMax         total on-level DR at the level ceiling
     * @param hpSlots             the armor slot HP share entries
     * @param hpBudgetMin         total on-level authored armor HP at level 1
     * @param hpBudgetMax         total on-level authored armor HP at the level ceiling
     * @return the resolved snapshot
     */
    @Nonnull
    static Snapshot build(
            @Nonnull GearCurveConfigAsset.WeaponFamilyEntry[] families,
            float defaultWeaponAnchor,
            @Nonnull GearCurveConfigAsset.RarityNudgeEntry[] rarities,
            @Nonnull GearCurveConfigAsset.ArmorSlotEntry[] slots,
            float drBudgetMin,
            float drBudgetMax,
            @Nonnull GearCurveConfigAsset.ArmorHpSlotEntry[] hpSlots,
            float hpBudgetMin,
            float hpBudgetMax) {
        Map<String, Float> weaponAnchors = new HashMap<>();
        for (GearCurveConfigAsset.WeaponFamilyEntry entry : families) {
            if (entry.getName() == null || entry.getName().isBlank()) {
                continue;
            }
            weaponAnchors.put(entry.getName(), entry.getAnchorDamage());
        }

        Map<String, Float> rarityNudges = new HashMap<>();
        for (GearCurveConfigAsset.RarityNudgeEntry entry : rarities) {
            if (entry.getRarity() == null || entry.getRarity().isBlank()) {
                continue;
            }
            rarityNudges.put(entry.getRarity(), entry.getMultiplier());
        }

        Map<String, Float> slotShares = new HashMap<>();
        for (GearCurveConfigAsset.ArmorSlotEntry entry : slots) {
            if (entry.getSlot() == null || entry.getSlot().isBlank()) {
                continue;
            }
            slotShares.put(entry.getSlot(), entry.getDrShare());
        }

        Map<String, Float> armorHpShares = new HashMap<>();
        for (GearCurveConfigAsset.ArmorHpSlotEntry entry : hpSlots) {
            if (entry.getSlot() == null || entry.getSlot().isBlank()) {
                continue;
            }
            armorHpShares.put(entry.getSlot(), entry.getHpShare());
        }

        return new Snapshot(
                Map.copyOf(weaponAnchors),
                defaultWeaponAnchor,
                Map.copyOf(rarityNudges),
                Map.copyOf(slotShares),
                drBudgetMin,
                drBudgetMax,
                Map.copyOf(armorHpShares),
                hpBudgetMin,
                hpBudgetMax
        );
    }

    // ============================================
    // Value types
    // ============================================

    /**
     * An immutable, lock-free snapshot of the authored gear curves.
     *
     * @param weaponAnchors      family name &rarr; level-1 per-hit anchor
     * @param defaultWeaponAnchor fallback per-hit for unmapped families
     * @param rarityNudges       rarity name &rarr; power multiplier
     * @param slotShares         armor slot name &rarr; share of the DR budget
     * @param drBudgetMin        total on-level DR at level 1
     * @param drBudgetMax        total on-level DR at the level ceiling
     */
    public record Snapshot(
            @Nonnull Map<String, Float> weaponAnchors,
            float defaultWeaponAnchor,
            @Nonnull Map<String, Float> rarityNudges,
            @Nonnull Map<String, Float> slotShares,
            float drBudgetMin,
            float drBudgetMax,
            @Nonnull Map<String, Float> armorHpShares,
            float hpBudgetMin,
            float hpBudgetMax
    ) {
        /** The empty snapshot served before any asset loads — drives the legacy fallback. */
        public static final Snapshot EMPTY =
                new Snapshot(Map.of(), 0f, Map.of(), Map.of(), 0f, 0f, Map.of(), 0f, 0f);

        /**
         * Returns whether this snapshot carries authored curves.
         *
         * <p>A snapshot is considered loaded only when it maps at least one weapon family or armor
         * slot; an asset present but empty still degrades to legacy scaling.
         *
         * @return {@code true} when authored data is present
         */
        public boolean loaded() {
            return !weaponAnchors.isEmpty() || !slotShares.isEmpty();
        }
    }
}
