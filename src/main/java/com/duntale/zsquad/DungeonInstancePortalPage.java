package com.duntale.zsquad;

import com.duntale.zsquad.dungeon.DungeonInstance;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Portal prompt opened when a player enters the Village dungeon TriggerVolume.
 */
final class DungeonInstancePortalPage extends InteractiveCustomUIPage<DungeonInstancePortalPage.PortalEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String ACTION_ENTER = "enter";
    private static final String ACTION_CONTINUE = "continue";
    private static final String ACTION_NEW_DUNGEON = "new-dungeon";
    private static final String ACTION_CANCEL = "cancel";

    private final PortalMode mode;
    private final DungeonInstance activeInstance;

    DungeonInstancePortalPage(
            @Nonnull PlayerRef playerRef,
            @Nonnull PortalMode mode,
            @Nullable DungeonInstance activeInstance
    ) {
        super(playerRef, CustomPageLifetime.CanDismiss, PortalEventData.CODEC);
        this.mode = Objects.requireNonNull(mode, "mode");
        this.activeInstance = activeInstance;
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store
    ) {
        cmd.append("Pages/Entry/DungeonInstancePortalPage.ui");

        boolean hasInstance = mode == PortalMode.EXISTING_INSTANCE;
        cmd.set("#NoInstanceActions.Visible", !hasInstance);
        cmd.set("#ExistingInstanceActions.Visible", hasInstance);

        if (hasInstance) {
            cmd.set("#PromptLine1.Text", "There is existing dungeon floor progress saved.");
            cmd.set("#PromptLine2.Text", "Continue or start from the bottom (Floor 1). Gear and stats stay intact.");
            cmd.set("#StateText.Text", activeInstance != null
                ? "Current Floor " + activeInstance.floorLevel()
                    : "Current run: unavailable");
        } else {
            cmd.set("#PromptLine1.Text", "You are about to enter a dungeon.");
            cmd.set("#PromptLine2.Text", "Start a fresh floor-1 run from the Village portal?");
            cmd.set("#StateText.Text", "No active dungeon run");
        }

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#EnterButton",
                EventData.of("Action", ACTION_ENTER)
        );
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#ContinueButton",
                EventData.of("Action", ACTION_CONTINUE)
        );
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#NewDungeonButton",
                EventData.of("Action", ACTION_NEW_DUNGEON)
        );
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#CancelButton",
                EventData.of("Action", ACTION_CANCEL)
        );
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PortalEventData data
    ) {
        if (data.action == null) {
            return;
        }

        ZSquadPlugin plugin = ZSquadPlugin.get();
        if (plugin == null) {
            LOGGER.at(Level.WARNING).log("Unable to handle dungeon portal action because ZSquadPlugin is unavailable");
            return;
        }

        switch (data.action) {
            case ACTION_ENTER -> plugin.handlePortalEnter(ref, store, playerRef);
            case ACTION_CONTINUE -> plugin.handlePortalContinue(ref, store, playerRef);
            case ACTION_NEW_DUNGEON -> plugin.handlePortalNewDungeon(ref, store, playerRef);
            case ACTION_CANCEL -> plugin.handlePortalCancel(ref, store);
            default -> LOGGER.at(Level.WARNING).log("Unknown dungeon portal action: %s", data.action);
        }
    }

    enum PortalMode {
        NO_INSTANCE,
        EXISTING_INSTANCE
    }

    static final class PortalEventData {

        static final BuilderCodec<PortalEventData> CODEC = BuilderCodec.builder(
                        PortalEventData.class,
                        PortalEventData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING),
                        (event, value) -> event.action = value,
                        event -> event.action)
            .add()
                .build();

        String action;
    }
}