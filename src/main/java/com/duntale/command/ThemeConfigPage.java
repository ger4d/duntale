package com.duntale.command;

import com.duntale.DuntalePlugin;
import com.duntale.dungeon.FloorConfigService;
import com.duntale.dungeon.ThemeAssetRepository;
import com.duntale.dungeon.ThemeAssetService;
import com.duntale.dungeon.preview.DungeonPreviewPacketFactory;
import com.duntale.dungeongen.config.DungeonConfig;
import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.config.asset.DungeonThemeConfig;
import com.duntale.dungeongen.config.asset.LightEntry;
import com.duntale.dungeongen.config.asset.OvergrowthEntry;
import com.duntale.dungeongen.config.asset.PaletteEntry;
import com.duntale.dungeongen.config.asset.PropRuleEntry;
import com.duntale.dungeongen.config.asset.SpawnPoolEntry;
import com.duntale.dungeongen.config.asset.TrapEntry;
import com.duntale.dungeongen.model.SpawnerVariant;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolPrefabPreview;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.browser.AssetPackSaveBrowser;
import com.hypixel.hytale.server.core.ui.browser.AssetPackSaveBrowserConfig;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Interactive UI page for authoring {@link DungeonThemeConfig} assets at runtime.
 *
 * <p>Lets a Creative-mode admin create a new theme from blank defaults (or clone an existing one),
 * edit every field of the theme schema (palette, lights, props, traps, spawn pool, scalars) with a
 * reusable {@link BlockPickerComponent} for block-ID fields, and watch a debounced auto-preview of a
 * generated floor that uses a chosen {@link FloorConfigService} floor for layout/pacing while
 * forcing the in-progress theme palette.
 *
 * <p><b>Preview mechanism.</b> The dungeon-gen pipeline resolves theme palettes by ID against the
 * live asset registry, with no in-memory override channel. To preview an unsaved theme the page
 * writes a transient {@code _preview_<session>.json} draft into the selected writable pack on every
 * debounced edit; the engine asset monitor hot-reloads it, and preview generation requests the draft
 * ID as its theme. The asset monitor needs a tick or two to settle after each write; the debounce
 * window plus a short settle delay before generation cover this in practice, and a stale result is
 * corrected by the next edit cycle. Drafts are deleted on save, dismiss and plugin shutdown, and
 * swept on startup.
 *
 * <p>Opened via {@code /dungeon theme [<themeId>]}.
 *
 * @since 1.9.0
 * @see ThemeAssetService
 * @see BlockPickerComponent
 */
