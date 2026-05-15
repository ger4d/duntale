package com.duntale.death;

import com.duntale.DuntalePlugin;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Custom dungeon death page with a paid current-floor continue option and a free village retreat.
 */
public final class DungeonDeathPage extends InteractiveCustomUIPage<DungeonDeathPage.DeathEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String ACTION_CURRENT_FLOOR = "current-floor";
    private static final String ACTION_VILLAGE = "village";

    private final DungeonDeathContext context;

    /**
     * Creates a dungeon death page for the given context snapshot.
     *
     * @param playerRef the player who died
     * @param context the dungeon death context snapshot
     */
    public DungeonDeathPage(@Nonnull PlayerRef playerRef, @Nonnull DungeonDeathContext context) {
        super(playerRef, CustomPageLifetime.CantClose, DeathEventData.CODEC);
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store
    ) {
        cmd.append("Pages/Death/DungeonDeathPage.ui");

        int floorLevel = context.instance().floorLevel();
        boolean currentDisabled = context.balance() < context.currentFloorCost();
        cmd.set("#DeathReason.TextSpans", context.deathReason() != null
                ? context.deathReason()
                : Message.raw("You died in the dungeon."));
        cmd.set("#FloorText.Text", "Floor " + floorLevel);
        cmd.set("#BalanceText.Text", formatGold(context.balance()) + " gold");
        cmd.set("#CurrentCostText.Text", formatGold(context.currentFloorCost()) + " gold");
        cmd.set("#CurrentFloorButton.Disabled", currentDisabled);

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#CurrentFloorButton",
                EventData.of("Action", ACTION_CURRENT_FLOOR)
        );
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#VillageButton",
                EventData.of("Action", ACTION_VILLAGE)
        );
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull DeathEventData data
    ) {
        if (data.action == null) {
            return;
        }

        DuntalePlugin plugin = DuntalePlugin.get();
        if (plugin == null) {
            LOGGER.at(Level.WARNING).log("Unable to handle dungeon death action because DuntalePlugin is unavailable");
            return;
        }

        disableActions();
        switch (data.action) {
            case ACTION_CURRENT_FLOOR -> plugin.handleDungeonRespawnCurrent(ref, store, playerRef);
            case ACTION_VILLAGE -> plugin.handleDungeonReturnVillage(ref, store, playerRef);
            default -> LOGGER.at(Level.WARNING).log("Unknown dungeon death action: %s", data.action);
        }
    }

    private void disableActions() {
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set("#CurrentFloorButton.Disabled", true);
        cmd.set("#VillageButton.Disabled", true);
        sendUpdate(cmd, null, false);
    }

    @Nonnull
    private static String formatGold(long amount) {
        return Long.toString(amount);
    }

    /** Event payload sent by dungeon death page action buttons. */
    public static final class DeathEventData {

        static final BuilderCodec<DeathEventData> CODEC = BuilderCodec.builder(
                        DeathEventData.class,
                        DeathEventData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING),
                        (event, value) -> event.action = value,
                        event -> event.action)
            .add()
                .build();

        String action;
    }
}