package com.duntale;

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
import java.util.logging.Level;

final class CustomizeCharacterPage extends InteractiveCustomUIPage<CustomizeCharacterPage.CustomizeEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String ACTION_CONFIRM = "confirm";
    private static final String ACTION_UPDATE_PREVIEW_NAME = "update-preview-name";
    private static final String LOCKED_ROLE_NAME = "Companion_Wolf_Black";

    CustomizeCharacterPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CantClose, CustomizeEventData.CODEC);
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store
    ) {
        cmd.append("Pages/Entry/CustomizeCharacterPage.ui");
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#ConfirmButton",
                EventData.of("Action", ACTION_CONFIRM)
                        .append("@CompanionName", "#CompanionName.Value")
                        .append("RoleName", LOCKED_ROLE_NAME)
        );
        events.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#CompanionName",
            EventData.of("Action", ACTION_UPDATE_PREVIEW_NAME)
                .append("@CompanionName", "#CompanionName.Value")
                .append("RoleName", LOCKED_ROLE_NAME),
            false
        );
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull CustomizeEventData data
    ) {
        DuntalePlugin plugin = DuntalePlugin.get();
        if (plugin == null) {
            LOGGER.at(Level.WARNING).log("Unable to handle character setup because DuntalePlugin is unavailable");
            if (ACTION_CONFIRM.equals(data.action)) {
                sendUpdate(null, null, false);
            }
            return;
        }

        if (!ACTION_CONFIRM.equals(data.action)) {
            if (ACTION_UPDATE_PREVIEW_NAME.equals(data.action)) {
                plugin.handleCustomizeCharacterPreviewName(playerRef, store, data.companionName);
            }
            return;
        }

        if (!plugin.handleCustomizeCharacterConfirm(ref, store, playerRef, data.roleName, data.companionName)) {
            sendUpdate(null, null, false);
        }
    }

    static final class CustomizeEventData {

        static final BuilderCodec<CustomizeEventData> CODEC = BuilderCodec.builder(
                        CustomizeEventData.class,
                        CustomizeEventData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING),
                        (event, value) -> event.action = value,
                        event -> event.action)
            .add()
            .append(new KeyedCodec<>("@CompanionName", Codec.STRING),
                        (event, value) -> event.companionName = value,
                        event -> event.companionName)
            .add()
            .append(new KeyedCodec<>("RoleName", Codec.STRING),
                        (event, value) -> event.roleName = value,
                        event -> event.roleName)
            .add()
                .build();

        String action;
        String companionName;
        String roleName;
    }
}