package com.duntale.zsquad.merchant;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * A {@link ContainerWindow} for merchant sessions that triggers inventory
 * cleanup when the window is closed (dismissed).
 *
 * <p>On close, strips merchant display metadata from all items in the player's
 * inventory and closes the active merchant session.
 *
 * @see MerchantService#cleanupInventoryMetadata(UUID, Ref, ComponentAccessor)
 */
class MerchantWindow extends ContainerWindow {

    private final UUID playerId;
    private final MerchantService merchantService;

    MerchantWindow(@Nonnull ItemContainer itemContainer,
                   @Nonnull UUID playerId,
                   @Nonnull MerchantService merchantService) {
        super(itemContainer);
        this.playerId = playerId;
        this.merchantService = merchantService;
    }

    @Override
    public void onClose0(@Nonnull Ref<EntityStore> ref,
                         @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        merchantService.cleanupInventoryMetadata(playerId, ref, componentAccessor);
    }
}
