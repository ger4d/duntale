package com.duntale.zsquad;

import com.duntale.zsquad.camera.ClickToMoveManager;
import com.duntale.zsquad.companion.CompanionService;
import com.duntale.zsquad.companion.CompanionSpawner;
import com.duntale.zsquad.config.asset.CustomizeCharacterConfigAsset;
import com.duntale.zsquad.progression.ProgressionService;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.ApplyLookType;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import it.unimi.dsi.fastutil.Pair;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class CustomizeCharacterService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String ALLOWED_ROLE = CompanionService.DEFAULT_COMPANION_ROLE;
    private static final String COLOR_GREEN = "#55FF55";
    private static final String COLOR_RED = "#FF5555";
    private static final String COLOR_YELLOW = "#FFD700";

    private final ClickToMoveManager clickToMoveManager;
    private final CompanionService companionService;
    private final CompanionSpawner companionSpawner;
    private final ProgressionService progressionService;
    private final AtomicInteger nextSlotIndex = new AtomicInteger();
    private final Map<UUID, Integer> reservedSlotsByPlayer = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> slotUsageCounts = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveCustomization> activeCustomizations = new ConcurrentHashMap<>();

    CustomizeCharacterService(
            @Nullable ClickToMoveManager clickToMoveManager,
            @Nullable CompanionService companionService,
            @Nullable CompanionSpawner companionSpawner,
            @Nullable ProgressionService progressionService
    ) {
        this.clickToMoveManager = clickToMoveManager;
        this.companionService = companionService;
        this.companionSpawner = companionSpawner;
        this.progressionService = progressionService;
    }

    void start(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        UUID playerId = playerRef.getUuid();
        cleanup(playerId);

        if (clickToMoveManager != null) {
            clickToMoveManager.disable(playerId);
        }

        CustomizeCharacterConfig config = loadConfig();
        World world = store.getExternalData().getWorld();
        Transform fallbackSpawn = world.getWorldConfig().getSpawnProvider().getSpawnPoint(ref, store);
        SlotReservation reservation = reserveSlot(playerId, config.setupSlots());
        Transform playerTransform = reservation.slot() != null
                ? reservation.slot().toPlayerTransform()
                : fallbackSpawn;

        if (reservation.slot() == null) {
            LOGGER.atWarning().log("CustomizeCharacter setup slots are not configured; using world spawn fallback");
            playerRef.sendMessage(Message.raw("Character setup is using the world spawn until setup slots are configured.")
                    .color(COLOR_YELLOW));
        }

        store.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(world, playerTransform));

        String selectedRoleName = config.defaultCompanionRole();
        Ref<EntityStore> previewRef = spawnPreviewCompanion(store, playerId, selectedRoleName, config, playerTransform);
        activeCustomizations.put(playerId, new ActiveCustomization(
                playerId,
                reservation.slotIndex(),
                previewRef,
                selectedRoleName,
                world
        ));

        applySetupCamera(playerRef, config, reservation.slot(), playerTransform);
    }

    boolean complete(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef,
            @Nullable String roleName,
            @Nullable String companionName
    ) {
        UUID playerId = playerRef.getUuid();
        ActiveCustomization activeCustomization = activeCustomizations.get(playerId);
        if (activeCustomization == null || companionService == null) {
            playerRef.sendMessage(Message.raw("Character setup is not active right now.").color(COLOR_RED));
            return false;
        }

        CustomizeCharacterConfig config = loadConfig();
        String normalizedName = validateName(companionName);
        if (normalizedName == null) {
            playerRef.sendMessage(Message.raw("Companion names must be 1-24 characters using letters, digits, spaces, underscores, apostrophes, or hyphens.")
                    .color(COLOR_RED));
            return false;
        }

        if (roleName == null || !ALLOWED_ROLE.equals(roleName) || !config.defaultCompanionRole().equals(roleName)) {
            playerRef.sendMessage(Message.raw("Only the Wolf companion is available currently.").color(COLOR_RED));
            return false;
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null || npcPlugin.getIndex(roleName) < 0) {
            LOGGER.atWarning().log("CustomizeCharacter completion blocked because role %s is missing", roleName);
            playerRef.sendMessage(Message.raw("Companion setup is misconfigured right now. Please try again later.")
                    .color(COLOR_RED));
            return false;
        }

        if (!companionService.persistPreference(playerId, roleName, normalizedName)) {
            playerRef.sendMessage(Message.raw("Unable to save your companion right now. Please try again.").color(COLOR_RED));
            return false;
        }

        finishCustomization(activeCustomization, store);
        resetSetupCamera(playerRef);
        closeCustomPage(ref, store);
        if (clickToMoveManager != null) {
            clickToMoveManager.disable(playerId);
        }
        companionService.spawn(store, ref, playerId);
        playerRef.sendMessage(Message.raw("Your Wolf companion is ready. Welcome to the village.").color(COLOR_GREEN));
        return true;
    }

    void updatePreviewName(
            @Nonnull UUID playerId,
            @Nonnull Store<EntityStore> store,
            @Nullable String companionName
    ) {
        ActiveCustomization activeCustomization = activeCustomizations.get(playerId);
        if (activeCustomization == null) {
            return;
        }

        Ref<EntityStore> previewRef = activeCustomization.previewCompanionRef();
        if (previewRef == null || !previewRef.isValid()) {
            return;
        }

        int level = progressionService != null ? Math.max(1, progressionService.getLevel(playerId)) : 1;
        String previewName = sanitizePreviewName(companionName);
        store.ensureAndGetComponent(previewRef, Nameplate.getComponentType()).setText(
                CompanionSpawner.buildNameplateText(activeCustomization.selectedRoleName(), previewName, level)
        );
    }

    void cleanup(@Nonnull UUID playerId) {
        cleanup(playerId, null);
    }

    void cleanup(@Nonnull UUID playerId, @Nullable Store<EntityStore> store) {
        ActiveCustomization activeCustomization = activeCustomizations.remove(playerId);
        if (activeCustomization == null) {
            releaseReservation(playerId);
            return;
        }
        finishCustomization(activeCustomization, store);
    }

    @Nonnull
    SlotReservation reserveSlot(
            @Nonnull UUID playerId,
            @Nonnull List<CustomizeCharacterConfig.SetupSlot> setupSlots
    ) {
        releaseReservation(playerId);

        if (setupSlots.isEmpty()) {
            return new SlotReservation(-1, null);
        }

        int candidateIndex = Math.floorMod(nextSlotIndex.getAndIncrement(), setupSlots.size());
        for (int offset = 0; offset < setupSlots.size(); offset++) {
            int slotIndex = (candidateIndex + offset) % setupSlots.size();
            if (slotUsageCounts.getOrDefault(slotIndex, 0) == 0) {
                reserveIndex(playerId, slotIndex);
                return new SlotReservation(slotIndex, setupSlots.get(slotIndex));
            }
        }

        reserveIndex(playerId, candidateIndex);
        return new SlotReservation(candidateIndex, setupSlots.get(candidateIndex));
    }

    void releaseReservation(@Nonnull UUID playerId) {
        Integer slotIndex = reservedSlotsByPlayer.remove(playerId);
        if (slotIndex == null || slotIndex < 0) {
            return;
        }

        slotUsageCounts.computeIfPresent(slotIndex, (ignored, count) -> count > 1 ? count - 1 : null);
    }

    private void reserveIndex(@Nonnull UUID playerId, int slotIndex) {
        reservedSlotsByPlayer.put(playerId, slotIndex);
        slotUsageCounts.merge(slotIndex, 1, Integer::sum);
    }

    @Nullable
    private Ref<EntityStore> spawnPreviewCompanion(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID playerId,
            @Nonnull String roleName,
            @Nonnull CustomizeCharacterConfig config,
            @Nonnull Transform playerTransform
    ) {
        if (companionSpawner == null) {
            return null;
        }

        Vector3d previewPosition = computeCompanionPosition(playerTransform, config.companionOffset());
        int level = progressionService != null ? Math.max(1, progressionService.getLevel(playerId)) : 1;
        Pair<Ref<EntityStore>, ?> spawnResult = companionSpawner.spawn(store, roleName, null, previewPosition, level);
        if (spawnResult == null || spawnResult.first() == null || !spawnResult.first().isValid()) {
            LOGGER.atWarning().log("Failed to spawn preview companion %s for player %s", roleName, playerId);
            return null;
        }

        Ref<EntityStore> previewRef = spawnResult.first();
        store.putComponent(previewRef, EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
        return previewRef;
    }

    @Nonnull
    private Vector3d computeCompanionPosition(
            @Nonnull Transform playerTransform,
            @Nonnull CustomizeCharacterConfig.CompanionOffset companionOffset
    ) {
        double yaw = playerTransform.getRotation().yaw();
        double rightX = Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        double forwardX = -Math.sin(yaw);
        double forwardZ = -Math.cos(yaw);

        return new Vector3d(
                playerTransform.getPosition().x + companionOffset.x() * rightX + companionOffset.z() * forwardX,
                playerTransform.getPosition().y + companionOffset.y(),
                playerTransform.getPosition().z + companionOffset.x() * rightZ + companionOffset.z() * forwardZ
        );
    }

    private void applySetupCamera(
            @Nonnull PlayerRef playerRef,
            @Nonnull CustomizeCharacterConfig config,
            @Nullable CustomizeCharacterConfig.SetupSlot slot,
            @Nonnull Transform playerTransform
    ) {
        float yaw = slot != null ? slot.resolvedCameraYaw() : playerTransform.getRotation().yaw();

        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 0.2F;
        settings.rotationLerpSpeed = 0.2F;
        settings.distance = config.camera().distance();
        settings.displayCursor = true;
        settings.displayReticle = false;
        settings.sendMouseMotion = false;
        settings.allowPitchControls = false;
        settings.isFirstPerson = false;
        settings.attachedToType = AttachedToType.None;
        settings.eyeOffset = false;
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
        settings.positionOffset = new Position(
            playerTransform.getPosition().x,
            playerTransform.getPosition().y + config.camera().heightOffset(),
            playerTransform.getPosition().z
        );
        settings.rotationType = RotationType.Custom;
        settings.movementForceRotationType = MovementForceRotationType.Custom;
        settings.rotation = new Direction(yaw, config.camera().pitch(), 0.0F);
        settings.applyLookType = ApplyLookType.Rotation;
        settings.lookMultiplier = new Vector2f(0.0F, 0.0F);
        settings.movementMultiplier = new Vector3f(0.0F, 0.0F, 0.0F);
        settings.mouseInputType = MouseInputType.LookAtPlane;
        settings.planeNormal = new Vector3f(0.0F, 1.0F, 0.0F);

        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, settings));
    }

    private void resetSetupCamera(@Nonnull PlayerRef playerRef) {
        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
    }

    private void closeCustomPage(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().setPage(ref, store, Page.None);
        }
    }

    private void finishCustomization(@Nonnull ActiveCustomization activeCustomization, @Nullable Store<EntityStore> store) {
        activeCustomizations.remove(activeCustomization.playerId());
        releaseReservation(activeCustomization.playerId());

        Ref<EntityStore> previewRef = activeCustomization.previewCompanionRef();
        if (previewRef == null || !previewRef.isValid()) {
            return;
        }

        if (store != null && store.isInThread()) {
            store.removeEntity(previewRef, RemoveReason.REMOVE);
            return;
        }

        activeCustomization.world().execute(() -> {
            Store<EntityStore> worldStore = activeCustomization.world().getEntityStore().getStore();
            if (previewRef.isValid()) {
                worldStore.removeEntity(previewRef, RemoveReason.REMOVE);
            }
        });
    }

    @Nonnull
    private CustomizeCharacterConfig loadConfig() {
        CustomizeCharacterConfig config = CustomizeCharacterConfigAsset.getConfig();
        return config != null ? config : CustomizeCharacterConfig.defaultConfig();
    }

    @Nullable
    private String validateName(@Nullable String companionName) {
        if (companionName == null) {
            return null;
        }

        String normalized = companionName.trim();
        if (normalized.isEmpty() || normalized.length() > 24) {
            return null;
        }

        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            if (Character.isISOControl(character)) {
                return null;
            }
            if (!(Character.isLetterOrDigit(character)
                    || character == ' '
                    || character == '_'
                    || character == '\''
                    || character == '-')) {
                return null;
            }
        }

        return normalized;
    }

    @Nullable
    private String sanitizePreviewName(@Nullable String companionName) {
        if (companionName == null) {
            return null;
        }

        String normalized = companionName.trim();
        if (normalized.isEmpty()) {
            return null;
        }

        StringBuilder sanitized = new StringBuilder();
        for (int i = 0; i < normalized.length() && sanitized.length() < 24; i++) {
            char character = normalized.charAt(i);
            if (Character.isISOControl(character)) {
                continue;
            }
            if (Character.isLetterOrDigit(character)
                    || character == ' '
                    || character == '_'
                    || character == '\''
                    || character == '-') {
                sanitized.append(character);
            }
        }

        return sanitized.isEmpty() ? null : sanitized.toString().trim();
    }

    record SlotReservation(int slotIndex, @Nullable CustomizeCharacterConfig.SetupSlot slot) {
    }

    record ActiveCustomization(
            @Nonnull UUID playerId,
            int slotIndex,
            @Nullable Ref<EntityStore> previewCompanionRef,
            @Nonnull String selectedRoleName,
            @Nonnull World world
    ) {
    }
}