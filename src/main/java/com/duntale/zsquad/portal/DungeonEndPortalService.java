package com.duntale.zsquad.portal;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.zsquad.dungeon.DungeonInstance;
import com.hypixel.hytale.builtin.triggervolumes.EntityTargetType;
import com.hypixel.hytale.builtin.triggervolumes.TriggerVolumesPlugin;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType;
import com.hypixel.hytale.builtin.triggervolumes.event.TriggerVolumeEvent;
import com.hypixel.hytale.builtin.triggervolumes.manager.TriggerVolumeManager;
import com.hypixel.hytale.builtin.triggervolumes.manager.VolumeEntry;
import com.hypixel.hytale.builtin.triggervolumes.shape.BoxShape;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.component.PropComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and resolves dynamic dungeon end portals for active dungeon floors.
 */
public final class DungeonEndPortalService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PORTAL_ID_PREFIX = "dungeon_end_portal_";
    private static final String FLOOR_SUFFIX_DELIMITER = "_f";

    static final String MODEL_ASSET_ID = "Portal_Dungeon";
    static final double PORTAL_XZ_OFFSET = 0.5D;
    static final double VOLUME_HALF_WIDTH = 1.25D;
    static final double VOLUME_HEIGHT = 2.75D;
    static final float VOLUME_COOLDOWN_SECONDS = 2.0F;

    private final Set<String> spawnedVisualVolumeIds = ConcurrentHashMap.newKeySet();

    /**
     * Parsed target metadata for a dynamic dungeon end portal volume.
     *
     * @param instanceId the dungeon instance identifier encoded in the volume id
     * @param floorLevel the floor level encoded in the volume id
     */
    public record EndPortalTarget(@Nonnull String instanceId, int floorLevel) {

        /**
         * Creates a parsed end-portal target.
         *
         * @param instanceId the dungeon instance identifier encoded in the volume id
         * @param floorLevel the floor level encoded in the volume id
         */
        public EndPortalTarget {
            Objects.requireNonNull(instanceId, "instanceId");
            if (instanceId.isBlank()) {
                throw new IllegalArgumentException("instanceId must not be blank");
            }
            if (floorLevel < 1) {
                throw new IllegalArgumentException("floorLevel must be at least 1");
            }
        }
    }

    /**
     * Resolves the dynamic end-portal target from an enter trigger event.
     *
     * @param event the trigger event to inspect
     * @return the parsed target when the event is a matching dynamic enter event
     */
    @Nonnull
    public Optional<EndPortalTarget> parseTarget(@Nonnull TriggerVolumeEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getTriggerEventType() != TriggerEventType.ENTER) {
            return Optional.empty();
        }
        return parseTarget(event.getVolumeId());
    }

    /**
     * Ensures the active dungeon floor has a deterministic trigger volume and portal model.
     *
     * @param world the live dungeon world that owns the portal
     * @param store the entity store for that world
     * @param instance the active dungeon floor metadata with a translated exit position
     */
    public void ensurePortal(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull DungeonInstance instance
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(instance, "instance");

        TriggerVolumesPlugin plugin = TriggerVolumesPlugin.get();
        if (plugin == null) {
            LOGGER.atWarning().log(
                    "Unable to create dungeon end portal for instance %s because TriggerVolumesPlugin is unavailable",
                    instance.instanceId()
            );
            return;
        }

        TriggerVolumeManager manager = store.getResource(plugin.getManagerResourceType());
        if (manager == null) {
            LOGGER.atWarning().log(
                    "Unable to create dungeon end portal for instance %s in world %s because no TriggerVolumeManager is available",
                    instance.instanceId(),
                    world.getName()
            );
            return;
        }

        String volumeId = volumeIdFor(instance);
        boolean registeredVolume = manager.hasVolume(volumeId);
        if (!registeredVolume) {
            VolumeEntry entry = buildVolumeEntry(world.getName(), instance);
            manager.register(volumeId, entry);
            manager.notifyViewersAdd(entry);
        }

        spawnPortalVisual(store, volumeId, portalOrigin(instance.exitPosition()));

        if (!registeredVolume) {
            LOGGER.atInfo().log(
                    "Registered dungeon end portal %s for world %s floor %d at %s",
                    volumeId,
                    world.getName(),
                    instance.floorLevel(),
                    instance.exitPosition()
            );
        }
    }

    /**
     * Disables the dynamic portal volume for a floor that is already transitioning.
     *
     * @param store the entity store that owns the trigger volume manager
     * @param target the parsed portal target to disable
     * @return {@code true} when a matching volume was removed
     */
    public boolean disablePortal(@Nonnull Store<EntityStore> store, @Nonnull EndPortalTarget target) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(target, "target");

        TriggerVolumesPlugin plugin = TriggerVolumesPlugin.get();
        if (plugin == null) {
            return false;
        }

        TriggerVolumeManager manager = store.getResource(plugin.getManagerResourceType());
        if (manager == null) {
            return false;
        }

        String volumeId = volumeIdFor(target.instanceId(), target.floorLevel());
        if (!manager.hasVolume(volumeId)) {
            return false;
        }

        manager.unregister(volumeId);
        manager.notifyViewersRemove(volumeId);
        return true;
    }

    @Nonnull
    String volumeIdFor(@Nonnull DungeonInstance instance) {
        Objects.requireNonNull(instance, "instance");
        return volumeIdFor(instance.instanceId(), instance.floorLevel());
    }

    @Nonnull
    String volumeIdFor(@Nonnull String instanceId, int floorLevel) {
        Objects.requireNonNull(instanceId, "instanceId");
        if (instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        if (floorLevel < 1) {
            throw new IllegalArgumentException("floorLevel must be at least 1");
        }
        return PORTAL_ID_PREFIX + instanceId + FLOOR_SUFFIX_DELIMITER + floorLevel;
    }

    @Nonnull
    Optional<EndPortalTarget> parseTarget(@Nonnull String volumeId) {
        Objects.requireNonNull(volumeId, "volumeId");
        if (!volumeId.startsWith(PORTAL_ID_PREFIX)) {
            return Optional.empty();
        }

        int floorDelimiterIndex = volumeId.lastIndexOf(FLOOR_SUFFIX_DELIMITER);
        int instanceStartIndex = PORTAL_ID_PREFIX.length();
        if (floorDelimiterIndex <= instanceStartIndex || floorDelimiterIndex == volumeId.length() - FLOOR_SUFFIX_DELIMITER.length()) {
            return Optional.empty();
        }

        String instanceId = volumeId.substring(instanceStartIndex, floorDelimiterIndex);
        if (instanceId.isBlank()) {
            return Optional.empty();
        }

        String floorToken = volumeId.substring(floorDelimiterIndex + FLOOR_SUFFIX_DELIMITER.length());
        try {
            int floorLevel = Integer.parseInt(floorToken);
            if (floorLevel < 1) {
                return Optional.empty();
            }
            return Optional.of(new EndPortalTarget(instanceId, floorLevel));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    boolean matchesEnterEvent(@Nonnull TriggerVolumeEvent event) {
        return parseTarget(event).isPresent();
    }

    @Nonnull
    VolumeEntry buildVolumeEntry(@Nonnull String worldName, @Nonnull DungeonInstance instance) {
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(instance, "instance");
        Vector3d origin = portalOrigin(instance.exitPosition());
        VolumeEntry entry = new VolumeEntry(
                volumeIdFor(instance),
                worldName.toLowerCase(Locale.ROOT),
                origin,
                new BoxShape(
                        new Vector3d(-VOLUME_HALF_WIDTH, 0.0D, -VOLUME_HALF_WIDTH),
                        new Vector3d(VOLUME_HALF_WIDTH, VOLUME_HEIGHT, VOLUME_HALF_WIDTH)
                ),
                List.of(),
                EnumSet.of(EntityTargetType.PLAYER),
                true
        );
        entry.setCooldown(VOLUME_COOLDOWN_SECONDS);
        return entry;
    }

    @Nonnull
    Vector3d portalOrigin(@Nonnull Vec3i exitPosition) {
        Objects.requireNonNull(exitPosition, "exitPosition");
        return new Vector3d(
                exitPosition.x() + PORTAL_XZ_OFFSET,
                exitPosition.y(),
                exitPosition.z() + PORTAL_XZ_OFFSET
        );
    }

    private void spawnPortalVisual(
            @Nonnull Store<EntityStore> store,
            @Nonnull String volumeId,
            @Nonnull Vector3d origin
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(volumeId, "volumeId");
        Objects.requireNonNull(origin, "origin");
        if (!spawnedVisualVolumeIds.add(volumeId)) {
            return;
        }

        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(MODEL_ASSET_ID);
        if (modelAsset == null) {
            spawnedVisualVolumeIds.remove(volumeId);
            LOGGER.atWarning().log(
                    "Dungeon end portal visual asset %s is missing; keeping the trigger volume active",
                    MODEL_ASSET_ID
            );
            return;
        }

        Model model = Model.createStaticScaledModel(modelAsset, 1.0F);
        Rotation3f rotation = new Rotation3f(Rotation3f.IDENTITY);
        Holder<EntityStore> holder = store.getRegistry().newHolder();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.ensureComponent(Intangible.getComponentType());
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(origin, rotation));
        holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
        holder.addComponent(
            PersistentModel.getComponentType(),
            new PersistentModel(new Model.ModelReference(MODEL_ASSET_ID, 1.0F, null, true))
        );
        holder.addComponent(HeadRotation.getComponentType(), new HeadRotation(rotation));
        holder.addComponent(PropComponent.getComponentType(), PropComponent.get());
        holder.ensureComponent(UUIDComponent.getComponentType());
        store.addEntity(holder, AddReason.SPAWN);
    }
}