package com.duntale.zsquad;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * Join-time destination page for Continue / Village routing.
 */
final class DungeonEntryPage extends InteractiveCustomUIPage<DungeonEntryPage.EntryEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String ACTION_CONTINUE = "continue";
    private static final String ACTION_VILLAGE = "village";

    DungeonEntryPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CantClose, EntryEventData.CODEC);
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store
    ) {
        cmd.append("Pages/Entry/DungeonEntryPage.ui");
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#ContinueButton",
                EventData.of("Action", ACTION_CONTINUE)
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
            @Nonnull EntryEventData data
    ) {
        if (data.action == null) {
            return;
        }

        ZSquadPlugin plugin = ZSquadPlugin.get();
        if (plugin == null) {
            LOGGER.at(Level.WARNING).log("Unable to route dungeon entry action because ZSquadPlugin is unavailable");
            return;
        }

        switch (data.action) {
            case ACTION_CONTINUE -> plugin.handleEntryContinue(ref, store, playerRef);
            case ACTION_VILLAGE -> plugin.handleEntryVillage(ref, store, playerRef);
            default -> LOGGER.at(Level.WARNING).log("Unknown dungeon entry action: %s", data.action);
        }
    }

    static final class EntryEventData {

        static final BuilderCodec<EntryEventData> CODEC = BuilderCodec.builder(
                        EntryEventData.class, EntryEventData::new)
                .addField(new KeyedCodec<>("Action", Codec.STRING),
                        (event, value) -> event.action = value,
                        event -> event.action)
                .build();

        String action;
    }
}
