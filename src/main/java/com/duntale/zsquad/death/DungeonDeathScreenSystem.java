package com.duntale.zsquad.death;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Replaces the built-in respawn page for deaths that occur inside active dungeon instances.
 */
public class DungeonDeathScreenSystem extends DeathSystems.OnDeathSystem {

    @Nonnull
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.BEFORE, DeathSystems.PlayerDeathScreen.class)
    );

    private final DungeonRespawnService respawnService;

    /**
     * Creates a dungeon death screen system.
     *
     * @param respawnService the dungeon respawn service
     */
    public DungeonDeathScreenSystem(@Nonnull DungeonRespawnService respawnService) {
        this.respawnService = Objects.requireNonNull(respawnService, "respawnService");
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(
                Player.getComponentType(),
                PlayerRef.getComponentType(),
                UUIDComponent.getComponentType()
        );
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        if (player == null || playerRef == null || uuidComponent == null) {
            return;
        }

        World world = store.getExternalData().getWorld();
        String worldName = world != null ? world.getName() : null;
        Message deathReason = resolveDeathReason(ref, component, commandBuffer);
        Optional<DungeonDeathContext> context = respawnService.resolveContext(
                uuidComponent.getUuid(),
                worldName,
                deathReason
        );
        if (context.isEmpty()) {
            return;
        }

        component.setDeathMessage(deathReason);
        component.setShowDeathMenu(false);
        player.getPageManager().openCustomPage(ref, store, new DungeonDeathPage(playerRef, context.get()));
    }

    @Nullable
    private static Message resolveDeathReason(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        if (component.getDeathMessage() != null) {
            return component.getDeathMessage();
        }
        Damage deathInfo = component.getDeathInfo();
        return deathInfo != null ? deathInfo.getDeathMessage(ref, commandBuffer) : null;
    }
}