package com.duntale.merchant;

import com.duntale.DuntalePlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * NPC action that opens the dungeon custom merchant UI for the interacting player.
 * Reads the {@link MerchantComponent} from the NPC entity to determine the
 * dungeon floor level, then delegates to {@link MerchantService}.
 *
 * @since 1.3.0
 */
public class ActionOpenDungeonMerchant extends ActionBase {

    public ActionOpenDungeonMerchant(@Nonnull BuilderActionBase builder) {
        super(builder);
    }

    @Override
    public boolean canExecute(@Nonnull Ref<EntityStore> ref, @Nonnull Role role,
                              InfoProvider sensorInfo, double dt, @Nonnull Store<EntityStore> store) {
        return super.canExecute(ref, role, sensorInfo, dt, store)
                && role.getStateSupport().getInteractionIterationTarget() != null;
    }

    @Override
    public boolean execute(@Nonnull Ref<EntityStore> ref, @Nonnull Role role,
                           InfoProvider sensorInfo, double dt, @Nonnull Store<EntityStore> store) {
        super.execute(ref, role, sensorInfo, dt, store);

        Ref<EntityStore> playerReference = role.getStateSupport().getInteractionIterationTarget();
        if (playerReference == null) return false;

        PlayerRef playerRefComp = store.getComponent(playerReference, PlayerRef.getComponentType());
        if (playerRefComp == null) return false;

        Player player = store.getComponent(playerReference, Player.getComponentType());
        if (player == null) return false;

        // Read floor level from the merchant NPC's component
        MerchantComponent merchantComp = store.getComponent(ref, MerchantComponent.getComponentType());
        int floorLevel = merchantComp != null ? merchantComp.getFloorLevel() : 1;

        // Generate catalog on first interaction, then reuse
        DuntalePlugin plugin = DuntalePlugin.get();
        MerchantService merchantService = plugin.getMerchantService();

        List<CatalogEntry> catalog;
        if (merchantComp != null && merchantComp.hasCatalog()) {
            catalog = merchantComp.getCatalog();
        } else {
            long seed = ref.hashCode();
            catalog = plugin.getCatalogGenerator().generate(floorLevel, seed);
            if (merchantComp != null) {
                merchantComp.setCatalog(catalog);
            }
        }

        merchantService.openMerchant(player, playerRefComp, playerReference, store, catalog);

        return true;
    }
}
