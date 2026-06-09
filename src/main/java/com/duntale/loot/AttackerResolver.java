package com.duntale.loot;

import com.duntale.companion.CompanionComponent;
import com.hypixel.hytale.builtin.deployables.component.DeployableComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Resolves kill-credit attribution from a {@link DeathComponent}.
 *
 * <p>Centralises both the ECS traversal (damage source → attacker ref → components)
 * and the attribution decision (player → companion owner → none), keeping
 * {@link NpcLootSystem} focused on loot dispatch.
 *
 * <p>The pure decision method {@link #resolveAttackerUuid} is package-private
 * and framework-free, making it directly unit-testable without server infrastructure.
 */
final class AttackerResolver {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private AttackerResolver() {}

    /**
     * Resolves the credited player UUID from a {@link DeathComponent}.
     *
     * <p>Credits a direct player attacker by their own UUID, a companion attacker to its owner
     * UUID, or a deployable attacker (e.g. a player-deployed turret) to its owner UUID.
     * Non-player, non-companion, non-deployable attackers yield {@code null}.
     *
     * @param deathComponent the death component of the dying NPC
     * @param store          the entity store used to look up attacker components
     * @return the credited player UUID, or {@code null} if the kill should not be credited
     */
    @Nullable
    static UUID resolve(@Nullable DeathComponent deathComponent,
                        @Nullable Store<EntityStore> store) {
        if (deathComponent == null || store == null) return null;
        try {
            Damage damage = deathComponent.getDeathInfo();
            if (damage == null) return null;

            Damage.Source source = damage.getSource();
            if (!(source instanceof Damage.EntitySource entitySource)) return null;

            Ref<EntityStore> attackerRef = entitySource.getRef();
            if (!attackerRef.isValid()) return null;

            Player player = store.getComponent(attackerRef, Player.getComponentType());
            UUIDComponent uuidComp = store.getComponent(attackerRef, UUIDComponent.getComponentType());
            CompanionComponent companionComp = store.getComponent(attackerRef, CompanionComponent.getComponentType());
            DeployableComponent deployableComp = store.getComponent(attackerRef, DeployableComponent.getComponentType());

            UUID directUuid = uuidComp != null ? uuidComp.getUuid() : null;
            UUID companionOwnerUuid = companionComp != null ? companionComp.getOwnerUuid() : null;
            UUID deployableOwnerUuid = deployableComp != null ? deployableComp.getOwnerUUID() : null;
            return resolveAttackerUuid(player != null, directUuid, companionOwnerUuid, deployableOwnerUuid);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to resolve attacker from DeathComponent: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the credited player UUID from already-resolved attacker state.
     *
     * <p>Priority: direct player entity → companion owner → deployable owner → {@code null}.
     *
     * @param hasPlayerComponent {@code true} if the attacker entity carries a {@code Player} component
     * @param directUuid         the attacker's own UUID, or {@code null} if {@code UUIDComponent} is absent
     * @param companionOwnerUuid the companion owner's UUID, or {@code null} if the attacker is not a companion
     * @param deployableOwnerUuid the deployable owner's UUID, or {@code null} if the attacker is not a deployable
     * @return the credited player UUID, or {@code null} if the kill should not be credited to any player
     */
    @Nullable
    static UUID resolveAttackerUuid(boolean hasPlayerComponent,
                                    @Nullable UUID directUuid,
                                    @Nullable UUID companionOwnerUuid,
                                    @Nullable UUID deployableOwnerUuid) {
        if (hasPlayerComponent) {
            return directUuid;
        }
        if (companionOwnerUuid != null) {
            return companionOwnerUuid;
        }
        if (deployableOwnerUuid != null) {
            return deployableOwnerUuid;
        }
        return null;
    }
}
