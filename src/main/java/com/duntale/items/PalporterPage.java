package com.duntale.items;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Custom UI page for the Palporter consumable.
 *
 * <p>Lists other players in the user's dungeon-instance world and allows teleporting to them.
 */
public class PalporterPage extends InteractiveCustomUIPage<PalporterPage.PalporterEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String COLOR_GREEN = "#55FF55";
    private static final String COLOR_RED = "#FF5555";

    static final int MAX_TARGET_ROWS = 8;

    private UUID selectedTargetId;

    public PalporterPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, PalporterEventData.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        cmd.append("Pages/Palporter/PalporterPage.ui");
        applyState(cmd, events, ref, store);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull PalporterEventData data) {
        if (data.select != null) {
            try {
                selectedTargetId = UUID.fromString(data.select);
            } catch (IllegalArgumentException e) {
                LOGGER.atWarning().log("Invalid UUID selected in PalporterPage: %s", data.select);
            }
            refreshDisplay(ref, store);
        } else if (data.teleport != null) {
            doTeleport(ref, store);
        }
    }

    private void refreshDisplay(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        applyState(cmd, events, ref, store);
        sendUpdate(cmd, events, false);
    }

    private void applyState(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }

        List<PlayerRef> targets = new ArrayList<>();
        for (PlayerRef player : world.getPlayerRefs()) {
            if (!player.getUuid().equals(playerRef.getUuid())) {
                targets.add(player);
            }
        }

        // Show targets up to MAX_TARGET_ROWS
        for (int i = 0; i < MAX_TARGET_ROWS; i++) {
            String row = "#TargetRow" + i;

            if (i < targets.size()) {
                PlayerRef target = targets.get(i);
                boolean isSelected = target.getUuid().equals(selectedTargetId);

                cmd.set(row + ".Visible", true);
                if (isSelected) {
                    cmd.set(row + ".Text", target.getUsername() + "  [Selected]");
                } else {
                    cmd.set(row + ".Text", target.getUsername());
                }

                events.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        row,
                        EventData.of("Select", target.getUuid().toString()),
                        false);
            } else {
                cmd.set(row + ".Visible", false);
            }
        }

        // Validate selection is still in roster
        boolean selectionValid = false;
        if (selectedTargetId != null) {
            for (PlayerRef target : targets) {
                if (target.getUuid().equals(selectedTargetId)) {
                    selectionValid = true;
                    break;
                }
            }
        }

        cmd.set("#TeleportBtn.Visible", selectionValid);
        if (selectionValid) {
            PlayerRef targetPlayer = Universe.get().getPlayer(selectedTargetId);
            String targetName = targetPlayer != null ? targetPlayer.getUsername() : selectedTargetId.toString();
            cmd.set("#SelectedLabel.Text", "Selected: " + targetName);
        } else {
            cmd.set("#SelectedLabel.Text", "Pick a player");
        }

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TeleportBtn",
                EventData.of("Teleport", "1"),
                false);
    }

    private void doTeleport(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (selectedTargetId == null) {
            return;
        }

        PlayerRef targetPlayerRef = Universe.get().getPlayer(selectedTargetId);
        if (targetPlayerRef == null || !targetPlayerRef.getWorldUuid().equals(playerRef.getWorldUuid())) {
            playerRef.sendMessage(Message.raw("That player is no longer here.").color(COLOR_RED));
            selectedTargetId = null;
            refreshDisplay(ref, store);
            return;
        }

        Ref<EntityStore> targetEntityRef = targetPlayerRef.getReference();
        TransformComponent targetTransform = store.getComponent(targetEntityRef, TransformComponent.getComponentType());
        if (targetTransform == null) {
            playerRef.sendMessage(Message.raw("Unable to teleport to that player.").color(COLOR_RED));
            return;
        }

        // Consume one Palporter
        if (!InventoryQuery.removeOne(store, ref, CustomItems.PALPORTER)) {
            playerRef.sendMessage(Message.raw("You do not have a Palporter to use.").color(COLOR_RED));
            return;
        }

        // Apply Teleport component
        World currentWorld = store.getExternalData().getWorld();
        store.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(currentWorld, targetTransform.getTransform()));

        playerRef.sendMessage(Message.raw("Teleported to " + targetPlayerRef.getUsername() + ".").color(COLOR_GREEN));

        // Dismiss page
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().setPage(ref, store, Page.None);
        }
    }

    /**
     * Event data received from Palporter UI interactions.
     */
    public static class PalporterEventData {

        /** Codec for deserialising Palporter UI click events. */
        public static final BuilderCodec<PalporterEventData> CODEC = BuilderCodec.builder(
                        PalporterEventData.class, PalporterEventData::new)
                .append(new KeyedCodec<>("Select", Codec.STRING),
                        (e, v) -> e.select = v, e -> e.select).add()
                .append(new KeyedCodec<>("Teleport", Codec.STRING),
                        (e, v) -> e.teleport = v, e -> e.teleport).add()
                .build();

        String select;
        String teleport;
    }
}
