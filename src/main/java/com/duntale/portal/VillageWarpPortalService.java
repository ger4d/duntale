package com.duntale.portal;

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
import java.util.UUID;

/**
 * Creates and resolves dynamic village warp portals.
 *
 * <p>Spawns persistent portals in the dungeon world that teleport any player who steps
 * into them back to the village world, without ending or finalising the dungeon.
 */
public final class VillageWarpPortalService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PORTAL_ID_PREFIX = "village_warp_portal_";

    static final String MODEL_ASSET_ID = "Portal_Dungeon";
    static final double VOLUME_HALF_WIDTH = 1.25D;
    static final double VOLUME_HEIGHT = 2.75D;
    static final float VOLUME_COOLDOWN_SECONDS = 2.0F;

    public VillageWarpPortalService() {
    }

    /**
     * Checks if the given event is a player entering a village warp portal.
     *
     * @param event the trigger volume event to match
     * @return {@code true} if it matches, {@code false} otherwise
     */
    public boolean matches(@Nonnull TriggerVolumeEvent event) {
        Objects.requireNonNull(event, "event");
        return event.getTriggerEventType() == TriggerEventType.ENTER
                && event.getVolumeId() != null
                && event.getVolumeId().startsWith(PORTAL_ID_PREFIX);
    }

    /**
     * Places a persistent, shared village warp portal in the specified world.
     *
     * @param world     the world to place the portal in
     * @param store     the entity store of the world
     * @param origin    the position at which to spawn the portal
     * @param worldName the name of the world
     */
    public void placePortal(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull Vector3d origin,
            @Nonnull String worldName
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(worldName, "worldName");

        TriggerVolumesPlugin plugin = TriggerVolumesPlugin.get();
        if (plugin == null) {
            LOGGER.atWarning().log(
                    "Unable to create village warp portal in world %s because TriggerVolumesPlugin is unavailable",
                    worldName
            );
            return;
        }

        TriggerVolumeManager manager = store.getResource(plugin.getManagerResourceType());
        if (manager == null) {
            LOGGER.atWarning().log(
                    "Unable to create village warp portal in world %s because no TriggerVolumeManager is available",
                    worldName
            );
            return;
        }

        String volumeId = PORTAL_ID_PREFIX + worldName + "_" + UUID.randomUUID().toString();
        Vector3d portalPos = new Vector3d(origin);

        VolumeEntry entry = new VolumeEntry(
                volumeId,
                worldName.toLowerCase(Locale.ROOT),
                portalPos,
                new BoxShape(
                        new Vector3d(-VOLUME_HALF_WIDTH, 0.0D, -VOLUME_HALF_WIDTH),
                        new Vector3d(VOLUME_HALF_WIDTH, VOLUME_HEIGHT, VOLUME_HALF_WIDTH)
                ),
                List.of(),
                EnumSet.of(EntityTargetType.PLAYER),
                true
        );
        entry.setCooldown(VOLUME_COOLDOWN_SECONDS);

        manager.register(volumeId, entry);
        manager.notifyViewersAdd(entry);

        spawnPortalVisual(store, volumeId, portalPos);

        LOGGER.atInfo().log(
                "Registered village warp portal %s for world %s at %s",
                volumeId,
                worldName,
                portalPos
        );
    }

    private void spawnPortalVisual(
            @Nonnull Store<EntityStore> store,
            @Nonnull String volumeId,
            @Nonnull Vector3d origin
    ) {
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(MODEL_ASSET_ID);
        if (modelAsset == null) {
            LOGGER.atWarning().log(
                    "Village warp portal visual asset %s is missing",
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