public class ThemeConfigPage extends InteractiveCustomUIPage<ThemeConfigPage.ThemeConfigEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();

    private static final long PREVIEW_DEBOUNCE_MS = 750L;
    /** Grace period after writing the draft file for the asset monitor to hot-reload it before generation. */
    private static final long PREVIEW_SETTLE_MS = 400L;
    /**
     * Longer settle for block-picker selections: choosing a block rewrites the draft theme asset, and
     * the monitor needs ~3.5s to hot-reload it before preview generation. With only the short settle the
     * preview regenerates against the previous config and the newly chosen block doesn't appear.
     */
    private static final long PREVIEW_BLOCK_SETTLE_MS = 3_500L;
    private static final String PREVIEW_WORLD_NAME = "theme_preview";
    /** Fixed, overwrite-only preview theme asset (never deleted — see {@link ThemeAssetRepository#DRAFT_PREFIX}). */
    private static final String PREVIEW_THEME_ID = ThemeAssetRepository.DRAFT_PREFIX;
    private static final int MAX_ARRAY_ENTRIES = 32;
    private static final String GREEN = "#55FF55";
    private static final String RED = "#FF5555";
    private static final String BLOCK_PLACEHOLDER = "(click to set)";
    private static final String NONE_PLACEHOLDER = "(none)";

    /** Static palette block fields keyed by their UI selector suffix and draft accessor key. */
    private static final List<String> PALETTE_FIELDS = List.of(
            "primaryWall", "secondaryWall", "floor", "ceiling", "pillarBase", "pillarMiddle",
            "stairs", "slab", "fluidBlock", "secondaryFluidBlock", "accentBlock");
    private static final List<String> LIGHT_FIELDS = List.of(
            "wallLight", "ceilingLight", "floorLightBlock", "accentFloorLight");
    private static final Set<String> NULLABLE_FIELD_KEYS = Set.of(
            "palette.accentBlock", "lights.ceilingLight", "lights.floorLightBlock", "lights.accentFloorLight");

    private final AssetPackSaveBrowser packBrowser = new AssetPackSaveBrowser(AssetPackSaveBrowserConfig.defaults());
    private final BlockPickerComponent blockPicker = new BlockPickerComponent();
    private final DungeonPreviewPacketFactory previewPacketFactory = new DungeonPreviewPacketFactory();
    private final AtomicLong previewRequestSequence = new AtomicLong();

    private final ThemeAssetService themeAssetService;
    private final FloorConfigService floorConfigService;
    @Nullable
    private final String seedThemeId;

    private DraftTheme draft;
    private String themeIdInput;
    private EditorMode mode;
    private int selectedPreviewFloor = 1;
    private boolean initialized = false;
    private long lastAppliedPreviewRequestSequence = 0L;
    private List<String> blockCandidates = List.of();
    @Nullable
    private List<String> allBlockItems = null;
    private final RolePickerComponent rolePicker = new RolePickerComponent();
    private List<String> roleCandidates = List.of();
    @Nullable
    private DestructiveAction pendingDestructiveAction;

    /**
     * Creates a new theme config page.
     *
     * @param playerRef          the player opening the page
     * @param seedThemeId        an existing theme ID to clone immediately, or {@code null} to show
     *                           the seed-selection screen first
     * @param themeAssetService  the theme asset service
     * @param floorConfigService the floor config service (preview layout/pacing source)
     */
    public ThemeConfigPage(
            @Nonnull PlayerRef playerRef,
            @Nullable String seedThemeId,
            @Nonnull ThemeAssetService themeAssetService,
            @Nonnull FloorConfigService floorConfigService
    ) {
        super(playerRef, CustomPageLifetime.CanDismiss, ThemeConfigEventData.CODEC);
        this.themeAssetService = themeAssetService;
        this.floorConfigService = floorConfigService;
        this.seedThemeId = seedThemeId;

        if (seedThemeId != null) {
            DungeonThemeConfig seed = DungeonThemeConfig.get(seedThemeId);
            this.draft = seed != null ? DraftTheme.fromTheme(seed) : DraftTheme.blankDefaults();
            this.themeIdInput = seedThemeId;
            this.mode = EditorMode.EDITING;
        } else {
            this.draft = DraftTheme.blankDefaults();
            this.themeIdInput = "";
            this.mode = EditorMode.SEED_SELECT;
        }
    }

    // ============================================
    // Build
    // ============================================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        if (!initialized) {
            initialized = true;
            initializeSelectedPack();
            rebuildBlockCandidates();
            rebuildRoleCandidates();
        }

        cmd.append("Pages/ThemeConfig/ThemeConfigPage.ui");
        cmd.set("#PackBrowserPage.Visible", false);
        cmd.set("#CreatePackPage.Visible", false);
        cmd.set("#BlockPickerOverlay.Visible", false);
        cmd.set("#RolePickerOverlay.Visible", false);
        cmd.set("#ConfirmDialog.Visible", false);
        cmd.set("#SeedSelectOverlay.Visible", mode == EditorMode.SEED_SELECT);

        populateSeedDropdown(cmd);
        populatePreviewFloorDropdown(cmd);

        updatePackSelectionDisplay(cmd);
        updateOverrideNotice(cmd);
        updateDeleteButtonState(cmd);

        renderAll(cmd, events);

        addStaticBindings(events);
        blockPicker.buildStaticBindings(events);
        rolePicker.buildStaticBindings(events);
        packBrowser.buildEventBindings(events, "#BrowsePackButton");
        packBrowser.buildUI(cmd, events);

        if (mode == EditorMode.EDITING) {
            schedulePreview(traceId(), ref, store, false);
        } else {
            cmd.set("#PreviewStatusLabel.Text", "Choose a starting point");
        }
    }

    // ============================================
    // Event handling
    // ============================================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull ThemeConfigEventData data) {
        long traceId = EVENT_SEQUENCE.incrementAndGet();

        AssetPackSaveBrowser.ActionResult packResult =
                packBrowser.handleAction(data.action, data.packBrowserData, "#SelectedPackLabel");
        if (packResult != null) {
            pendingDestructiveAction = null;
            if (packResult.errorKey() != null) {
                playerRef.sendMessage(Message.translation(packResult.errorKey()));
            }
            UICommandBuilder cmd = packResult.commandBuilder();
            updatePackSelectionDisplay(cmd);
            updateDeleteButtonState(cmd);
            sendUpdate(cmd, packResult.eventBuilder(), false);
            return;
        }

        if (data.action == null) {
            return;
        }

        switch (data.action) {
            case "SeedBlank" -> startEditing(ref, store, DraftTheme.blankDefaults(),
                    generateDefaultThemeName("New_Theme"));
            case "SeedOpen" -> handleSeedOpen(ref, store, data.seedChoice);
            case BlockPickerComponent.ACTION_OPEN -> handleOpenBlockPicker(ref, store, data.fieldKey);
            case BlockPickerComponent.ACTION_SEARCH -> {
                UICommandBuilder cmd = new UICommandBuilder();
                UIEventBuilder evt = new UIEventBuilder();
                blockPicker.handleSearch(data.blockSearch, cmd, evt);
                sendUpdate(cmd, evt, false);
            }
            case "BlockDropped" -> handleBlockDropped(ref, store, data.fieldKey, data.itemStackId);
            case BlockPickerComponent.ACTION_SELECT -> handleBlockChosen(ref, store, data.blockId);
            case BlockPickerComponent.ACTION_USE_TYPED -> handleBlockChosen(ref, store,
                    data.blockSearch == null ? null : data.blockSearch.trim());
            case BlockPickerComponent.ACTION_CLEAR -> handleBlockChosen(ref, store, null);
            case BlockPickerComponent.ACTION_CANCEL -> {
                UICommandBuilder cmd = new UICommandBuilder();
                blockPicker.close(cmd);
                sendUpdate(cmd, null, false);
            }
            case RolePickerComponent.ACTION_OPEN -> handleOpenRolePicker(ref, store, data.fieldKey);
            case RolePickerComponent.ACTION_SEARCH -> {
                UICommandBuilder cmd = new UICommandBuilder();
                UIEventBuilder evt = new UIEventBuilder();
                rolePicker.handleSearch(data.roleSearch, cmd, evt);
                sendUpdate(cmd, evt, false);
            }
            case RolePickerComponent.ACTION_SELECT -> handleRoleChosen(ref, store, data.roleId);
            case RolePickerComponent.ACTION_USE_TYPED -> handleRoleChosen(ref, store,
                    data.roleSearch == null ? null : data.roleSearch.trim());
            case RolePickerComponent.ACTION_CANCEL -> {
                UICommandBuilder cmd = new UICommandBuilder();
                rolePicker.close(cmd);
                sendUpdate(cmd, null, false);
            }
            case "ThemeIdChanged" -> handleThemeIdChanged(ref, store, data.themeId);
            case "SpawnerPrefixChanged" -> {
                draft.spawnerPrefix = orEmpty(data.strValue);
                schedulePreview(traceId, ref, store, true);
            }
            case "SecondaryWallChanceChanged" -> {
                draft.secondaryWallChance = toDouble(data.numValue, draft.secondaryWallChance);
                schedulePreview(traceId, ref, store, true);
            }
            case "LevelVarianceChanged" -> draft.levelVariance = toInt(data.intValue, draft.levelVariance);
            case "FloorLightTallChanged" -> {
                draft.floorLightTall = Boolean.TRUE.equals(data.boolValue);
                schedulePreview(traceId, ref, store, true);
            }
            case "ArrayAdd" -> handleArrayAdd(ref, store, data.fieldKey);
            case "ArrayRemove" -> handleArrayRemove(ref, store, data.fieldKey, parseIndex(data.index));
            case "PropAdd" -> handlePropAdd(ref, store);
            case "PropRemove" -> handlePropRemove(ref, store, parseIndex(data.index));
            case "PropFieldChanged" -> handlePropFieldChanged(ref, store, data);
            case "SpawnAdd" -> handleSpawnAdd(ref, store);
            case "SpawnRemove" -> handleSpawnRemove(ref, store, parseIndex(data.index));
            case "SpawnFieldChanged" -> handleSpawnFieldChanged(ref, store, data);
            case "PreviewFloorChanged" -> {
                selectedPreviewFloor = parsePreviewFloor(data.previewFloor);
                schedulePreview(traceId, ref, store, true);
            }
            case "Save" -> handleSave(traceId, ref, store);
            case "RequestReset" -> requestConfirmation(ref, store, DestructiveAction.RESET);
            case "RequestDeleteOverride" -> requestConfirmation(ref, store, DestructiveAction.DELETE_OVERRIDE);
            case "ConfirmDestructive" -> confirmPendingAction(traceId, ref, store);
            case "CancelDestructive" -> cancelConfirmation(ref, store);
            default -> LOGGER.at(Level.WARNING).log("ThemeConfigPage unknown action: %s", data.action);
        }
    }

    // ── Seed selection ───────────────────────────────────────────────

    private void handleSeedOpen(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nullable String seedChoice) {
        if (seedChoice == null || seedChoice.isBlank()) {
            startEditing(ref, store, DraftTheme.blankDefaults(), generateDefaultThemeName("New_Theme"));
            return;
        }
        DungeonThemeConfig seed = DungeonThemeConfig.get(seedChoice);
        if (seed == null) {
            startEditing(ref, store, DraftTheme.blankDefaults(), generateDefaultThemeName("New_Theme"));
            return;
        }
        // Open the selected theme for in-place editing — Save overwrites it; the live preview uses a
        // separate draft, so the on-disk theme is untouched until Save.
        startEditing(ref, store, DraftTheme.fromTheme(seed), seedChoice);
    }

    private void startEditing(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                              @Nonnull DraftTheme newDraft, @Nonnull String newThemeId) {
        this.draft = newDraft;
        this.themeIdInput = newThemeId;
        this.mode = EditorMode.EDITING;
        rebuildBlockCandidates();

        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        cmd.set("#SeedSelectOverlay.Visible", false);
        cmd.set("#ThemeIdInput.Value", themeIdInput);
        updateOverrideNotice(cmd);
        updateDeleteButtonState(cmd);
        renderAll(cmd, events);
        sendUpdate(cmd, events, false);
        schedulePreview(traceId(), ref, store, false);
    }

    // ── Block picker ─────────────────────────────────────────────────

    private void handleOpenBlockPicker(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                       @Nullable String fieldKey) {
        if (fieldKey == null) {
            return;
        }
        blockPicker.setCandidates(blockCandidates);
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        blockPicker.open(cmd, events, fieldKey, currentBlockValue(fieldKey));
        sendUpdate(cmd, events, false);
    }

    private void handleBlockDropped(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                    @Nullable String fieldKey, @Nullable String droppedId) {
        if (fieldKey == null || droppedId == null || droppedId.isBlank()) {
            return;
        }
        applyBlock(fieldKey, droppedId);
        rebuildBlockCandidates();
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        renderBlockFieldOrList(fieldKey, cmd, events);
        sendUpdate(cmd, events, false);
        schedulePreview(traceId(), ref, store, false);
    }

    // ── Role picker ──────────────────────────────────────────────────

    private void handleOpenRolePicker(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                      @Nullable String fieldKey) {
        if (fieldKey == null) {
            return;
        }
        rolePicker.setCandidates(roleCandidates);
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        rolePicker.open(cmd, events, fieldKey, currentRoleValue(fieldKey));
        sendUpdate(cmd, events, false);
    }

    private void handleRoleChosen(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                  @Nullable String roleId) {
        String fieldKey = rolePicker.getPendingFieldKey();
        UICommandBuilder cmd = new UICommandBuilder();
        if (fieldKey != null && roleId != null && !roleId.isBlank() && fieldKey.startsWith("spawn.")) {
            int index = parseIndex(fieldKey.substring("spawn.".length()));
            if (index >= 0 && index < draft.spawnPool.size()) {
                draft.spawnPool.get(index).npcRole = roleId.trim();
                cmd.set("#SpawnList[" + index + "] #NpcRole.Value", roleId.trim());
            }
        }
        rolePicker.close(cmd);
        sendUpdate(cmd, null, false);
        schedulePreview(traceId(), ref, store, false);
    }

    @Nullable
    private String currentRoleValue(@Nonnull String fieldKey) {
        if (fieldKey.startsWith("spawn.")) {
            int index = parseIndex(fieldKey.substring("spawn.".length()));
            if (index >= 0 && index < draft.spawnPool.size()) {
                return draft.spawnPool.get(index).npcRole;
            }
        }
        return null;
    }

    private void handleBlockChosen(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                   @Nullable String blockId) {
        String fieldKey = blockPicker.getPendingFieldKey();
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        if (fieldKey != null) {
            applyBlock(fieldKey, blockId);
            rebuildBlockCandidates();
            renderBlockFieldOrList(fieldKey, cmd, events);
        }
        blockPicker.close(cmd);
        sendUpdate(cmd, events, false);
        // Choosing a block rewrites the draft theme asset; give the monitor longer to hot-reload it
        // before regenerating, otherwise the preview keeps the previous block (see PREVIEW_BLOCK_SETTLE_MS).
        schedulePreview(traceId(), ref, store, false, PREVIEW_BLOCK_SETTLE_MS);
    }

    // ── Theme ID ─────────────────────────────────────────────────────

    private void handleThemeIdChanged(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                      @Nullable String value) {
        themeIdInput = orEmpty(value);
        UICommandBuilder cmd = new UICommandBuilder();
        updateOverrideNotice(cmd);
        updateDeleteButtonState(cmd);
        sendUpdate(cmd, null, false);
    }

    // ── Block arrays (decay / rubble / overgrowth / traps) ───────────

    private void handleArrayAdd(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nullable String arrayKey) {
        List<String> list = arrayFor(arrayKey);
        if (list == null || list.size() >= MAX_ARRAY_ENTRIES) {
            return;
        }
        list.add("");
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        renderBlockList(arrayKey, list, cmd, events);
        sendUpdate(cmd, events, false);
    }

    private void handleArrayRemove(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                   @Nullable String arrayKey, int index) {
        List<String> list = arrayFor(arrayKey);
        if (list == null || index < 0 || index >= list.size()) {
            return;
        }
        list.remove(index);
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        renderBlockList(arrayKey, list, cmd, events);
        sendUpdate(cmd, events, false);
        schedulePreview(traceId(), ref, store, false);
    }

    // ── Props ────────────────────────────────────────────────────────

    private void handlePropAdd(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (draft.props.size() >= MAX_ARRAY_ENTRIES) {
            return;
        }
        draft.props.add(new PropDraft());
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        renderPropList(cmd, events);
        sendUpdate(cmd, events, false);
    }

    private void handlePropRemove(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, int index) {
        if (index < 0 || index >= draft.props.size()) {
            return;
        }
        draft.props.remove(index);
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        renderPropList(cmd, events);
        sendUpdate(cmd, events, false);
        schedulePreview(traceId(), ref, store, false);
    }

    private void handlePropFieldChanged(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                        @Nonnull ThemeConfigEventData data) {
        int index = parseIndex(data.index);
        if (index < 0 || index >= draft.props.size() || data.field == null) {
            return;
        }
        PropDraft prop = draft.props.get(index);
        switch (data.field) {
            case "placement" -> prop.placement = orDefault(data.strValue, prop.placement);
            case "chestTier" -> prop.chestTier = emptyToNull(data.strValue);
            case "spawnChance" -> prop.spawnChance = toDouble(data.numValue, prop.spawnChance);
            case "maxPerRoom" -> prop.maxPerRoom = toInt(data.intValue, prop.maxPerRoom);
            case "yOffset" -> prop.yOffset = toInt(data.intValue, prop.yOffset);
            case "roomTypes" -> prop.allowedRoomTypes = parseCsv(data.strValue);
            default -> { return; }
        }
        schedulePreview(traceId(), ref, store, true);
    }

    // ── Spawn pool ───────────────────────────────────────────────────

    private void handleSpawnAdd(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (draft.spawnPool.size() >= MAX_ARRAY_ENTRIES) {
            return;
        }
        draft.spawnPool.add(new SpawnDraft());
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        renderSpawnList(cmd, events);
        sendUpdate(cmd, events, false);
    }

    private void handleSpawnRemove(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, int index) {
        if (index < 0 || index >= draft.spawnPool.size()) {
            return;
        }
        draft.spawnPool.remove(index);
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        renderSpawnList(cmd, events);
        sendUpdate(cmd, events, false);
        schedulePreview(traceId(), ref, store, false);
    }

    private void handleSpawnFieldChanged(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                         @Nonnull ThemeConfigEventData data) {
        int index = parseIndex(data.index);
        if (index < 0 || index >= draft.spawnPool.size() || data.field == null) {
            return;
        }
        SpawnDraft spawn = draft.spawnPool.get(index);
        switch (data.field) {
            case "npcRole" -> spawn.npcRole = orEmpty(data.strValue);
            case "minFloor" -> spawn.minFloor = toInt(data.intValue, spawn.minFloor);
            case "maxFloor" -> spawn.maxFloor = toInt(data.intValue, spawn.maxFloor);
            case "weight" -> spawn.weight = toDouble(data.numValue, spawn.weight);
            case "varNormal" -> toggleVariant(spawn, "NORMAL", Boolean.TRUE.equals(data.boolValue));
            case "varElite" -> toggleVariant(spawn, "ELITE", Boolean.TRUE.equals(data.boolValue));
            case "varBoss" -> toggleVariant(spawn, "BOSS", Boolean.TRUE.equals(data.boolValue));
            default -> { return; }
        }
        schedulePreview(traceId(), ref, store, true);
    }

    private static void toggleVariant(@Nonnull SpawnDraft spawn, @Nonnull String variant, boolean on) {
        spawn.variants.remove(variant);
        if (on) {
            spawn.variants.add(variant);
        }
    }

    // ── Save / reset / delete ────────────────────────────────────────

    private void handleSave(long traceId, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        ThemeAssetRepository.PackChoice packChoice = requireSelectedPackChoice(ref, store);
        if (packChoice == null) {
            return;
        }
        if (!themeAssetService.isValidThemeId(themeIdInput)) {
            playerRef.sendMessage(Message.raw(
                    "Invalid theme ID. Use letters, digits, '_' or '-', starting with a letter.").color(RED));
            updateStatus(ref, store, "Invalid theme ID");
            return;
        }

        if (!closePageBeforePersistence(traceId, ref, store)) {
            return;
        }

        try {
            themeAssetService.saveTheme(themeIdInput, packChoice.name(), draft.toThemeDocument());
            playerRef.sendMessage(Message.raw(
                    "Saved theme '" + themeIdInput + "' to " + packChoice.name() + ".").color(GREEN));
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.SEVERE).withCause(e).log("ThemeConfigPage failed to save theme %s: %s",
                    themeIdInput, e.getMessage());
            playerRef.sendMessage(Message.raw("Save failed: " + e.getMessage()).color(RED));
        }
    }

    private void handleDeleteOverride(long traceId, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        ThemeAssetRepository.PackChoice packChoice = requireSelectedPackChoice(ref, store);
        if (packChoice == null) {
            return;
        }
        pendingDestructiveAction = null;
        if (!closePageBeforePersistence(traceId, ref, store)) {
            return;
        }
        try {
            boolean deleted = themeAssetService.deleteOverride(themeIdInput, packChoice.name());
            playerRef.sendMessage(Message.raw(deleted
                    ? "Deleted theme override '" + themeIdInput + "' from " + packChoice.name() + "."
                    : "No theme override '" + themeIdInput + "' existed in " + packChoice.name() + ".").color(GREEN));
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.SEVERE).withCause(e).log("ThemeConfigPage failed to delete theme %s: %s",
                    themeIdInput, e.getMessage());
            playerRef.sendMessage(Message.raw("Delete failed: " + e.getMessage()).color(RED));
        }
    }

    private void handleResetAll(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        pendingDestructiveAction = null;
        DungeonThemeConfig seed = seedThemeId != null ? DungeonThemeConfig.get(seedThemeId) : null;
        draft = seed != null ? DraftTheme.fromTheme(seed) : DraftTheme.blankDefaults();
        rebuildBlockCandidates();
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        updateConfirmationDialog(cmd);
        renderAll(cmd, events);
        cmd.set("#StatusLabel.Text", "Reset to starting values");
        cmd.set("#StatusLabel.Visible", true);
        sendUpdate(cmd, events, false);
        schedulePreview(traceId(), ref, store, false);
    }

    private void requestConfirmation(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                     @Nonnull DestructiveAction action) {
        if (action == DestructiveAction.DELETE_OVERRIDE && !hasPersistedSelectedOverride()) {
            updateStatus(ref, store, "No saved override for this ID in the selected pack");
            return;
        }
        pendingDestructiveAction = action;
        UICommandBuilder cmd = new UICommandBuilder();
        updateConfirmationDialog(cmd);
        sendUpdate(cmd, null, false);
    }

    private void confirmPendingAction(long traceId, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (pendingDestructiveAction == null) {
            return;
        }
        switch (pendingDestructiveAction) {
            case RESET -> handleResetAll(ref, store);
            case DELETE_OVERRIDE -> handleDeleteOverride(traceId, ref, store);
        }
    }

    private void cancelConfirmation(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (pendingDestructiveAction == null) {
            return;
        }
        pendingDestructiveAction = null;
        UICommandBuilder cmd = new UICommandBuilder();
        updateConfirmationDialog(cmd);
        sendUpdate(cmd, null, false);
    }

    private boolean closePageBeforePersistence(long traceId, @Nonnull Ref<EntityStore> ref,
                                               @Nonnull Store<EntityStore> store) {
        if (!ref.isValid()) {
            return false;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return false;
        }
        previewRequestSequence.incrementAndGet();
        player.getPageManager().setPage(ref, store, Page.None);
        return true;
    }

    // ============================================
    // Preview
    // ============================================

    private void schedulePreview(long traceId, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                 boolean debounce) {
        schedulePreview(traceId, ref, store, debounce, PREVIEW_SETTLE_MS);
    }

    private void schedulePreview(long traceId, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                 boolean debounce, long settleMs) {
        if (mode != EditorMode.EDITING) {
            return;
        }
        ThemeAssetRepository.PackChoice packChoice = getSelectedPackChoice();
        if (packChoice == null || !packChoice.isValidTarget()) {
            updatePreviewStatus(ref, store, "Select a writable pack to preview");
            return;
        }

        long requestSequence = previewRequestSequence.incrementAndGet();
        String packName = packChoice.name();
        int floor = selectedPreviewFloor;
        BsonDocument draftDoc = draft.toThemeDocument();
        World world = store.getExternalData().getWorld();

        // Write the draft immediately so the asset monitor can reload it before generation runs.
        Executor writeExecutor = CompletableFuture.delayedExecutor(0L, TimeUnit.MILLISECONDS);
        writeExecutor.execute(() -> {
            try {
                themeAssetService.saveDraft(PREVIEW_THEME_ID, packName, draftDoc);
            } catch (IOException | RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log("ThemeConfigPage preview draft write failed: %s",
                        e.getMessage());
            }
        });

        long delayMs = (debounce ? PREVIEW_DEBOUNCE_MS : 0L) + settleMs;
        Executor delayedExecutor = CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS);
        delayedExecutor.execute(() -> {
            if (requestSequence != previewRequestSequence.get()) {
                return;
            }
            DuntalePlugin plugin = DuntalePlugin.get();
            if (plugin == null) {
                return;
            }
            DungeonConfig previewConfig;
            try {
                previewConfig = buildPreviewConfig(floor);
            } catch (RuntimeException e) {
                world.execute(() -> applyPreviewFailure(requestSequence, ref, store, e));
                return;
            }
            try {
                plugin.getDungeonOrchestrator().generateArtifacts(previewConfig)
                        .thenApply(previewPacketFactory::createPacket)
                        .whenComplete((packet, error) -> world.execute(() ->
                                applyPreviewResult(requestSequence, ref, store, packet, error)));
            } catch (RuntimeException e) {
                world.execute(() -> applyPreviewFailure(requestSequence, ref, store, e));
            }
        });
    }

    @Nonnull
    private DungeonConfig buildPreviewConfig(int floor) {
        DungeonConfig base = floorConfigService.resolveConfigForFloor(floor, PREVIEW_THEME_ID);
        return new DungeonConfig(
                "theme-preview-" + PREVIEW_THEME_ID + "-" + floor,
                null,
                PREVIEW_WORLD_NAME,
                Vec3i.ZERO,
                base.layout(),
                base.theme(),
                base.pacing(),
                false,
                floor);
    }

    private void applyPreviewResult(long requestSequence, @Nonnull Ref<EntityStore> ref,
                                    @Nonnull Store<EntityStore> store, @Nullable BuilderToolPrefabPreview packet,
                                    @Nullable Throwable error) {
        if (requestSequence != previewRequestSequence.get()) {
            return;
        }
        if (error != null) {
            applyPreviewFailure(requestSequence, ref, store, error);
            return;
        }
        if (packet == null || !ref.isValid() || playerRef.getReference() == null) {
            return;
        }
        DungeonPreviewPacketFactory.applyTintFromPlayerPosition(packet, playerRef);
        int blocks = packet.blocksChange == null ? 0 : packet.blocksChange.length;
        int fluids = packet.fluidsChange == null ? 0 : packet.fluidsChange.length;
        int markers = packet.entityChanges == null ? 0 : packet.entityChanges.length;
        writePreviewPacket(packet);
        lastAppliedPreviewRequestSequence = requestSequence;
        updatePreviewStatus(ref, store,
                blocks == 0 && fluids == 0 && markers == 0 ? "Preview empty: no renderable blocks" : "Preview updated");
    }

    private void applyPreviewFailure(long requestSequence, @Nonnull Ref<EntityStore> ref,
                                     @Nonnull Store<EntityStore> store, @Nonnull Throwable error) {
        if (requestSequence != previewRequestSequence.get()) {
            return;
        }
        updatePreviewStatus(ref, store, "Preview unavailable: " + extractErrorMessage(error));
    }

    private void writePreviewPacket(@Nonnull BuilderToolPrefabPreview packet) {
        if (lastAppliedPreviewRequestSequence != 0L) {
            playerRef.getPacketHandler().write(new BuilderToolPrefabPreview());
        }
        playerRef.getPacketHandler().write(packet);
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        previewRequestSequence.incrementAndGet();
        playerRef.getPacketHandler().write(new BuilderToolPrefabPreview());
        lastAppliedPreviewRequestSequence = 0L;
        // Intentionally do NOT delete the preview asset: DungeonThemeConfig's IndexedLookupTableAssetMap
        // crashes the asset monitor when a replacement-less asset is removed. The fixed preview file is
        // left in place and simply overwritten by the next editing session.
    }

    // ============================================
    // Rendering
    // ============================================

    private void renderAll(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.set("#ThemeIdInput.Value", themeIdInput);
        cmd.set("#SpawnerPrefix.Value", draft.spawnerPrefix);
        cmd.set("#SecondaryWallChance.Value", (float) draft.secondaryWallChance);
        cmd.set("#LevelVariance.Value", draft.levelVariance);
        cmd.set("#FloorLightTall #CheckBox.Value", draft.floorLightTall);
        cmd.set("#FieldFill.Text", blockDisplay(draft.fillBlock, false));
        setBlockSlot(cmd, "#FieldFillSlot", draft.fillBlock);

        for (String field : PALETTE_FIELDS) {
            cmd.set("#Palette" + cap(field) + ".Text", blockDisplay(paletteValue(field), isNullablePalette(field)));
            setBlockSlot(cmd, "#Palette" + cap(field) + "Slot", paletteValue(field));
        }
        for (String field : LIGHT_FIELDS) {
            cmd.set("#Lights" + cap(field) + ".Text", blockDisplay(lightValue(field), !"wallLight".equals(field)));
            setBlockSlot(cmd, "#Lights" + cap(field) + "Slot", lightValue(field));
        }

        renderBlockList("decay", draft.decayVariants, cmd, events);
        renderBlockList("rubble", draft.rubbleBlocks, cmd, events);
        renderBlockList("ogFloor", draft.ogFloor, cmd, events);
        renderBlockList("ogWall", draft.ogWall, cmd, events);
        renderBlockList("ogCeiling", draft.ogCeiling, cmd, events);
        renderBlockList("trapRegular", draft.regularTraps, cmd, events);
        renderBlockList("trapWallSpike", draft.wallSpikeTraps, cmd, events);
        renderBlockList("trapFloor", draft.floorTraps, cmd, events);
        renderPropList(cmd, events);
        renderSpawnList(cmd, events);
    }

    /**
     * Renders a block's item icon into an {@code ItemGrid} drop slot, or an empty slot when the
     * block has no item form (so the slot still works as a drag target).
     *
     * @param cmd          the command builder to render into
     * @param slotSelector the {@code ItemGrid} element selector
     * @param blockId      the block/item ID to show, or {@code null}/blank for an empty slot
     */
    private void setBlockSlot(@Nonnull UICommandBuilder cmd, @Nonnull String slotSelector, @Nullable String blockId) {
        ItemGridSlot slot = blockId != null && !blockId.isBlank() && isItemAsset(blockId)
                ? new ItemGridSlot(new ItemStack(blockId, 1))
                : new ItemGridSlot();
        cmd.set(slotSelector + ".Slots", new ItemGridSlot[]{slot});
    }

    private static boolean isItemAsset(@Nonnull String id) {
        try {
            return Item.getAssetMap().getAsset(id) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void renderBlockFieldOrList(@Nonnull String fieldKey, @Nonnull UICommandBuilder cmd,
                                        @Nonnull UIEventBuilder events) {
        if (fieldKey.equals("fill")) {
            cmd.set("#FieldFill.Text", blockDisplay(draft.fillBlock, false));
            setBlockSlot(cmd, "#FieldFillSlot", draft.fillBlock);
        } else if (fieldKey.startsWith("palette.")) {
            String field = fieldKey.substring("palette.".length());
            cmd.set("#Palette" + cap(field) + ".Text", blockDisplay(paletteValue(field), isNullablePalette(field)));
            setBlockSlot(cmd, "#Palette" + cap(field) + "Slot", paletteValue(field));
        } else if (fieldKey.startsWith("lights.")) {
            String field = fieldKey.substring("lights.".length());
            cmd.set("#Lights" + cap(field) + ".Text", blockDisplay(lightValue(field), !"wallLight".equals(field)));
            setBlockSlot(cmd, "#Lights" + cap(field) + "Slot", lightValue(field));
        } else if (fieldKey.startsWith("prop.")) {
            int index = parseIndex(fieldKey.substring("prop.".length()));
            if (index >= 0 && index < draft.props.size()) {
                cmd.set("#PropList[" + index + "] #BlockButton.Text",
                        blockDisplay(draft.props.get(index).blockId, false));
                setBlockSlot(cmd, "#PropList[" + index + "] #BlockSlot", draft.props.get(index).blockId);
            }
        } else {
            int dot = fieldKey.lastIndexOf('.');
            if (dot > 0) {
                String arrayKey = fieldKey.substring(0, dot);
                List<String> list = arrayFor(arrayKey);
                if (list != null) {
                    renderBlockList(arrayKey, list, cmd, events);
                }
            }
        }
    }

    private void renderBlockList(@Nullable String arrayKey, @Nonnull List<String> values,
                                 @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        String container = listContainer(arrayKey);
        if (container == null) {
            return;
        }
        cmd.clear(container);
        for (int i = 0; i < values.size(); i++) {
            String sel = container + "[" + i + "]";
            cmd.append(container, "Pages/ThemeConfig/BlockFieldRow.ui");
            cmd.set(sel + " #BlockButton.Text", blockDisplay(values.get(i), false));
            setBlockSlot(cmd, sel + " #BlockSlot", values.get(i));
            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #BlockButton",
                    new EventData().append("Action", BlockPickerComponent.ACTION_OPEN)
                            .append("FieldKey", arrayKey + "." + i), false);
            events.addEventBinding(CustomUIEventBindingType.Dropped, sel + " #BlockSlot",
                    new EventData().append("Action", "BlockDropped").append("FieldKey", arrayKey + "." + i), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #RemoveButton",
                    new EventData().append("Action", "ArrayRemove")
                            .append("FieldKey", arrayKey).append("Index", Integer.toString(i)), false);
        }
    }

    private void renderPropList(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.clear("#PropList");
        for (int i = 0; i < draft.props.size(); i++) {
            PropDraft prop = draft.props.get(i);
            String sel = "#PropList[" + i + "]";
            String idx = Integer.toString(i);
            cmd.append("#PropList", "Pages/ThemeConfig/PropRow.ui");
            cmd.set(sel + " #BlockButton.Text", blockDisplay(prop.blockId, false));
            setBlockSlot(cmd, sel + " #BlockSlot", prop.blockId);
            cmd.set(sel + " #Placement.Value", prop.placement);
            cmd.set(sel + " #ChestTier.Value", prop.chestTier == null ? "" : prop.chestTier);
            cmd.set(sel + " #SpawnChance.Value", (float) prop.spawnChance);
            cmd.set(sel + " #MaxPerRoom.Value", prop.maxPerRoom);
            cmd.set(sel + " #YOffset.Value", prop.yOffset);
            cmd.set(sel + " #RoomTypes.Value", String.join(",", prop.allowedRoomTypes));

            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #BlockButton",
                    new EventData().append("Action", BlockPickerComponent.ACTION_OPEN).append("FieldKey", "prop." + i), false);
            events.addEventBinding(CustomUIEventBindingType.Dropped, sel + " #BlockSlot",
                    new EventData().append("Action", "BlockDropped").append("FieldKey", "prop." + i), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #RemoveButton",
                    new EventData().append("Action", "PropRemove").append("Index", idx), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Placement",
                    propFieldData(idx, "placement").append("@StrValue", sel + " #Placement.Value"), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #ChestTier",
                    propFieldData(idx, "chestTier").append("@StrValue", sel + " #ChestTier.Value"), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #SpawnChance",
                    propFieldData(idx, "spawnChance").append("@NumValue", sel + " #SpawnChance.Value"), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #MaxPerRoom",
                    propFieldData(idx, "maxPerRoom").append("@IntValue", sel + " #MaxPerRoom.Value"), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #YOffset",
                    propFieldData(idx, "yOffset").append("@IntValue", sel + " #YOffset.Value"), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #RoomTypes",
                    propFieldData(idx, "roomTypes").append("@StrValue", sel + " #RoomTypes.Value"), false);
        }
    }

    private void renderSpawnList(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.clear("#SpawnList");
        for (int i = 0; i < draft.spawnPool.size(); i++) {
            SpawnDraft spawn = draft.spawnPool.get(i);
            String sel = "#SpawnList[" + i + "]";
            String idx = Integer.toString(i);
            cmd.append("#SpawnList", "Pages/ThemeConfig/SpawnRow.ui");
            cmd.set(sel + " #NpcRole.Value", spawn.npcRole);
            cmd.set(sel + " #MinFloor.Value", spawn.minFloor);
            cmd.set(sel + " #MaxFloor.Value", spawn.maxFloor);
            cmd.set(sel + " #Weight.Value", (float) spawn.weight);
            cmd.set(sel + " #VarNormal #CheckBox.Value", spawn.variants.contains("NORMAL"));
            cmd.set(sel + " #VarElite #CheckBox.Value", spawn.variants.contains("ELITE"));
            cmd.set(sel + " #VarBoss #CheckBox.Value", spawn.variants.contains("BOSS"));

            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #RemoveButton",
                    new EventData().append("Action", "SpawnRemove").append("Index", idx), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #RolePick",
                    new EventData().append("Action", RolePickerComponent.ACTION_OPEN).append("FieldKey", "spawn." + i), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #NpcRole",
                    spawnFieldData(idx, "npcRole").append("@StrValue", sel + " #NpcRole.Value"), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #MinFloor",
                    spawnFieldData(idx, "minFloor").append("@IntValue", sel + " #MinFloor.Value"), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #MaxFloor",
                    spawnFieldData(idx, "maxFloor").append("@IntValue", sel + " #MaxFloor.Value"), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Weight",
                    spawnFieldData(idx, "weight").append("@NumValue", sel + " #Weight.Value"), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #VarNormal #CheckBox",
                    spawnFieldData(idx, "varNormal").append("@BoolValue", sel + " #VarNormal #CheckBox.Value"), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #VarElite #CheckBox",
                    spawnFieldData(idx, "varElite").append("@BoolValue", sel + " #VarElite #CheckBox.Value"), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #VarBoss #CheckBox",
                    spawnFieldData(idx, "varBoss").append("@BoolValue", sel + " #VarBoss #CheckBox.Value"), false);
        }
    }

    @Nonnull
    private static EventData propFieldData(@Nonnull String index, @Nonnull String field) {
        return new EventData().append("Action", "PropFieldChanged").append("Index", index).append("Field", field);
    }

    @Nonnull
    private static EventData spawnFieldData(@Nonnull String index, @Nonnull String field) {
        return new EventData().append("Action", "SpawnFieldChanged").append("Index", index).append("Field", field);
    }

    // ============================================
    // Static event bindings
    // ============================================

    private void addStaticBindings(@Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ThemeIdInput",
                new EventData().append("Action", "ThemeIdChanged").append("@ThemeId", "#ThemeIdInput.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SpawnerPrefix",
                new EventData().append("Action", "SpawnerPrefixChanged").append("@StrValue", "#SpawnerPrefix.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SecondaryWallChance",
                new EventData().append("Action", "SecondaryWallChanceChanged").append("@NumValue", "#SecondaryWallChance.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#LevelVariance",
                new EventData().append("Action", "LevelVarianceChanged").append("@IntValue", "#LevelVariance.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#FloorLightTall #CheckBox",
                new EventData().append("Action", "FloorLightTallChanged").append("@BoolValue", "#FloorLightTall #CheckBox.Value"), false);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#FieldFill",
                new EventData().append("Action", BlockPickerComponent.ACTION_OPEN).append("FieldKey", "fill"), false);
        events.addEventBinding(CustomUIEventBindingType.Dropped, "#FieldFillSlot",
                new EventData().append("Action", "BlockDropped").append("FieldKey", "fill"), false);
        for (String field : PALETTE_FIELDS) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Palette" + cap(field),
                    new EventData().append("Action", BlockPickerComponent.ACTION_OPEN).append("FieldKey", "palette." + field), false);
            events.addEventBinding(CustomUIEventBindingType.Dropped, "#Palette" + cap(field) + "Slot",
                    new EventData().append("Action", "BlockDropped").append("FieldKey", "palette." + field), false);
        }
        for (String field : LIGHT_FIELDS) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Lights" + cap(field),
                    new EventData().append("Action", BlockPickerComponent.ACTION_OPEN).append("FieldKey", "lights." + field), false);
            events.addEventBinding(CustomUIEventBindingType.Dropped, "#Lights" + cap(field) + "Slot",
                    new EventData().append("Action", "BlockDropped").append("FieldKey", "lights." + field), false);
        }

        bindAdd(events, "#AddDecay", "decay");
        bindAdd(events, "#AddRubble", "rubble");
        bindAdd(events, "#AddOgFloor", "ogFloor");
        bindAdd(events, "#AddOgWall", "ogWall");
        bindAdd(events, "#AddOgCeiling", "ogCeiling");
        bindAdd(events, "#AddTrapRegular", "trapRegular");
        bindAdd(events, "#AddTrapWallSpike", "trapWallSpike");
        bindAdd(events, "#AddTrapFloor", "trapFloor");

        events.addEventBinding(CustomUIEventBindingType.Activating, "#AddProp",
                new EventData().append("Action", "PropAdd"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AddSpawn",
                new EventData().append("Action", "SpawnAdd"), false);

        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#PreviewFloor",
                new EventData().append("Action", "PreviewFloorChanged").append("@PreviewFloor", "#PreviewFloor.Value"), false);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton",
                new EventData().append("Action", "Save"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ResetButton",
                new EventData().append("Action", "RequestReset"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DeleteButton",
                new EventData().append("Action", "RequestDeleteOverride"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmDialogConfirmButton",
                new EventData().append("Action", "ConfirmDestructive"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmDialogCancelButton",
                new EventData().append("Action", "CancelDestructive"), false);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#SeedBlankButton",
                new EventData().append("Action", "SeedBlank"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SeedCloneButton",
                new EventData().append("Action", "SeedOpen").append("@SeedChoice", "#SeedThemeSelect.Value"), false);
    }

    private static void bindAdd(@Nonnull UIEventBuilder events, @Nonnull String selector, @Nonnull String arrayKey) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                new EventData().append("Action", "ArrayAdd").append("FieldKey", arrayKey), false);
    }

    // ============================================
    // Display helpers
    // ============================================

    private void populateSeedDropdown(@Nonnull UICommandBuilder cmd) {
        ObjectArrayList<DropdownEntryInfo> entries = new ObjectArrayList<>();
        entries.add(new DropdownEntryInfo(LocalizableString.fromString("(none)"), ""));
        for (String themeId : loadedThemeIds()) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(themeId), themeId));
        }
        cmd.set("#SeedThemeSelect.Entries", entries);
        cmd.set("#SeedThemeSelect.Value", "");
    }

    private void populatePreviewFloorDropdown(@Nonnull UICommandBuilder cmd) {
        ObjectArrayList<DropdownEntryInfo> entries = new ObjectArrayList<>();
        List<Integer> floors = new ArrayList<>(floorConfigService.listDefinedFloors());
        if (!floors.contains(1)) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString("Floor 1 (defaults)"), "1"));
        }
        for (int floor : floors) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString("Floor " + floor), Integer.toString(floor)));
        }
        cmd.set("#PreviewFloor.Entries", entries);
        cmd.set("#PreviewFloor.Value", Integer.toString(selectedPreviewFloor));
    }

    private void updateOverrideNotice(@Nonnull UICommandBuilder cmd) {
        boolean overrides = themeAssetService.isValidThemeId(themeIdInput) && themeAssetService.themeIdExists(themeIdInput);
        cmd.set("#OverrideNotice.Text", overrides ? "Save overwrites existing theme '" + themeIdInput + "'" : "");
    }

    private void updatePackSelectionDisplay(@Nonnull UICommandBuilder cmd) {
        if (!packBrowser.hasSelectedPack()) {
            cmd.set("#SelectedPackLabel.Text", "No asset pack selected");
            cmd.set("#PackStatusLabel.Text", "Select a writable pack downstream of DungeonGen to save themes.");
            return;
        }
        cmd.set("#SelectedPackLabel.Text", packBrowser.getSelectedPackDisplayName());
        ThemeAssetRepository.PackChoice choice = getSelectedPackChoice();
        cmd.set("#PackStatusLabel.Text", choice == null ? "Selected pack is no longer loaded." : choice.status());
    }

    private void updateDeleteButtonState(@Nonnull UICommandBuilder cmd) {
        cmd.set("#DeleteButton.Visible", hasPersistedSelectedOverride());
    }

    private void updateConfirmationDialog(@Nonnull UICommandBuilder cmd) {
        if (pendingDestructiveAction == null) {
            cmd.set("#ConfirmDialog.Visible", false);
            return;
        }
        cmd.set("#ConfirmDialog.Visible", true);
        switch (pendingDestructiveAction) {
            case RESET -> {
                cmd.set("#ConfirmDialogTitle.Text", "Reset all edits?");
                cmd.set("#ConfirmDialogMessage.Text", "This discards your current edits and restores the starting values.");
                cmd.set("#ConfirmDialogConfirmButton.Text", "Reset");
            }
            case DELETE_OVERRIDE -> {
                cmd.set("#ConfirmDialogTitle.Text", "Delete saved override?");
                cmd.set("#ConfirmDialogMessage.Text",
                        "This deletes the saved theme file '" + themeIdInput + "' from the selected pack.");
                cmd.set("#ConfirmDialogConfirmButton.Text", "Delete");
            }
        }
    }

    private void updateStatus(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull String text) {
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set("#StatusLabel.Text", text);
        cmd.set("#StatusLabel.Visible", true);
        sendUpdate(cmd, null, false);
    }

    private void updatePreviewStatus(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                     @Nonnull String text) {
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set("#PreviewStatusLabel.Text", text);
        sendUpdate(cmd, null, false);
    }

    // ============================================
    // Pack selection
    // ============================================

    private void initializeSelectedPack() {
        if (packBrowser.hasSelectedPack()) {
            return;
        }
        themeAssetService.listPackChoices().stream()
                .filter(ThemeAssetRepository.PackChoice::isValidTarget)
                .findFirst()
                .ifPresentOrElse(
                        choice -> packBrowser.setSelectedPackKey(choice.name()),
                        () -> themeAssetService.listPackChoices().stream()
                                .filter(ThemeAssetRepository.PackChoice::writable)
                                .findFirst()
                                .ifPresent(choice -> packBrowser.setSelectedPackKey(choice.name())));
    }

    @Nullable
    private ThemeAssetRepository.PackChoice getSelectedPackChoice() {
        if (!packBrowser.hasSelectedPack() || packBrowser.getSelectedPack() == null) {
            return null;
        }
        String packName = packBrowser.getSelectedPack().getName();
        return themeAssetService.listPackChoices().stream()
                .filter(choice -> choice.name().equals(packName))
                .findFirst()
                .orElse(null);
    }

    @Nullable
    private ThemeAssetRepository.PackChoice requireSelectedPackChoice(@Nonnull Ref<EntityStore> ref,
                                                                      @Nonnull Store<EntityStore> store) {
        ThemeAssetRepository.PackChoice choice = getSelectedPackChoice();
        if (choice == null) {
            playerRef.sendMessage(Message.raw("Select an asset pack before saving.").color(RED));
            updateStatus(ref, store, "Select a pack first");
            return null;
        }
        if (!choice.isValidTarget()) {
            playerRef.sendMessage(Message.raw("Selected pack cannot store themes: " + choice.status()).color(RED));
            updateStatus(ref, store, "Invalid target pack");
            return null;
        }
        return choice;
    }

    private boolean hasPersistedSelectedOverride() {
        ThemeAssetRepository.PackChoice choice = getSelectedPackChoice();
        if (choice == null || !choice.isValidTarget() || !themeAssetService.isValidThemeId(themeIdInput)) {
            return false;
        }
        try {
            return themeAssetService.hasAssetOverride(themeIdInput, choice.name());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ============================================
    // Block candidate gathering
    // ============================================

    private void rebuildBlockCandidates() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        // Every block-type item in the game, enumerated once and cached (the item registry is fixed
        // at runtime). This is the full block catalog the picker browses.
        if (allBlockItems == null) {
            List<String> blocks = new ArrayList<>();
            try {
                for (Map.Entry<String, Item> entry : Item.getAssetMap().getAssetMap().entrySet()) {
                    Item item = entry.getValue();
                    if (item != null && item.hasBlockType()) {
                        blocks.add(entry.getKey());
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.at(Level.FINE).withCause(e).log("ThemeConfigPage could not enumerate block items");
            }
            if (!blocks.isEmpty()) {
                allBlockItems = blocks;
            }
        }
        if (allBlockItems != null) {
            set.addAll(allBlockItems);
        }
        // Cover any shipped-theme / authored values that aren't block-type items (e.g. fluids).
        try {
            for (DungeonThemeConfig theme : DungeonThemeConfig.getAssetMap().getAssetMap().values()) {
                collectThemeBlocks(theme, set);
            }
        } catch (RuntimeException ignored) {
            // Asset store not ready yet.
        }
        collectDraftBlocks(set);
        set.remove("");
        List<String> sorted = new ArrayList<>(set);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        blockCandidates = sorted;
    }

    private void rebuildRoleCandidates() {
        try {
            List<String> roles = new ArrayList<>(NPCPlugin.get().getRoleTemplateNames(true));
            roles.sort(String.CASE_INSENSITIVE_ORDER);
            roleCandidates = roles;
        } catch (RuntimeException e) {
            LOGGER.at(Level.FINE).withCause(e).log("ThemeConfigPage could not enumerate NPC role templates");
            roleCandidates = List.of();
        }
    }

    private static void collectThemeBlocks(@Nonnull DungeonThemeConfig theme, @Nonnull Set<String> out) {
        PaletteEntry p = theme.getPalette();
        addAll(out, p.getPrimaryWall(), p.getSecondaryWall(), p.getFloor(), p.getCeiling(), p.getPillarBase(),
                p.getPillarMiddle(), p.getStairs(), p.getSlab(), p.getFluidBlock(), p.getSecondaryFluidBlock(),
                p.getAccentBlock());
        addArray(out, p.getDecayVariants());
        addArray(out, p.getRubbleBlocks());
        OvergrowthEntry og = p.getOvergrowthBlocks();
        addArray(out, og.getFloor());
        addArray(out, og.getWall());
        addArray(out, og.getCeiling());
        LightEntry l = theme.getLights();
        addAll(out, l.getWallLight(), l.getCeilingLight(), l.getFloorLightBlock(), l.getAccentFloorLight());
        TrapEntry t = theme.getTraps();
        addArray(out, t.getRegularTraps());
        addArray(out, t.getWallSpikeTraps());
        addArray(out, t.getFloorTraps());
        for (PropRuleEntry prop : theme.getProps()) {
            addAll(out, prop.getBlockId());
        }
    }

    private void collectDraftBlocks(@Nonnull Set<String> out) {
        addAll(out, draft.fillBlock, draft.primaryWall, draft.secondaryWall, draft.floor, draft.ceiling,
                draft.pillarBase, draft.pillarMiddle, draft.stairs, draft.slab, draft.fluidBlock,
                draft.secondaryFluidBlock, draft.accentBlock, draft.wallLight, draft.ceilingLight,
                draft.floorLightBlock, draft.accentFloorLight);
        out.addAll(draft.decayVariants);
        out.addAll(draft.rubbleBlocks);
        out.addAll(draft.ogFloor);
        out.addAll(draft.ogWall);
        out.addAll(draft.ogCeiling);
        out.addAll(draft.regularTraps);
        out.addAll(draft.wallSpikeTraps);
        out.addAll(draft.floorTraps);
        for (PropDraft prop : draft.props) {
            addAll(out, prop.blockId);
        }
    }

    private static void addAll(@Nonnull Set<String> out, @Nullable String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
    }

    private static void addArray(@Nonnull Set<String> out, @Nullable String[] values) {
        if (values != null) {
            addAll(out, values);
        }
    }

    @Nonnull
    private List<String> loadedThemeIds() {
        try {
            List<String> ids = new ArrayList<>();
            for (String id : DungeonThemeConfig.getAssetMap().getAssetMap().keySet()) {
                if (!isHiddenPreviewId(id)) {
                    ids.add(id);
                }
            }
            return ids;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Hides the live preview asset and any legacy draft files from theme lists. */
    private static boolean isHiddenPreviewId(@Nonnull String id) {
        return id.startsWith(ThemeAssetRepository.DRAFT_PREFIX)
                || id.startsWith("Preview_Draft_")
                || id.startsWith("_preview_");
    }

    /**
     * Generates a valid, collision-free default theme name for a freshly created theme.
     *
     * <p>Follows the shipped asset naming convention (letter-leading, {@code Word_Word} style) and
     * appends a numeric suffix to avoid colliding with any loaded theme or any file already present
     * in the selected write location.
     *
     * @param base the preferred base name
     * @return a unique theme ID not currently in use
     */
    @Nonnull
    private String generateDefaultThemeName(@Nonnull String base) {
        String sanitized = themeAssetService.isValidThemeId(base) ? base : "New_Theme";
        String candidate = sanitized;
        int suffix = 2;
        while (themeNameInUse(candidate) && suffix < 10_000) {
            candidate = sanitized + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private boolean themeNameInUse(@Nonnull String id) {
        if (themeAssetService.themeIdExists(id)) {
            return true;
        }
        ThemeAssetRepository.PackChoice choice = getSelectedPackChoice();
        if (choice != null && choice.isValidTarget()) {
            try {
                return themeAssetService.hasAssetOverride(id, choice.name());
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return false;
    }

    // ============================================
    // Field key resolution
    // ============================================

    @Nullable
    private String currentBlockValue(@Nonnull String fieldKey) {
        if (fieldKey.equals("fill")) {
            return draft.fillBlock;
        }
        if (fieldKey.startsWith("palette.")) {
            return paletteValue(fieldKey.substring("palette.".length()));
        }
        if (fieldKey.startsWith("lights.")) {
            return lightValue(fieldKey.substring("lights.".length()));
        }
        if (fieldKey.startsWith("prop.")) {
            int index = parseIndex(fieldKey.substring("prop.".length()));
            return index >= 0 && index < draft.props.size() ? draft.props.get(index).blockId : null;
        }
        int dot = fieldKey.lastIndexOf('.');
        if (dot > 0) {
            List<String> list = arrayFor(fieldKey.substring(0, dot));
            int index = parseIndex(fieldKey.substring(dot + 1));
            if (list != null && index >= 0 && index < list.size()) {
                return list.get(index);
            }
        }
        return null;
    }

    private void applyBlock(@Nonnull String fieldKey, @Nullable String value) {
        boolean nullable = NULLABLE_FIELD_KEYS.contains(fieldKey);
        String resolved = value == null || value.isBlank() ? null : value.trim();
        if (resolved == null && !nullable && !fieldKey.contains(".") ) {
            // Required scalar (fill) — ignore clears.
            return;
        }

        if (fieldKey.equals("fill")) {
            if (resolved != null) {
                draft.fillBlock = resolved;
            }
            return;
        }
        if (fieldKey.startsWith("palette.")) {
            setPaletteValue(fieldKey.substring("palette.".length()), resolved);
            return;
        }
        if (fieldKey.startsWith("lights.")) {
            setLightValue(fieldKey.substring("lights.".length()), resolved);
            return;
        }
        if (fieldKey.startsWith("prop.")) {
            int index = parseIndex(fieldKey.substring("prop.".length()));
            if (index >= 0 && index < draft.props.size() && resolved != null) {
                draft.props.get(index).blockId = resolved;
            }
            return;
        }
        int dot = fieldKey.lastIndexOf('.');
        if (dot > 0) {
            List<String> list = arrayFor(fieldKey.substring(0, dot));
            int index = parseIndex(fieldKey.substring(dot + 1));
            if (list != null && index >= 0 && index < list.size()) {
                list.set(index, resolved == null ? "" : resolved);
            }
        }
    }

    @Nullable
    private String paletteValue(@Nonnull String field) {
        return switch (field) {
            case "primaryWall" -> draft.primaryWall;
            case "secondaryWall" -> draft.secondaryWall;
            case "floor" -> draft.floor;
            case "ceiling" -> draft.ceiling;
            case "pillarBase" -> draft.pillarBase;
            case "pillarMiddle" -> draft.pillarMiddle;
            case "stairs" -> draft.stairs;
            case "slab" -> draft.slab;
            case "fluidBlock" -> draft.fluidBlock;
            case "secondaryFluidBlock" -> draft.secondaryFluidBlock;
            case "accentBlock" -> draft.accentBlock;
            default -> null;
        };
    }

    private void setPaletteValue(@Nonnull String field, @Nullable String value) {
        switch (field) {
            case "primaryWall" -> draft.primaryWall = orDefault(value, draft.primaryWall);
            case "secondaryWall" -> draft.secondaryWall = orDefault(value, draft.secondaryWall);
            case "floor" -> draft.floor = orDefault(value, draft.floor);
            case "ceiling" -> draft.ceiling = orDefault(value, draft.ceiling);
            case "pillarBase" -> draft.pillarBase = orDefault(value, draft.pillarBase);
            case "pillarMiddle" -> draft.pillarMiddle = orDefault(value, draft.pillarMiddle);
            case "stairs" -> draft.stairs = orDefault(value, draft.stairs);
            case "slab" -> draft.slab = orDefault(value, draft.slab);
            case "fluidBlock" -> draft.fluidBlock = orDefault(value, draft.fluidBlock);
            case "secondaryFluidBlock" -> draft.secondaryFluidBlock = orDefault(value, draft.secondaryFluidBlock);
            case "accentBlock" -> draft.accentBlock = value;
            default -> { }
        }
    }

    private boolean isNullablePalette(@Nonnull String field) {
        return "accentBlock".equals(field);
    }

    @Nullable
    private String lightValue(@Nonnull String field) {
        return switch (field) {
            case "wallLight" -> draft.wallLight;
            case "ceilingLight" -> draft.ceilingLight;
            case "floorLightBlock" -> draft.floorLightBlock;
            case "accentFloorLight" -> draft.accentFloorLight;
            default -> null;
        };
    }

    private void setLightValue(@Nonnull String field, @Nullable String value) {
        switch (field) {
            case "wallLight" -> draft.wallLight = orDefault(value, draft.wallLight);
            case "ceilingLight" -> draft.ceilingLight = value;
            case "floorLightBlock" -> draft.floorLightBlock = value;
            case "accentFloorLight" -> draft.accentFloorLight = value;
            default -> { }
        }
    }

    @Nullable
    private List<String> arrayFor(@Nullable String arrayKey) {
        if (arrayKey == null) {
            return null;
        }
        return switch (arrayKey) {
            case "decay" -> draft.decayVariants;
            case "rubble" -> draft.rubbleBlocks;
            case "ogFloor" -> draft.ogFloor;
            case "ogWall" -> draft.ogWall;
            case "ogCeiling" -> draft.ogCeiling;
            case "trapRegular" -> draft.regularTraps;
            case "trapWallSpike" -> draft.wallSpikeTraps;
            case "trapFloor" -> draft.floorTraps;
            default -> null;
        };
    }

    @Nullable
    private static String listContainer(@Nullable String arrayKey) {
        if (arrayKey == null) {
            return null;
        }
        return switch (arrayKey) {
            case "decay" -> "#DecayList";
            case "rubble" -> "#RubbleList";
            case "ogFloor" -> "#OgFloorList";
            case "ogWall" -> "#OgWallList";
            case "ogCeiling" -> "#OgCeilingList";
            case "trapRegular" -> "#TrapRegularList";
            case "trapWallSpike" -> "#TrapWallSpikeList";
            case "trapFloor" -> "#TrapFloorList";
            default -> null;
        };
    }

    // ============================================
    // Small helpers
    // ============================================

    @Nonnull
    private static String blockDisplay(@Nullable String value, boolean nullable) {
        if (value == null || value.isBlank()) {
            return nullable ? NONE_PLACEHOLDER : BLOCK_PLACEHOLDER;
        }
        return value;
    }

    private int parsePreviewFloor(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int parseIndex(@Nullable String raw) {
        if (raw == null) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed.toUpperCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    @Nonnull
    private static String cap(@Nonnull String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    @Nonnull
    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    @Nonnull
    private static String orDefault(@Nullable String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static double toDouble(@Nullable Number value, double fallback) {
        return value != null ? value.doubleValue() : fallback;
    }

    private static int toInt(@Nullable Number value, int fallback) {
        return value != null ? value.intValue() : fallback;
    }

    private static long traceId() {
        return EVENT_SEQUENCE.incrementAndGet();
    }

    @Nonnull
    private static String extractErrorMessage(@Nonnull Throwable throwable) {
        Throwable current = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private enum EditorMode { SEED_SELECT, EDITING }

    private enum DestructiveAction { RESET, DELETE_OVERRIDE }

    // ============================================
    // Draft model
    // ============================================

    /** Mutable in-memory representation of every authorable theme field, owned solely by the page. */
    static final class DraftTheme {
        // Identity
        String spawnerPrefix = "Zone1_Undead";
        String fillBlock = "Rock_Stone_Brick";
        double secondaryWallChance = 0.2;
        int levelVariance = 5;
        // Palette singletons
        String primaryWall = "Rock_Stone_Brick";
        String secondaryWall = "Rock_Stone_Brick";
        String floor = "Rock_Stone_Cobble";
        String ceiling = "Rock_Stone_Brick";
        String pillarBase = "Rock_Stone_Brick";
        String pillarMiddle = "Rock_Stone_Brick";
        String stairs = "Rock_Stone_Brick";
        String slab = "Rock_Stone_Brick";
        String fluidBlock = "Fluid_Water";
        String secondaryFluidBlock = "Fluid_Lava";
        @Nullable String accentBlock = null;
        // Palette arrays
        List<String> decayVariants = new ArrayList<>();
        List<String> rubbleBlocks = new ArrayList<>();
        List<String> ogFloor = new ArrayList<>();
        List<String> ogWall = new ArrayList<>();
        List<String> ogCeiling = new ArrayList<>();
        // Lights
        String wallLight = "Wood_Torch_Wall";
        @Nullable String ceilingLight = null;
        @Nullable String floorLightBlock = null;
        boolean floorLightTall = false;
        @Nullable String accentFloorLight = null;
        // Traps
        List<String> regularTraps = new ArrayList<>();
        List<String> wallSpikeTraps = new ArrayList<>();
        List<String> floorTraps = new ArrayList<>();
        // Props / spawn pool
        List<PropDraft> props = new ArrayList<>();
        List<SpawnDraft> spawnPool = new ArrayList<>();

        @Nonnull
        static DraftTheme blankDefaults() {
            DraftTheme d = new DraftTheme();
            TrapEntry trapDefaults = new TrapEntry();
            d.regularTraps = new ArrayList<>(Arrays.asList(trapDefaults.getRegularTraps()));
            d.wallSpikeTraps = new ArrayList<>(Arrays.asList(trapDefaults.getWallSpikeTraps()));
            d.floorTraps = new ArrayList<>(Arrays.asList(trapDefaults.getFloorTraps()));
            return d;
        }

        @Nonnull
        static DraftTheme fromTheme(@Nonnull DungeonThemeConfig t) {
            DraftTheme d = new DraftTheme();
            PaletteEntry p = t.getPalette();
            d.primaryWall = p.getPrimaryWall();
            d.secondaryWall = p.getSecondaryWall();
            d.floor = p.getFloor();
            d.ceiling = p.getCeiling();
            d.pillarBase = p.getPillarBase();
            d.pillarMiddle = p.getPillarMiddle();
            d.stairs = p.getStairs();
            d.slab = p.getSlab();
            d.fluidBlock = p.getFluidBlock();
            d.secondaryFluidBlock = p.getSecondaryFluidBlock();
            d.accentBlock = p.getAccentBlock();
            d.decayVariants = toList(p.getDecayVariants());
            d.rubbleBlocks = toList(p.getRubbleBlocks());
            OvergrowthEntry og = p.getOvergrowthBlocks();
            d.ogFloor = toList(og.getFloor());
            d.ogWall = toList(og.getWall());
            d.ogCeiling = toList(og.getCeiling());
            LightEntry l = t.getLights();
            d.wallLight = l.getWallLight();
            d.ceilingLight = l.getCeilingLight();
            d.floorLightBlock = l.getFloorLightBlock();
            d.floorLightTall = l.isFloorLightTall();
            d.accentFloorLight = l.getAccentFloorLight();
            TrapEntry tr = t.getTraps();
            d.regularTraps = toList(tr.getRegularTraps());
            d.wallSpikeTraps = toList(tr.getWallSpikeTraps());
            d.floorTraps = toList(tr.getFloorTraps());
            d.spawnerPrefix = t.getSpawnerPrefix();
            d.fillBlock = t.getFillBlock();
            d.secondaryWallChance = t.getSecondaryWallChance();
            d.levelVariance = t.getLevelVariance();
            for (PropRuleEntry pr : t.getProps()) {
                PropDraft pd = new PropDraft();
                pd.blockId = pr.getBlockId();
                pd.placement = pr.getPlacement();
                pd.spawnChance = pr.getSpawnChance();
                pd.maxPerRoom = pr.getMaxPerRoom();
                pd.allowedRoomTypes = toList(pr.getAllowedRoomTypes());
                pd.yOffset = pr.getYOffset();
                pd.chestTier = pr.getChestTier();
                d.props.add(pd);
            }
            for (SpawnPoolEntry sp : t.getSpawnPool()) {
                SpawnDraft sd = new SpawnDraft();
                sd.npcRole = sp.getNpcRole();
                sd.minFloor = sp.getMinFloor();
                sd.maxFloor = sp.getMaxFloor();
                sd.weight = sp.getWeight();
                sd.variants = new ArrayList<>();
                for (SpawnerVariant v : sp.getAllowedVariants()) {
                    sd.variants.add(v.name());
                }
                d.spawnPool.add(sd);
            }
            return d;
        }

        /** Builds the full theme JSON document encoded as the asset monitor expects to read it. */
        @Nonnull
        BsonDocument toThemeDocument() {
            BsonDocument doc = new BsonDocument();

            BsonDocument pal = new BsonDocument();
            pal.append("PrimaryWall", new BsonString(primaryWall));
            pal.append("SecondaryWall", new BsonString(secondaryWall));
            pal.append("Floor", new BsonString(floor));
            pal.append("Ceiling", new BsonString(ceiling));
            pal.append("PillarBase", new BsonString(pillarBase));
            pal.append("PillarMiddle", new BsonString(pillarMiddle));
            pal.append("Stairs", new BsonString(stairs));
            pal.append("Slab", new BsonString(slab));
            pal.append("DecayVariants", strArray(decayVariants));
            BsonDocument og = new BsonDocument();
            og.append("Floor", strArray(ogFloor));
            og.append("Wall", strArray(ogWall));
            og.append("Ceiling", strArray(ogCeiling));
            pal.append("OvergrowthBlocks", og);
            pal.append("RubbleBlocks", strArray(rubbleBlocks));
            pal.append("FluidBlock", new BsonString(fluidBlock));
            pal.append("SecondaryFluidBlock", new BsonString(secondaryFluidBlock));
            if (accentBlock != null && !accentBlock.isBlank()) {
                pal.append("AccentBlock", new BsonString(accentBlock));
            }
            doc.append("Palette", pal);

            BsonDocument lights = new BsonDocument();
            lights.append("WallLight", new BsonString(wallLight));
            if (ceilingLight != null && !ceilingLight.isBlank()) {
                lights.append("CeilingLight", new BsonString(ceilingLight));
            }
            if (floorLightBlock != null && !floorLightBlock.isBlank()) {
                lights.append("FloorLightBlock", new BsonString(floorLightBlock));
            }
            lights.append("FloorLightTall", BsonBoolean.valueOf(floorLightTall));
            if (accentFloorLight != null && !accentFloorLight.isBlank()) {
                lights.append("AccentFloorLight", new BsonString(accentFloorLight));
            }
            doc.append("Lights", lights);

            BsonArray propsArray = new BsonArray();
            for (PropDraft prop : props) {
                BsonDocument pd = new BsonDocument();
                pd.append("BlockId", new BsonString(prop.blockId));
                pd.append("Placement", new BsonString(prop.placement));
                pd.append("SpawnChance", new BsonDouble(prop.spawnChance));
                pd.append("MaxPerRoom", new BsonInt32(prop.maxPerRoom));
                if (!prop.allowedRoomTypes.isEmpty()) {
                    pd.append("AllowedRoomTypes", strArray(prop.allowedRoomTypes));
                }
                pd.append("YOffset", new BsonInt32(prop.yOffset));
                if (prop.chestTier != null && !prop.chestTier.isBlank()) {
                    pd.append("ChestTier", new BsonString(prop.chestTier));
                }
                propsArray.add(pd);
            }
            doc.append("Props", propsArray);

            doc.append("SpawnerPrefix", new BsonString(spawnerPrefix));

            BsonDocument traps = new BsonDocument();
            traps.append("RegularTraps", strArray(regularTraps));
            traps.append("WallSpikeTraps", strArray(wallSpikeTraps));
            traps.append("FloorTraps", strArray(floorTraps));
            doc.append("Traps", traps);

            doc.append("FillBlock", new BsonString(fillBlock));
            doc.append("SecondaryWallChance", new BsonDouble(secondaryWallChance));

            BsonArray pool = new BsonArray();
            for (SpawnDraft spawn : spawnPool) {
                BsonDocument sd = new BsonDocument();
                sd.append("NpcRole", new BsonString(spawn.npcRole));
                sd.append("MinFloor", new BsonInt32(spawn.minFloor));
                sd.append("MaxFloor", new BsonInt32(spawn.maxFloor));
                sd.append("Weight", new BsonDouble(spawn.weight));
                sd.append("Variants", strArray(spawn.variants));
                pool.add(sd);
            }
            doc.append("SpawnPool", pool);

            doc.append("LevelVariance", new BsonInt32(levelVariance));
            return doc;
        }

        @Nonnull
        private static BsonArray strArray(@Nonnull List<String> values) {
            BsonArray array = new BsonArray();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    array.add(new BsonString(value));
                }
            }
            return array;
        }

        @Nonnull
        private static List<String> toList(@Nullable String[] values) {
            List<String> list = new ArrayList<>();
            if (values != null) {
                for (String value : values) {
                    if (value != null) {
                        list.add(value);
                    }
                }
            }
            return list;
        }
    }

    /** Mutable prop entry within {@link DraftTheme}. */
    static final class PropDraft {
        String blockId = "";
        String placement = "FLOOR";
        double spawnChance = 0.1;
        int maxPerRoom = 1;
        List<String> allowedRoomTypes = new ArrayList<>();
        int yOffset = 0;
        @Nullable String chestTier = null;
    }

    /** Mutable spawn-pool entry within {@link DraftTheme}. */
    static final class SpawnDraft {
        String npcRole = "";
        int minFloor = 1;
        int maxFloor = 1;
        double weight = 1.0;
        List<String> variants = new ArrayList<>(List.of("NORMAL"));
    }

    // ============================================
    // Event data codec
    // ============================================

    /** Event payload for {@link ThemeConfigPage} interactions. */
    public static final class ThemeConfigEventData {

        public static final BuilderCodec<ThemeConfigEventData> CODEC = BuilderCodec.builder(
                        ThemeConfigEventData.class, ThemeConfigEventData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (e, v) -> e.action = v, e -> e.action).add()
                .append(new KeyedCodec<>("@ThemeId", Codec.STRING), (e, v) -> e.themeId = v, e -> e.themeId).add()
                .append(new KeyedCodec<>("@StrValue", Codec.STRING), (e, v) -> e.strValue = v, e -> e.strValue).add()
                .append(new KeyedCodec<>("@NumValue", Codec.FLOAT), (e, v) -> e.numValue = v, e -> e.numValue).add()
                .append(new KeyedCodec<>("@IntValue", Codec.INTEGER), (e, v) -> e.intValue = v, e -> e.intValue).add()
                .append(new KeyedCodec<>("@BoolValue", Codec.BOOLEAN), (e, v) -> e.boolValue = v, e -> e.boolValue).add()
                .append(new KeyedCodec<>("FieldKey", Codec.STRING), (e, v) -> e.fieldKey = v, e -> e.fieldKey).add()
                .append(new KeyedCodec<>("Index", Codec.STRING), (e, v) -> e.index = v, e -> e.index).add()
                .append(new KeyedCodec<>("Field", Codec.STRING), (e, v) -> e.field = v, e -> e.field).add()
                .append(new KeyedCodec<>("BlockId", Codec.STRING), (e, v) -> e.blockId = v, e -> e.blockId).add()
                .append(new KeyedCodec<>("ItemStackId", Codec.STRING), (e, v) -> e.itemStackId = v, e -> e.itemStackId).add()
                .append(new KeyedCodec<>("@BlockSearch", Codec.STRING), (e, v) -> e.blockSearch = v, e -> e.blockSearch).add()
                .append(new KeyedCodec<>("RoleId", Codec.STRING), (e, v) -> e.roleId = v, e -> e.roleId).add()
                .append(new KeyedCodec<>("@RoleSearch", Codec.STRING), (e, v) -> e.roleSearch = v, e -> e.roleSearch).add()
                .append(new KeyedCodec<>("@PreviewFloor", Codec.STRING), (e, v) -> e.previewFloor = v, e -> e.previewFloor).add()
                .append(new KeyedCodec<>("@SeedChoice", Codec.STRING), (e, v) -> e.seedChoice = v, e -> e.seedChoice).add()
                .append(new KeyedCodec<>("Pack", Codec.STRING), (e, v) -> e.packBrowserData.pack = v, e -> e.packBrowserData.pack).add()
                .append(new KeyedCodec<>("@PackSearch", Codec.STRING), (e, v) -> e.packBrowserData.search = v, e -> e.packBrowserData.search).add()
                .append(new KeyedCodec<>("@CreateName", Codec.STRING), (e, v) -> e.packBrowserData.createName = v, e -> e.packBrowserData.createName).add()
                .append(new KeyedCodec<>("@CreateGroup", Codec.STRING), (e, v) -> e.packBrowserData.createGroup = v, e -> e.packBrowserData.createGroup).add()
                .append(new KeyedCodec<>("@CreateDescription", Codec.STRING), (e, v) -> e.packBrowserData.createDescription = v, e -> e.packBrowserData.createDescription).add()
                .append(new KeyedCodec<>("@CreateVersion", Codec.STRING), (e, v) -> e.packBrowserData.createVersion = v, e -> e.packBrowserData.createVersion).add()
                .append(new KeyedCodec<>("@CreateWebsite", Codec.STRING), (e, v) -> e.packBrowserData.createWebsite = v, e -> e.packBrowserData.createWebsite).add()
                .append(new KeyedCodec<>("@CreateAuthorName", Codec.STRING), (e, v) -> e.packBrowserData.createAuthorName = v, e -> e.packBrowserData.createAuthorName).add()
                .append(new KeyedCodec<>("ValidateCreate", Codec.STRING), (e, v) -> e.packBrowserData.validateCreate = v, e -> e.packBrowserData.validateCreate).add()
                .append(new KeyedCodec<>("@CreateTargetDir", Codec.STRING), (e, v) -> e.packBrowserData.createTargetDir = v, e -> e.packBrowserData.createTargetDir).add()
                .append(new KeyedCodec<>("@DirectoryFilter", Codec.STRING), (e, v) -> e.packBrowserData.directoryFilter = v, e -> e.packBrowserData.directoryFilter).add()
                .build();

        String action;
        String themeId;
        String strValue;
        Float numValue;
        Integer intValue;
        Boolean boolValue;
        String fieldKey;
        String index;
        String field;
        String blockId;
        String itemStackId;
        String blockSearch;
        String roleId;
        String roleSearch;
        String previewFloor;
        String seedChoice;
        final com.hypixel.hytale.server.core.ui.browser.AssetPackSaveBrowserEventData packBrowserData =
                new com.hypixel.hytale.server.core.ui.browser.AssetPackSaveBrowserEventData();
    }
}
