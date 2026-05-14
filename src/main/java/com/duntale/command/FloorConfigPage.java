package com.duntale.command;

import com.duntale.dungeon.FloorConfigAssetRepository;
import com.duntale.dungeon.FloorConfigService;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.browser.AssetPackSaveBrowser;
import com.hypixel.hytale.server.core.ui.browser.AssetPackSaveBrowserConfig;
import com.hypixel.hytale.server.core.ui.browser.AssetPackSaveBrowserEventData;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interactive UI page for viewing and editing per-floor dungeon generation config overrides.
 *
 * <p>Displays all overridable config fields with their effective values for the specified
 * floor level. Fields explicitly overridden on this floor are marked with a green "●"
 * indicator. The player can switch floors, choose a target asset pack in-page, and click
 * "Save" to persist the current visible config as a full asset snapshot.
 *
 * <p>Opened via {@code /dungeon floorconfig [floor]}.
 *
 * @since 1.6.0
 * @see FloorConfigService
 */
public class FloorConfigPage extends InteractiveCustomUIPage<FloorConfigPage.FloorConfigEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();

    private static final int DEFAULT_FLOOR_LEVEL = 1;
    private static final int MAX_FLOOR_LEVEL = 60;
    private static final String GREEN = "#55FF55";
    private static final String RED = "#FF5555";
    private static final String SET_INDICATOR = "*";
    private static final List<String> THEME_VARIANT_ORDER = List.of(
            "crypt",
            "arcane",
            "hive",
            "temple_dark",
            "volcanic"
    );

    private final AssetPackSaveBrowser packBrowser = new AssetPackSaveBrowser(AssetPackSaveBrowserConfig.defaults());
    private final FloorConfigService floorConfigService;
    private boolean initialized = false;
    private int selectedFloorLevel;
    @Nullable
    private DestructiveAction pendingDestructiveAction;

    /**
     * Creates a new floor config page.
     *
     * @param playerRef          the player opening the page
     * @param floorLevel         the initial floor level to configure
     * @param floorConfigService the floor config service
     */
    public FloorConfigPage(
            @Nonnull PlayerRef playerRef,
            int floorLevel,
            @Nonnull FloorConfigService floorConfigService
    ) {
        super(playerRef, CustomPageLifetime.CanDismiss, FloorConfigEventData.CODEC);
        this.floorConfigService = floorConfigService;
        this.selectedFloorLevel = clampFloorLevel(floorLevel);
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
        }

        cmd.append("Pages/FloorConfig/FloorConfigPage.ui");
        cmd.set("#PackBrowserPage.Visible", false);
        cmd.set("#CreatePackPage.Visible", false);

        populateFloorSelector(cmd);

        FloorConfigService.EffectiveConfig effective = floorConfigService.getEffectiveConfig(selectedFloorLevel);

        refreshHeader(cmd, effective);
        updatePackSelectionDisplay(cmd);
        updateDeleteButtonState(cmd);
        updateConfirmationDialog(cmd);

        populateFields(cmd, effective);

        events.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#FloorSelector",
            new EventData().append("Action", "SelectFloor").append("@SelectedFloor", "#FloorSelector.Value"),
            false
        );

        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton",
                buildSaveBinding());
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ResetButton",
            new EventData().append("Action", "RequestResetAll").append("@SelectedFloor", "#FloorSelector.Value"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DeleteButton",
            new EventData().append("Action", "RequestDeletePersisted").append("@SelectedFloor", "#FloorSelector.Value"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmDialogConfirmButton",
            new EventData().append("Action", "ConfirmDestructive").append("@SelectedFloor", "#FloorSelector.Value"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmDialogCancelButton",
            new EventData().append("Action", "CancelDestructive"));

        packBrowser.buildEventBindings(events, "#BrowsePackButton");
        packBrowser.buildUI(cmd, events);
    }

    // ============================================
    // Event Handling
    // ============================================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull FloorConfigEventData data) {
        long traceId = EVENT_SEQUENCE.incrementAndGet();
        LOGGER.atInfo().log(
                "FloorConfigPage[%d] event action=%s selectedFloor=%s currentFloor=%d player=%s eventRefValid=%s playerRefPresent=%s",
                traceId,
                data.action,
                data.selectedFloor,
                selectedFloorLevel,
                playerRef.getUuid(),
                ref.isValid(),
                playerRef.getReference() != null);

        AssetPackSaveBrowser.ActionResult packResult =
                packBrowser.handleAction(data.action, data.packBrowserData, "#SelectedPackLabel");
        if (packResult != null) {
            pendingDestructiveAction = null;
            LOGGER.atInfo().log(
                    "FloorConfigPage[%d] pack browser result confirmed=%s error=%s",
                    traceId,
                    packResult.packConfirmed(),
                    packResult.errorKey());
            if (packResult.errorKey() != null) {
                playerRef.sendMessage(Message.translation(packResult.errorKey()));
            }
            updatePackSelectionDisplay(packResult.commandBuilder());
            updateDeleteButtonState(packResult.commandBuilder());
            updateConfirmationDialog(packResult.commandBuilder());
            sendEventUpdate(traceId, "pack-browser", ref, store, packResult.commandBuilder(), packResult.eventBuilder(), false);
            return;
        }

        updateSelectedFloor(data.selectedFloor);

        if (data.action == null) {
            LOGGER.atInfo().log("FloorConfigPage[%d] event ignored because action is null", traceId);
            return;
        }

        switch (data.action) {
            case "SelectFloor" -> {
                pendingDestructiveAction = null;
                sendEventUpdate(traceId, "select-floor", ref, store, buildRefreshCommands(null), null, false);
            }
            case "Save" -> handleSave(traceId, ref, store, data);
            case "RequestResetAll" -> requestConfirmation(traceId, ref, store, DestructiveAction.RESET_ALL);
            case "RequestDeletePersisted" -> requestConfirmation(traceId, ref, store, DestructiveAction.DELETE_PERSISTED);
            case "ConfirmDestructive" -> confirmPendingAction(traceId, ref, store);
            case "CancelDestructive" -> cancelConfirmation(traceId, ref, store);
            default -> LOGGER.at(Level.WARNING).log("FloorConfigPage[%d] unknown action: %s", traceId, data.action);
        }
    }

    private void handleSave(
            long traceId,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull FloorConfigEventData data
    ) {
        FloorConfigAssetRepository.PackChoice packChoice = requireSelectedPackChoice(traceId, ref, store);
        if (packChoice == null) {
            LOGGER.atInfo().log("FloorConfigPage[%d] save stopped before persistence because no valid pack is selected", traceId);
            return;
        }

        Map<String, Object> allValues = buildValuesMap(data);
        @SuppressWarnings("unchecked")
        List<String> themeVariants = (List<String>) allValues.get("theme.variants");
        if (themeVariants.isEmpty()) {
            LOGGER.atInfo().log("FloorConfigPage[%d] save rejected because theme variants are empty", traceId);
            playerRef.sendMessage(
                    Message.raw("At least one theme must stay enabled. Use Reset to reveal inherited defaults.")
                            .color(RED));
            updateStatus(traceId, ref, store, "Error — see chat");
            return;
        }

        if (!closePageBeforePersistence(traceId, ref, store, "save")) {
            return;
        }

        try {
            LOGGER.atInfo().log(
                    "FloorConfigPage[%d] save start floor=%d pack=%s fieldCount=%d themes=%s",
                    traceId,
                    selectedFloorLevel,
                    packChoice.name(),
                    allValues.size(),
                    themeVariants);
            floorConfigService.saveAssetOverride(selectedFloorLevel, packChoice.name(), allValues);
            LOGGER.atInfo().log("FloorConfigPage[%d] save returned from service", traceId);
            playerRef.sendMessage(
                    Message.raw("Saved floor " + selectedFloorLevel + " asset config to " + packChoice.name() + ".")
                            .color(GREEN));
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.SEVERE).withCause(e).log(
                    "FloorConfigPage[%d] failed to save floor %d config: %s",
                    traceId,
                    selectedFloorLevel,
                    e.getMessage());
            playerRef.sendMessage(Message.raw("Save failed: " + e.getMessage()).color(RED));
        }
    }

    private void handleDeletePersistedOverride(long traceId, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        FloorConfigAssetRepository.PackChoice packChoice = requireSelectedPackChoice(traceId, ref, store);
        if (packChoice == null) {
            LOGGER.atInfo().log("FloorConfigPage[%d] delete stopped because no valid pack is selected", traceId);
            return;
        }

        pendingDestructiveAction = null;

        if (!closePageBeforePersistence(traceId, ref, store, "delete")) {
            return;
        }

        try {
            LOGGER.atInfo().log(
                    "FloorConfigPage[%d] reset start floor=%d pack=%s",
                    traceId,
                    selectedFloorLevel,
                    packChoice.name());
            boolean deleted = floorConfigService.deleteAssetOverride(selectedFloorLevel, packChoice.name());
            LOGGER.atInfo().log("FloorConfigPage[%d] reset returned from service deleted=%s", traceId, deleted);
            if (deleted) {
                playerRef.sendMessage(
                        Message.raw("Deleted floor " + selectedFloorLevel + " asset config from " + packChoice.name() + ".")
                                .color(GREEN));
            } else {
                playerRef.sendMessage(
                        Message.raw("No floor " + selectedFloorLevel + " asset config existed in " + packChoice.name() + ".")
                                .color(GREEN));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.SEVERE).withCause(e).log(
                    "FloorConfigPage[%d] failed to delete floor %d config: %s",
                    traceId,
                    selectedFloorLevel,
                    e.getMessage());
            playerRef.sendMessage(Message.raw("Delete failed: " + e.getMessage()).color(RED));
        }
    }

    private void handleResetAll(long traceId, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        pendingDestructiveAction = null;
        LOGGER.atInfo().log("FloorConfigPage[%d] resetting visible fields for floor=%d", traceId, selectedFloorLevel);
        sendEventUpdate(traceId, "reset-all", ref, store, buildRefreshCommands("Reset current edits"), null, false);
    }

    private void requestConfirmation(
            long traceId,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull DestructiveAction action
    ) {
        if (action == DestructiveAction.DELETE_PERSISTED && !hasPersistedSelectedOverride()) {
            LOGGER.atInfo().log(
                    "FloorConfigPage[%d] delete confirmation skipped because there is no persisted override floor=%d",
                    traceId,
                    selectedFloorLevel);
            updateStatus(traceId, ref, store, "No persisted floor asset to delete");
            return;
        }

        pendingDestructiveAction = action;
        LOGGER.atInfo().log("FloorConfigPage[%d] showing confirmation dialog for action=%s", traceId, action);
        UICommandBuilder cmd = new UICommandBuilder();
        updateConfirmationDialog(cmd);
        sendEventUpdate(traceId, "confirm-request", ref, store, cmd, null, false);
    }

    private void confirmPendingAction(long traceId, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (pendingDestructiveAction == null) {
            LOGGER.atInfo().log("FloorConfigPage[%d] confirm ignored because no destructive action is pending", traceId);
            return;
        }

        switch (pendingDestructiveAction) {
            case RESET_ALL -> handleResetAll(traceId, ref, store);
            case DELETE_PERSISTED -> handleDeletePersistedOverride(traceId, ref, store);
        }
    }

    private void cancelConfirmation(long traceId, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (pendingDestructiveAction == null) {
            return;
        }
        LOGGER.atInfo().log("FloorConfigPage[%d] canceling confirmation dialog for action=%s", traceId, pendingDestructiveAction);
        pendingDestructiveAction = null;
        UICommandBuilder cmd = new UICommandBuilder();
        updateConfirmationDialog(cmd);
        sendEventUpdate(traceId, "confirm-cancel", ref, store, cmd, null, false);
    }

    private boolean closePageBeforePersistence(
            long traceId,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull String action
    ) {
        if (!ref.isValid()) {
            LOGGER.at(Level.WARNING).log(
                    "FloorConfigPage[%d] cannot close page before %s because event ref is invalid",
                    traceId,
                    action);
            return false;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            LOGGER.at(Level.WARNING).log(
                    "FloorConfigPage[%d] cannot close page before %s because Player component is missing",
                    traceId,
                    action);
            return false;
        }

        LOGGER.atInfo().log("FloorConfigPage[%d] closing page before %s persistence", traceId, action);
        player.getPageManager().setPage(ref, store, Page.None);
        return true;
    }

    // ============================================
    // Display helpers
    // ============================================

    private void populateFields(@Nonnull UICommandBuilder cmd,
                                @Nonnull FloorConfigService.EffectiveConfig effective) {
        for (Map.Entry<String, FloorConfigService.FieldStatus> entry : effective.fields().entrySet()) {
            String path = entry.getKey();
            FloorConfigService.FieldStatus status = entry.getValue();
            setFieldValue(cmd, path, status.value());
            setFieldTag(cmd, path, status.explicit());
        }
    }

    private void setFieldValue(@Nonnull UICommandBuilder cmd, @Nonnull String path, @Nonnull Object value) {
        switch (path) {
            // Size
            case "layout.width" -> cmd.set("#Width.Value", toInt(value));
            case "layout.depth" -> cmd.set("#Depth.Value", toInt(value));
            case "layout.height" -> cmd.set("#Height.Value", toInt(value));
            // Rooms
            case "layout.maxRooms" -> cmd.set("#MaxRooms.Value", toInt(value));
            case "layout.roomDensity" -> cmd.set("#RoomDensity.Value", toFloat(value));
            case "layout.complexity" -> cmd.set("#Complexity.Value", toFloat(value));
            case "layout.minRoomSize" -> cmd.set("#MinRoomSize.Value", toInt(value));
            case "layout.maxRoomSize" -> cmd.set("#MaxRoomSize.Value", toInt(value));
            case "layout.roomShape" -> cmd.set("#RoomShape.Value", value.toString());
            case "layout.irregularity" -> cmd.set("#Irregularity.Value", toFloat(value));
            // Corridors
            case "layout.corridorWidth" -> cmd.set("#CorridorWidth.Value", toInt(value));
            case "layout.branchChance" -> cmd.set("#BranchChance.Value", toFloat(value));
            case "layout.loopChance" -> cmd.set("#LoopChance.Value", toFloat(value));
            case "layout.windingCorridors" -> cmd.set("#WindingCheck #CheckBox.Value", (Boolean) value);
            case "layout.windingFactor" -> cmd.set("#WindingFactor.Value", toFloat(value));
            // Features
            case "layout.pillarFrequency" -> cmd.set("#PillarFreq.Value", toFloat(value));
            case "layout.waterFrequency" -> cmd.set("#WaterFreq.Value", toFloat(value));
            case "layout.lavaFrequency" -> cmd.set("#LavaFreq.Value", toFloat(value));
            case "layout.trapDensity" -> cmd.set("#TrapDensity.Value", toFloat(value));
            case "layout.floorTraps" -> cmd.set("#FloorTrapsCheck #CheckBox.Value", (Boolean) value);
            case "layout.secretWallChance" -> cmd.set("#SecretWallChance.Value", toFloat(value));
            case "layout.merchantSpawnChance" -> cmd.set("#MerchantChance.Value", toFloat(value));
            // Navigation
            case "layout.entrancePlacement" -> cmd.set("#EntrancePlacement.Value", value.toString());
            case "layout.exitDistance" -> cmd.set("#ExitDistance.Value", toFloat(value));
            // Enemies
            case "layout.enemyDensity" -> cmd.set("#EnemyDensity.Value", toFloat(value));
            case "layout.maxEnemiesPerRoom" -> cmd.set("#MaxEnemiesPerRoom.Value", toInt(value));
            case "layout.ambushChance" -> cmd.set("#AmbushChance.Value", toFloat(value));
            case "layout.bossRoom" -> cmd.set("#BossRoomCheck #CheckBox.Value", (Boolean) value);
            // Architecture
            case "layout.erosion" -> cmd.set("#Erosion.Value", toFloat(value));
            case "layout.removeCeiling" -> cmd.set("#RemoveCeilingCheck #CheckBox.Value", (Boolean) value);
            case "layout.flatFloor" -> cmd.set("#FlatFloorCheck #CheckBox.Value", (Boolean) value);
            case "layout.solidFill" -> cmd.set("#SolidFillCheck #CheckBox.Value", (Boolean) value);
            // Theme
            case "theme.variants" -> setThemeVariantValues(cmd, value);
            case "theme.decayFactor" -> cmd.set("#DecayFactor.Value", toFloat(value));
            case "theme.overgrowthFactor" -> cmd.set("#OvergrowthFactor.Value", toFloat(value));
            case "theme.floodingFactor" -> cmd.set("#FloodingFactor.Value", toFloat(value));
            // Pacing
            case "pacing.breatheRoomFrequency" -> cmd.set("#BreatheRoomFreq.Value", toFloat(value));
            case "pacing.difficultyRamp" -> cmd.set("#DifficultyRamp.Value", toFloat(value));
            default -> { /* unknown field — skip */ }
        }
    }

    private void setFieldTag(@Nonnull UICommandBuilder cmd, @Nonnull String path, boolean explicit) {
        String tagId = switch (path) {
            case "layout.width" -> "#WidthTag";
            case "layout.depth" -> "#DepthTag";
            case "layout.height" -> "#HeightTag";
            case "layout.maxRooms" -> "#MaxRoomsTag";
            case "layout.roomDensity" -> "#RoomDensityTag";
            case "layout.complexity" -> "#ComplexityTag";
            case "layout.minRoomSize" -> "#MinRoomSizeTag";
            case "layout.maxRoomSize" -> "#MaxRoomSizeTag";
            case "layout.roomShape" -> "#RoomShapeTag";
            case "layout.irregularity" -> "#IrregularityTag";
            case "layout.corridorWidth" -> "#CorridorWidthTag";
            case "layout.branchChance" -> "#BranchChanceTag";
            case "layout.loopChance" -> "#LoopChanceTag";
            case "layout.windingCorridors" -> "#WindingTag";
            case "layout.windingFactor" -> "#WindingFactorTag";
            case "layout.pillarFrequency" -> "#PillarFreqTag";
            case "layout.waterFrequency" -> "#WaterFreqTag";
            case "layout.lavaFrequency" -> "#LavaFreqTag";
            case "layout.trapDensity" -> "#TrapDensityTag";
            case "layout.floorTraps" -> "#FloorTrapsTag";
            case "layout.secretWallChance" -> "#SecretWallChanceTag";
            case "layout.merchantSpawnChance" -> "#MerchantChanceTag";
            case "layout.entrancePlacement" -> "#EntrancePlacementTag";
            case "layout.exitDistance" -> "#ExitDistanceTag";
            case "layout.enemyDensity" -> "#EnemyDensityTag";
            case "layout.maxEnemiesPerRoom" -> "#MaxEnemiesPerRoomTag";
            case "layout.ambushChance" -> "#AmbushChanceTag";
            case "layout.bossRoom" -> "#BossRoomTag";
            case "layout.erosion" -> "#ErosionTag";
            case "layout.removeCeiling" -> "#RemoveCeilingTag";
            case "layout.flatFloor" -> "#FlatFloorTag";
            case "layout.solidFill" -> "#SolidFillTag";
            case "theme.variants" -> "#ThemeVariantsTag";
            case "theme.decayFactor" -> "#DecayFactorTag";
            case "theme.overgrowthFactor" -> "#OvergrowthFactorTag";
            case "theme.floodingFactor" -> "#FloodingFactorTag";
            case "pacing.breatheRoomFrequency" -> "#BreatheRoomFreqTag";
            case "pacing.difficultyRamp" -> "#DifficultyRampTag";
            default -> null;
        };
        if (tagId != null) {
            cmd.set(tagId + ".Text", explicit ? SET_INDICATOR : "");
        }
    }

    private void populateFloorSelector(@Nonnull UICommandBuilder cmd) {
        ObjectArrayList<DropdownEntryInfo> entries = new ObjectArrayList<>();
        for (int floor = DEFAULT_FLOOR_LEVEL; floor <= MAX_FLOOR_LEVEL; floor++) {
            String value = Integer.toString(floor);
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(value), value));
        }
        cmd.set("#FloorSelector.Entries", entries);
        cmd.set("#FloorSelector.Value", Integer.toString(selectedFloorLevel));
    }

    private void refreshHeader(
            @Nonnull UICommandBuilder cmd,
            @Nonnull FloorConfigService.EffectiveConfig effective
    ) {
        cmd.set("#FloorLabel.Text", "Floor " + selectedFloorLevel + " Config");
        cmd.set("#FloorSelector.Value", Integer.toString(selectedFloorLevel));
        String baseInfo = effective.baseFloor() != null
                ? "Base: Floor " + effective.baseFloor()
                : "All defaults";
        cmd.set("#BaseLabel.Text", baseInfo);
    }

    private void initializeSelectedPack() {
        if (packBrowser.hasSelectedPack()) {
            return;
        }

        floorConfigService.listPackChoices().stream()
                .filter(FloorConfigAssetRepository.PackChoice::isValidTarget)
                .findFirst()
                .ifPresentOrElse(
                        choice -> packBrowser.setSelectedPackKey(choice.name()),
                        () -> floorConfigService.listPackChoices().stream()
                                .filter(FloorConfigAssetRepository.PackChoice::writable)
                                .findFirst()
                                .ifPresent(choice -> packBrowser.setSelectedPackKey(choice.name()))
                );
    }

    private void updatePackSelectionDisplay(@Nonnull UICommandBuilder cmd) {
        AssetPack selectedPack = packBrowser.getSelectedPack();
        if (selectedPack == null) {
            cmd.set("#SelectedPackLabel.Text", "No asset pack selected");
            cmd.set("#PackStatusLabel.Text", "Select a writable pack to save floor config assets.");
            return;
        }

        cmd.set("#SelectedPackLabel.Text", packBrowser.getSelectedPackDisplayName());
        FloorConfigAssetRepository.PackChoice choice = getSelectedPackChoice();
        if (choice == null) {
            cmd.set("#PackStatusLabel.Text", "Selected pack is no longer loaded.");
            return;
        }
        cmd.set("#PackStatusLabel.Text", choice.status());
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
            case RESET_ALL -> {
                cmd.set("#ConfirmDialogTitle.Text", "Reset all unsaved changes?");
                cmd.set("#ConfirmDialogMessage.Text",
                        "This will discard the current unsaved edits for floor " + selectedFloorLevel
                                + " and restore the effective values shown by the active config.");
                cmd.set("#ConfirmDialogConfirmButton.Text", "Reset All");
            }
            case DELETE_PERSISTED -> {
                cmd.set("#ConfirmDialogTitle.Text", "Delete persisted floor config?");
                cmd.set("#ConfirmDialogMessage.Text",
                        "This will delete the saved floor asset for floor " + selectedFloorLevel
                                + " from " + selectedPackDisplayName() + ". This cannot be undone.");
                cmd.set("#ConfirmDialogConfirmButton.Text", "Delete Asset");
            }
        }
    }

    private boolean hasPersistedSelectedOverride() {
        FloorConfigAssetRepository.PackChoice choice = getSelectedPackChoice();
        if (choice == null || !choice.isValidTarget()) {
            return false;
        }

        try {
            return floorConfigService.hasAssetOverride(selectedFloorLevel, choice.name());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Nonnull
    private String selectedPackDisplayName() {
        AssetPack selectedPack = packBrowser.getSelectedPack();
        if (selectedPack == null) {
            return "the selected pack";
        }
        return packBrowser.getSelectedPackDisplayName();
    }

    @Nullable
        private FloorConfigAssetRepository.PackChoice requireSelectedPackChoice(
            long traceId,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
        ) {
        FloorConfigAssetRepository.PackChoice choice = getSelectedPackChoice();
        if (choice == null) {
            LOGGER.atInfo().log("FloorConfigPage[%d] no selected asset pack", traceId);
            playerRef.sendMessage(Message.raw("Select an asset pack before saving or resetting floor config.").color(RED));
            updateStatus(traceId, ref, store, "Select a pack first");
            return null;
        }
        if (!choice.isValidTarget()) {
            LOGGER.atInfo().log(
                "FloorConfigPage[%d] selected asset pack is invalid name=%s status=%s",
                traceId,
                choice.name(),
                choice.status());
            playerRef.sendMessage(
                    Message.raw("Selected asset pack cannot store floor config overrides: " + choice.status()).color(RED));
            updateStatus(traceId, ref, store, "Invalid target pack");
            return null;
        }
        return choice;
    }

    @Nullable
    private FloorConfigAssetRepository.PackChoice getSelectedPackChoice() {
        AssetPack selectedPack = packBrowser.getSelectedPack();
        if (selectedPack == null) {
            return null;
        }

        String packName = selectedPack.getName();
        return floorConfigService.listPackChoices().stream()
                .filter(choice -> choice.name().equals(packName))
                .findFirst()
                .orElse(null);
    }

    private void updateSelectedFloor(@Nullable String selectedFloor) {
        if (selectedFloor == null || selectedFloor.isBlank()) {
            return;
        }
        try {
            selectedFloorLevel = clampFloorLevel(Integer.parseInt(selectedFloor));
        } catch (NumberFormatException e) {
            selectedFloorLevel = DEFAULT_FLOOR_LEVEL;
        }
    }

    private static int clampFloorLevel(int floorLevel) {
        return Math.max(DEFAULT_FLOOR_LEVEL, Math.min(MAX_FLOOR_LEVEL, floorLevel));
    }

    @Nonnull
    private UICommandBuilder buildRefreshCommands(@Nullable String statusText) {
        FloorConfigService.EffectiveConfig effective = floorConfigService.getEffectiveConfig(selectedFloorLevel);
        UICommandBuilder cmd = new UICommandBuilder();

        refreshHeader(cmd, effective);
        updatePackSelectionDisplay(cmd);
        updateDeleteButtonState(cmd);
        updateConfirmationDialog(cmd);
        populateFields(cmd, effective);
        if (statusText != null) {
            cmd.set("#StatusLabel.Text", statusText);
            cmd.set("#StatusLabel.Visible", true);
        }

        return cmd;
    }

    private void updateStatus(long traceId, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull String text) {
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set("#StatusLabel.Text", text);
        cmd.set("#StatusLabel.Visible", true);
        sendEventUpdate(traceId, "status", ref, store, cmd, null, false);
    }

    private void sendEventUpdate(
            long traceId,
            @Nonnull String reason,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nullable UICommandBuilder commandBuilder,
            @Nullable UIEventBuilder eventBuilder,
            boolean clear
    ) {
        int commandCount = commandBuilder != null ? commandBuilder.getCommands().length : 0;
        int eventCount = eventBuilder != null ? eventBuilder.getEvents().length : 0;
        boolean playerRefPresent = playerRef.getReference() != null;
        LOGGER.atInfo().log(
                "FloorConfigPage[%d] sending page update reason=%s commands=%d events=%d clear=%s eventRefValid=%s playerRefPresent=%s",
                traceId,
                reason,
                commandCount,
                eventCount,
                clear,
                ref.isValid(),
                playerRefPresent);

        if (!ref.isValid()) {
            LOGGER.at(Level.WARNING).log("FloorConfigPage[%d] cannot send update because event ref is invalid", traceId);
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            LOGGER.at(Level.WARNING).log("FloorConfigPage[%d] cannot send update because Player component is missing", traceId);
            return;
        }

        player.getPageManager().updateCustomPage(
                new CustomPage(
                        getClass().getName(),
                        false,
                        clear,
                        lifetime,
                        commandBuilder != null ? commandBuilder.getCommands() : UICommandBuilder.EMPTY_COMMAND_ARRAY,
                        eventBuilder != null ? eventBuilder.getEvents() : UIEventBuilder.EMPTY_EVENT_BINDING_ARRAY
                )
        );
        LOGGER.atInfo().log("FloorConfigPage[%d] page update sent reason=%s", traceId, reason);
    }

    // ============================================
    // Values map (event data → field paths)
    // ============================================

    @Nonnull
    private static Map<String, Object> buildValuesMap(@Nonnull FloorConfigEventData d) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfNotNull(map, "layout.width", d.width);
        putIfNotNull(map, "layout.depth", d.depth);
        putIfNotNull(map, "layout.height", d.height);
        putIfNotNull(map, "layout.maxRooms", d.maxRooms);
        putIfNotNull(map, "layout.roomDensity", d.roomDensity);
        putIfNotNull(map, "layout.complexity", d.complexity);
        putIfNotNull(map, "layout.minRoomSize", d.minRoomSize);
        putIfNotNull(map, "layout.maxRoomSize", d.maxRoomSize);
        putIfNotNull(map, "layout.roomShape", d.roomShape);
        putIfNotNull(map, "layout.irregularity", d.irregularity);
        putIfNotNull(map, "layout.corridorWidth", d.corridorWidth);
        putIfNotNull(map, "layout.branchChance", d.branchChance);
        putIfNotNull(map, "layout.loopChance", d.loopChance);
        putIfNotNull(map, "layout.windingCorridors", d.windingCorridors);
        putIfNotNull(map, "layout.windingFactor", d.windingFactor);
        putIfNotNull(map, "layout.pillarFrequency", d.pillarFreq);
        putIfNotNull(map, "layout.waterFrequency", d.waterFreq);
        putIfNotNull(map, "layout.lavaFrequency", d.lavaFreq);
        putIfNotNull(map, "layout.trapDensity", d.trapDensity);
        putIfNotNull(map, "layout.floorTraps", d.floorTraps);
        putIfNotNull(map, "layout.secretWallChance", d.secretWallChance);
        putIfNotNull(map, "layout.merchantSpawnChance", d.merchantChance);
        putIfNotNull(map, "layout.entrancePlacement", d.entrancePlacement);
        putIfNotNull(map, "layout.exitDistance", d.exitDistance);
        putIfNotNull(map, "layout.enemyDensity", d.enemyDensity);
        putIfNotNull(map, "layout.maxEnemiesPerRoom", d.maxEnemiesPerRoom);
        putIfNotNull(map, "layout.ambushChance", d.ambushChance);
        putIfNotNull(map, "layout.bossRoom", d.bossRoom);
        putIfNotNull(map, "layout.erosion", d.erosion);
        putIfNotNull(map, "layout.removeCeiling", d.removeCeiling);
        putIfNotNull(map, "layout.flatFloor", d.flatFloor);
        putIfNotNull(map, "layout.solidFill", d.solidFill);
        map.put("theme.variants", collectThemeVariants(d));
        putIfNotNull(map, "theme.decayFactor", d.decayFactor);
        putIfNotNull(map, "theme.overgrowthFactor", d.overgrowthFactor);
        putIfNotNull(map, "theme.floodingFactor", d.floodingFactor);
        putIfNotNull(map, "pacing.breatheRoomFrequency", d.breatheRoomFreq);
        putIfNotNull(map, "pacing.difficultyRamp", d.difficultyRamp);
        return map;
    }

    private static void putIfNotNull(@Nonnull Map<String, Object> map, @Nonnull String key,
                                     @Nullable Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    // ============================================
    // Type helpers
    // ============================================

    private static int toInt(@Nonnull Object value) {
        return ((Number) value).intValue();
    }

    private static float toFloat(@Nonnull Object value) {
        return ((Number) value).floatValue();
    }

    private static void setThemeVariantValues(@Nonnull UICommandBuilder cmd, @Nonnull Object value) {
        List<String> selectedThemes = value instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of();
        cmd.set("#ThemeCryptCheck #CheckBox.Value", selectedThemes.contains("crypt"));
        cmd.set("#ThemeArcaneCheck #CheckBox.Value", selectedThemes.contains("arcane"));
        cmd.set("#ThemeHiveCheck #CheckBox.Value", selectedThemes.contains("hive"));
        cmd.set("#ThemeTempleDarkCheck #CheckBox.Value", selectedThemes.contains("temple_dark"));
        cmd.set("#ThemeVolcanicCheck #CheckBox.Value", selectedThemes.contains("volcanic"));
    }

    @Nonnull
    private static List<String> collectThemeVariants(@Nonnull FloorConfigEventData d) {
        LinkedHashMap<String, Boolean> selected = new LinkedHashMap<>();
        selected.put("crypt", Boolean.TRUE.equals(d.themeCrypt));
        selected.put("arcane", Boolean.TRUE.equals(d.themeArcane));
        selected.put("hive", Boolean.TRUE.equals(d.themeHive));
        selected.put("temple_dark", Boolean.TRUE.equals(d.themeTempleDark));
        selected.put("volcanic", Boolean.TRUE.equals(d.themeVolcanic));

        return THEME_VARIANT_ORDER.stream()
                .filter(themeId -> Boolean.TRUE.equals(selected.get(themeId)))
                .toList();
    }

    // ============================================
    // Event Binding
    // ============================================

    @Nonnull
    private static EventData buildSaveBinding() {
        return new EventData()
            .append("Action", "Save")
            .append("@SelectedFloor", "#FloorSelector.Value")
                // Size
                .append("@Width", "#Width.Value")
                .append("@Depth", "#Depth.Value")
                .append("@Height", "#Height.Value")
                // Rooms
                .append("@MaxRooms", "#MaxRooms.Value")
                .append("@RoomDensity", "#RoomDensity.Value")
                .append("@Complexity", "#Complexity.Value")
                .append("@MinRoomSize", "#MinRoomSize.Value")
                .append("@MaxRoomSize", "#MaxRoomSize.Value")
                .append("@RoomShape", "#RoomShape.Value")
                .append("@Irregularity", "#Irregularity.Value")
                // Corridors
                .append("@CorridorWidth", "#CorridorWidth.Value")
                .append("@BranchChance", "#BranchChance.Value")
                .append("@LoopChance", "#LoopChance.Value")
                .append("@Winding", "#WindingCheck #CheckBox.Value")
                .append("@WindingFactor", "#WindingFactor.Value")
                // Features
                .append("@PillarFreq", "#PillarFreq.Value")
                .append("@WaterFreq", "#WaterFreq.Value")
                .append("@LavaFreq", "#LavaFreq.Value")
                .append("@TrapDensity", "#TrapDensity.Value")
                .append("@FloorTraps", "#FloorTrapsCheck #CheckBox.Value")
                .append("@SecretWallChance", "#SecretWallChance.Value")
                .append("@MerchantChance", "#MerchantChance.Value")
                // Navigation
                .append("@EntrancePlacement", "#EntrancePlacement.Value")
                .append("@ExitDistance", "#ExitDistance.Value")
                // Enemies
                .append("@EnemyDensity", "#EnemyDensity.Value")
                .append("@MaxEnemiesPerRoom", "#MaxEnemiesPerRoom.Value")
                .append("@AmbushChance", "#AmbushChance.Value")
                .append("@BossRoom", "#BossRoomCheck #CheckBox.Value")
                // Architecture
                .append("@Erosion", "#Erosion.Value")
                .append("@RemoveCeiling", "#RemoveCeilingCheck #CheckBox.Value")
                .append("@FlatFloor", "#FlatFloorCheck #CheckBox.Value")
                .append("@SolidFill", "#SolidFillCheck #CheckBox.Value")
                // Theme
                .append("@ThemeCrypt", "#ThemeCryptCheck #CheckBox.Value")
                .append("@ThemeArcane", "#ThemeArcaneCheck #CheckBox.Value")
                .append("@ThemeHive", "#ThemeHiveCheck #CheckBox.Value")
                .append("@ThemeTempleDark", "#ThemeTempleDarkCheck #CheckBox.Value")
                .append("@ThemeVolcanic", "#ThemeVolcanicCheck #CheckBox.Value")
                .append("@DecayFactor", "#DecayFactor.Value")
                .append("@OvergrowthFactor", "#OvergrowthFactor.Value")
                .append("@FloodingFactor", "#FloodingFactor.Value")
                // Pacing
                .append("@BreatheRoomFreq", "#BreatheRoomFreq.Value")
                .append("@DifficultyRamp", "#DifficultyRamp.Value");
    }

    // ============================================
    // Event Data Codec
    // ============================================

    /**
     * Event data received when Save or Reset All is clicked.
     * Save reads all field values from the UI at dispatch time via {@code @} bindings.
     */
    public static class FloorConfigEventData {

        public static final BuilderCodec<FloorConfigEventData> CODEC = BuilderCodec.builder(
                        FloorConfigEventData.class, FloorConfigEventData::new)
                // Actions
                .append(new KeyedCodec<>("Action", Codec.STRING), (e, v) -> e.action = v, e -> e.action)
                .add()
                .append(new KeyedCodec<>("@SelectedFloor", Codec.STRING), (e, v) -> e.selectedFloor = v, e -> e.selectedFloor)
                .add()
                // Size
                .append(new KeyedCodec<>("@Width", Codec.INTEGER), (e, v) -> e.width = v, e -> e.width)
                .add()
                .append(new KeyedCodec<>("@Depth", Codec.INTEGER), (e, v) -> e.depth = v, e -> e.depth)
                .add()
                .append(new KeyedCodec<>("@Height", Codec.INTEGER), (e, v) -> e.height = v, e -> e.height)
                .add()
                // Rooms
                .append(new KeyedCodec<>("@MaxRooms", Codec.INTEGER), (e, v) -> e.maxRooms = v, e -> e.maxRooms)
                .add()
                .append(new KeyedCodec<>("@RoomDensity", Codec.FLOAT), (e, v) -> e.roomDensity = v, e -> e.roomDensity)
                .add()
                .append(new KeyedCodec<>("@Complexity", Codec.FLOAT), (e, v) -> e.complexity = v, e -> e.complexity)
                .add()
                .append(new KeyedCodec<>("@MinRoomSize", Codec.INTEGER), (e, v) -> e.minRoomSize = v, e -> e.minRoomSize)
                .add()
                .append(new KeyedCodec<>("@MaxRoomSize", Codec.INTEGER), (e, v) -> e.maxRoomSize = v, e -> e.maxRoomSize)
                .add()
                .append(new KeyedCodec<>("@RoomShape", Codec.STRING), (e, v) -> e.roomShape = v, e -> e.roomShape)
                .add()
                .append(new KeyedCodec<>("@Irregularity", Codec.FLOAT), (e, v) -> e.irregularity = v, e -> e.irregularity)
                .add()
                // Corridors
                .append(new KeyedCodec<>("@CorridorWidth", Codec.INTEGER), (e, v) -> e.corridorWidth = v, e -> e.corridorWidth)
                .add()
                .append(new KeyedCodec<>("@BranchChance", Codec.FLOAT), (e, v) -> e.branchChance = v, e -> e.branchChance)
                .add()
                .append(new KeyedCodec<>("@LoopChance", Codec.FLOAT), (e, v) -> e.loopChance = v, e -> e.loopChance)
                .add()
                .append(new KeyedCodec<>("@Winding", Codec.BOOLEAN), (e, v) -> e.windingCorridors = v, e -> e.windingCorridors)
                .add()
                .append(new KeyedCodec<>("@WindingFactor", Codec.FLOAT), (e, v) -> e.windingFactor = v, e -> e.windingFactor)
                .add()
                // Features
                .append(new KeyedCodec<>("@PillarFreq", Codec.FLOAT), (e, v) -> e.pillarFreq = v, e -> e.pillarFreq)
                .add()
                .append(new KeyedCodec<>("@WaterFreq", Codec.FLOAT), (e, v) -> e.waterFreq = v, e -> e.waterFreq)
                .add()
                .append(new KeyedCodec<>("@LavaFreq", Codec.FLOAT), (e, v) -> e.lavaFreq = v, e -> e.lavaFreq)
                .add()
                .append(new KeyedCodec<>("@TrapDensity", Codec.FLOAT), (e, v) -> e.trapDensity = v, e -> e.trapDensity)
                .add()
                .append(new KeyedCodec<>("@FloorTraps", Codec.BOOLEAN), (e, v) -> e.floorTraps = v, e -> e.floorTraps)
                .add()
                .append(new KeyedCodec<>("@SecretWallChance", Codec.FLOAT), (e, v) -> e.secretWallChance = v, e -> e.secretWallChance)
                .add()
                .append(new KeyedCodec<>("@MerchantChance", Codec.FLOAT), (e, v) -> e.merchantChance = v, e -> e.merchantChance)
                .add()
                // Navigation
                .append(new KeyedCodec<>("@EntrancePlacement", Codec.STRING), (e, v) -> e.entrancePlacement = v, e -> e.entrancePlacement)
                .add()
                .append(new KeyedCodec<>("@ExitDistance", Codec.FLOAT), (e, v) -> e.exitDistance = v, e -> e.exitDistance)
                .add()
                // Enemies
                .append(new KeyedCodec<>("@EnemyDensity", Codec.FLOAT), (e, v) -> e.enemyDensity = v, e -> e.enemyDensity)
                .add()
                .append(new KeyedCodec<>("@MaxEnemiesPerRoom", Codec.INTEGER), (e, v) -> e.maxEnemiesPerRoom = v, e -> e.maxEnemiesPerRoom)
                .add()
                .append(new KeyedCodec<>("@AmbushChance", Codec.FLOAT), (e, v) -> e.ambushChance = v, e -> e.ambushChance)
                .add()
                .append(new KeyedCodec<>("@BossRoom", Codec.BOOLEAN), (e, v) -> e.bossRoom = v, e -> e.bossRoom)
                .add()
                // Architecture
                .append(new KeyedCodec<>("@Erosion", Codec.FLOAT), (e, v) -> e.erosion = v, e -> e.erosion)
                .add()
                .append(new KeyedCodec<>("@RemoveCeiling", Codec.BOOLEAN), (e, v) -> e.removeCeiling = v, e -> e.removeCeiling)
                .add()
                .append(new KeyedCodec<>("@FlatFloor", Codec.BOOLEAN), (e, v) -> e.flatFloor = v, e -> e.flatFloor)
                .add()
                .append(new KeyedCodec<>("@SolidFill", Codec.BOOLEAN), (e, v) -> e.solidFill = v, e -> e.solidFill)
                .add()
                // Theme
                .append(new KeyedCodec<>("@ThemeCrypt", Codec.BOOLEAN), (e, v) -> e.themeCrypt = v, e -> e.themeCrypt)
                .add()
                .append(new KeyedCodec<>("@ThemeArcane", Codec.BOOLEAN), (e, v) -> e.themeArcane = v, e -> e.themeArcane)
                .add()
                .append(new KeyedCodec<>("@ThemeHive", Codec.BOOLEAN), (e, v) -> e.themeHive = v, e -> e.themeHive)
                .add()
                .append(new KeyedCodec<>("@ThemeTempleDark", Codec.BOOLEAN), (e, v) -> e.themeTempleDark = v, e -> e.themeTempleDark)
                .add()
                .append(new KeyedCodec<>("@ThemeVolcanic", Codec.BOOLEAN), (e, v) -> e.themeVolcanic = v, e -> e.themeVolcanic)
                .add()
                .append(new KeyedCodec<>("@DecayFactor", Codec.FLOAT), (e, v) -> e.decayFactor = v, e -> e.decayFactor)
                .add()
                .append(new KeyedCodec<>("@OvergrowthFactor", Codec.FLOAT), (e, v) -> e.overgrowthFactor = v, e -> e.overgrowthFactor)
                .add()
                .append(new KeyedCodec<>("@FloodingFactor", Codec.FLOAT), (e, v) -> e.floodingFactor = v, e -> e.floodingFactor)
                .add()
                // Pacing
                .append(new KeyedCodec<>("@BreatheRoomFreq", Codec.FLOAT), (e, v) -> e.breatheRoomFreq = v, e -> e.breatheRoomFreq)
                .add()
                .append(new KeyedCodec<>("@DifficultyRamp", Codec.FLOAT), (e, v) -> e.difficultyRamp = v, e -> e.difficultyRamp)
                .add()
                .append(new KeyedCodec<>("Pack", Codec.STRING), (e, v) -> e.packBrowserData.pack = v, e -> e.packBrowserData.pack)
                .add()
                .append(new KeyedCodec<>("@PackSearch", Codec.STRING), (e, v) -> e.packBrowserData.search = v, e -> e.packBrowserData.search)
                .add()
                .append(new KeyedCodec<>("@CreateName", Codec.STRING), (e, v) -> e.packBrowserData.createName = v, e -> e.packBrowserData.createName)
                .add()
                .append(new KeyedCodec<>("@CreateGroup", Codec.STRING), (e, v) -> e.packBrowserData.createGroup = v, e -> e.packBrowserData.createGroup)
                .add()
                .append(new KeyedCodec<>("@CreateDescription", Codec.STRING), (e, v) -> e.packBrowserData.createDescription = v, e -> e.packBrowserData.createDescription)
                .add()
                .append(new KeyedCodec<>("@CreateVersion", Codec.STRING), (e, v) -> e.packBrowserData.createVersion = v, e -> e.packBrowserData.createVersion)
                .add()
                .append(new KeyedCodec<>("@CreateWebsite", Codec.STRING), (e, v) -> e.packBrowserData.createWebsite = v, e -> e.packBrowserData.createWebsite)
                .add()
                .append(new KeyedCodec<>("@CreateAuthorName", Codec.STRING), (e, v) -> e.packBrowserData.createAuthorName = v, e -> e.packBrowserData.createAuthorName)
                .add()
                .append(new KeyedCodec<>("ValidateCreate", Codec.STRING), (e, v) -> e.packBrowserData.validateCreate = v, e -> e.packBrowserData.validateCreate)
                .add()
                .append(new KeyedCodec<>("@CreateTargetDir", Codec.STRING), (e, v) -> e.packBrowserData.createTargetDir = v, e -> e.packBrowserData.createTargetDir)
                .add()
                .append(new KeyedCodec<>("@DirectoryFilter", Codec.STRING), (e, v) -> e.packBrowserData.directoryFilter = v, e -> e.packBrowserData.directoryFilter)
                .add()
                .build();

        // Actions
            String action;
            String selectedFloor;
        // Size
        Integer width, depth, height;
        // Rooms
        Integer maxRooms;
        Float roomDensity, complexity;
        Integer minRoomSize, maxRoomSize;
        String roomShape;
        Float irregularity;
        // Corridors
        Integer corridorWidth;
        Float branchChance, loopChance;
        Boolean windingCorridors;
        Float windingFactor;
        // Features
        Float pillarFreq, waterFreq, lavaFreq;
        Float trapDensity;
        Boolean floorTraps;
        Float secretWallChance, merchantChance;
        // Navigation
        String entrancePlacement;
        Float exitDistance;
        // Enemies
        Float enemyDensity;
        Integer maxEnemiesPerRoom;
        Float ambushChance;
        Boolean bossRoom;
        // Architecture
        Float erosion;
        Boolean removeCeiling, flatFloor, solidFill;
        // Theme
        Boolean themeCrypt, themeArcane, themeHive, themeTempleDark, themeVolcanic;
        Float decayFactor, overgrowthFactor, floodingFactor;
        // Pacing
        Float breatheRoomFreq, difficultyRamp;
        final AssetPackSaveBrowserEventData packBrowserData = new AssetPackSaveBrowserEventData();
    }

    private enum DestructiveAction {
        RESET_ALL,
        DELETE_PERSISTED
    }
}
