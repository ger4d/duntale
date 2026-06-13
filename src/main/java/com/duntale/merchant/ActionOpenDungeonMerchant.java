package com.duntale.merchant;

import com.duntale.DuntalePlugin;
import com.duntale.rpg.RpgStat;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.logging.Level;

/**
 * NPC action that opens the dungeon custom merchant UI for the interacting player.
 * Reads the {@link MerchantComponent} from the NPC entity to determine the
 * dungeon floor level, then delegates to {@link MerchantService}.
 *
 * @since 1.3.0
 */
public class ActionOpenDungeonMerchant extends ActionBase {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public ActionOpenDungeonMerchant(@Nonnull BuilderActionBase builder) {
        super(builder);
    }

    @Override
    public boolean canExecute(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport executionSupport,
                              InfoProvider sensorInfo, double dt, @Nonnull Store<EntityStore> store) {
        return super.canExecute(ref, executionSupport, sensorInfo, dt, store)
                && executionSupport.getStateSupport().getInteractionIterationTarget() != null;
    }

    @Override
    public boolean execute(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport executionSupport,
                           InfoProvider sensorInfo, double dt, @Nonnull Store<EntityStore> store) {
        super.execute(ref, executionSupport, sensorInfo, dt, store);

        Ref<EntityStore> playerReference = executionSupport.getStateSupport().getInteractionIterationTarget();
        if (playerReference == null) return false;

        PlayerRef playerRefComp = store.getComponent(playerReference, PlayerRef.getComponentType());
        if (playerRefComp == null) return false;

        Player player = store.getComponent(playerReference, Player.getComponentType());
        if (player == null) return false;

        // Read floor level from the merchant NPC's component
        MerchantComponent merchantComp = store.getComponent(ref, MerchantComponent.getComponentType());
        int floorLevel = merchantComp != null ? merchantComp.getFloorLevel() : 1;

        LOGGER.at(Level.INFO).log("Opening merchant for floorLevel: %d, %s", floorLevel, merchantComp != null ? merchantComp.toString() : "<none>");

        // Generate catalog on first interaction, then reuse
        DuntalePlugin plugin = DuntalePlugin.get();
        MerchantService merchantService = plugin.getMerchantService();

        List<CatalogEntry> catalog;
        if (merchantComp != null && merchantComp.hasCatalog()) {
            // Catalogs are generated once and cached on the merchant; the first opener's
            // Luck locked the rarity rolls for all subsequent openers.
            catalog = merchantComp.getCatalog();
        } else {
            long seed = ref.hashCode();
            if (merchantComp != null && "VILLAGE".equals(merchantComp.getCatalogType())) {
                catalog = plugin.getCatalogGenerator().generateVillageCatalog();
            } else {
                // The opener's Luck promotes the rarity of the gear this catalog rolls.
                int openerLuck = plugin.getRpgService().getEffectiveStat(playerRefComp.getUuid(), RpgStat.LUCK);
                catalog = plugin.getCatalogGenerator().generate(floorLevel, seed, openerLuck);
            }
            if (merchantComp != null) {
                merchantComp.setCatalog(catalog);
            }
        }

        merchantService.openMerchant(player, playerRefComp, playerReference, store, catalog);

        return true;
    }
}
