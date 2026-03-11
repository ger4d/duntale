package com.duntale.zsquad.companion;

import com.duntale.zsquad.ZSquadPlugin;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * ECS component attached to companion NPC entities.
 * Stores ownership, role, and level information for identification and filtering.
 *
 * @since 1.4.0
 */
public class CompanionComponent implements Component<EntityStore> {

    /** Codec for serialization during chunk save/load. */
    @Nonnull
    public static final BuilderCodec<CompanionComponent> CODEC = BuilderCodec.builder(
                    CompanionComponent.class, CompanionComponent::new)
            .append(new KeyedCodec<>("OwnerUUID", Codec.UUID_BINARY),
                    (c, v) -> c.ownerUuid = v, c -> c.ownerUuid)
            .add()
            .append(new KeyedCodec<>("RoleName", Codec.STRING),
                    (c, v) -> c.roleName = v, c -> c.roleName)
            .add()
            .append(new KeyedCodec<>("Level", Codec.INTEGER),
                    (c, v) -> c.level = v, c -> c.level)
            .add()
            .build();

    private UUID ownerUuid;
    private String roleName;
    private int level;

    /**
     * No-arg constructor required by ECS component registration.
     */
    public CompanionComponent() {
        this.ownerUuid = null;
        this.roleName = "";
        this.level = 1;
    }

    /**
     * Creates a companion component with the specified owner, role, and level.
     *
     * @param ownerUuid the UUID of the player who owns this companion
     * @param roleName  the NPC role name of this companion
     * @param level     the level this companion was spawned at
     */
    public CompanionComponent(@Nonnull UUID ownerUuid, @Nonnull String roleName, int level) {
        this.ownerUuid = ownerUuid;
        this.roleName = roleName;
        this.level = level;
    }

    /**
     * Returns the registered component type for {@link CompanionComponent}.
     *
     * @return the component type
     */
    @Nonnull
    public static ComponentType<EntityStore, CompanionComponent> getComponentType() {
        return ZSquadPlugin.get().getCompanionComponentType();
    }

    /**
     * @return the UUID of the player who owns this companion
     */
    @Nonnull
    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    /**
     * @return the NPC role name of this companion
     */
    @Nonnull
    public String getRoleName() {
        return roleName;
    }

    /**
     * @return the level this companion was spawned at
     */
    public int getLevel() {
        return level;
    }

    @Nullable
    @Override
    public Component<EntityStore> clone() {
        return new CompanionComponent(ownerUuid, roleName, level);
    }
}
