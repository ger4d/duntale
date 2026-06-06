package com.duntale.rpg;

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
import java.util.UUID;
import java.util.logging.Level;

/**
 * Interactive UI page for spending unassigned stat points.
 *
 * <p>Displays the player's current stats, unassigned point count, and a "+" button
 * per stat. Clicking a button sends an event with the stat name, which triggers
 * {@link RpgService#assignPoint(UUID, RpgStat)} and refreshes the display.
 *
 * <p>Registered via command or NPC interaction. Dismissible by the player.
 *
 * @see RpgService#assignPoint(UUID, RpgStat)
 */
public class StatAssignmentPage extends InteractiveCustomUIPage<StatAssignmentPage.StatAssignmentData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String COLOR_GREEN = "#55FF55";
    private static final String COLOR_RED = "#FF5555";
    private static final String COLOR_GRAY = "#AAAAAA";
    private static final String COLOR_CYAN = "#55FFFF";

    private final RpgService rpgService;

    /**
     * Creates a new stat assignment page.
     *
     * @param playerRef  the player opening the page
     * @param rpgService the RPG service for stat operations
     */
    public StatAssignmentPage(@Nonnull PlayerRef playerRef, @Nonnull RpgService rpgService) {
        super(playerRef, CustomPageLifetime.CanDismiss, StatAssignmentData.CODEC);
        this.rpgService = rpgService;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        cmd.append("Pages/StatAssignment/StatAssignmentPage.ui");

        UUID playerId = playerRef.getUuid();
        RpgProfile profile = rpgService.getProfile(playerId);
        int unassigned = rpgService.getUnassignedPoints(playerId);

        // Populate stat values
        for (RpgStat stat : RpgStat.values()) {
            String abbr = statAbbreviation(stat);
            cmd.set("#" + abbr + "Value.Text", String.valueOf(profile.getStat(stat)));
        }

        // Unassigned points display
        cmd.set("#UnassignedValue.Text", String.valueOf(unassigned));

        // Bind "+" buttons per stat — each sends the stat name as the action
        for (RpgStat stat : RpgStat.values()) {
            String abbr = statAbbreviation(stat);
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#" + abbr + "AddBtn",
                    EventData.of("AssignStat", stat.name()),
                    false // don't lock interface — allow rapid clicks
            );
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull StatAssignmentData data) {
        if (data.assignStat == null) {
            return;
        }

        UUID playerId = playerRef.getUuid();

        RpgStat stat;
        try {
            stat = RpgStat.valueOf(data.assignStat);
        } catch (IllegalArgumentException e) {
            LOGGER.at(Level.WARNING).log("Invalid stat name from UI: %s", data.assignStat);
            return;
        }

        boolean success = rpgService.assignPoint(playerId, stat);
        if (success) {
            playerRef.sendMessage(
                    Message.raw("+1 ").color(COLOR_GREEN)
                            .insert(Message.raw(stat.name()).color(COLOR_CYAN))
                            .insert(Message.raw(" (" + rpgService.getUnassignedPoints(playerId) + " remaining)").color(COLOR_GRAY))
            );
            refreshDisplay();
        } else {
            int unassigned = rpgService.getUnassignedPoints(playerId);
            if (unassigned <= 0) {
                playerRef.sendMessage(Message.raw("No stat points available.").color(COLOR_RED));
            } else {
                playerRef.sendMessage(
                        Message.raw(stat.name() + " is already at max (" + RpgConfig.values().maxStat() + ").").color(COLOR_RED)
                );
            }
        }
    }

    /**
     * Refreshes the stat display with current values.
     */
    private void refreshDisplay() {
        UUID playerId = playerRef.getUuid();
        RpgProfile profile = rpgService.getProfile(playerId);
        int unassigned = rpgService.getUnassignedPoints(playerId);

        UICommandBuilder cmd = new UICommandBuilder();
        for (RpgStat stat : RpgStat.values()) {
            String abbr = statAbbreviation(stat);
            cmd.set("#" + abbr + "Value.Text", String.valueOf(profile.getStat(stat)));
        }
        cmd.set("#UnassignedValue.Text", String.valueOf(unassigned));

        sendUpdate(cmd, null, false);
    }

    /**
     * Returns the abbreviated name used in UI element IDs for the given stat.
     *
     * @param stat the RPG stat
     * @return the abbreviated name (e.g. "Str" for STRENGTH)
     */
    @Nonnull
    static String statAbbreviation(@Nonnull RpgStat stat) {
        return switch (stat) {
            case STRENGTH -> "Str";
            case SPEED -> "Spd";
            case AGILITY -> "Agi";
            case RESISTANCE -> "Res";
            case LUCK -> "Lck";
            case VITALITY -> "Vit";
            case STAMINA -> "Sta";
        };
    }

    // ── Event Data ───────────────────────────────────────────────────

    /**
     * Event data received when a stat assignment button is clicked.
     * The {@code AssignStat} field contains the {@link RpgStat} name to assign.
     */
    public static class StatAssignmentData {

        /** Codec for deserialising button click events. */
        public static final BuilderCodec<StatAssignmentData> CODEC = BuilderCodec.builder(
                        StatAssignmentData.class, StatAssignmentData::new)
            .append(new KeyedCodec<>("AssignStat", Codec.STRING),
                        (e, v) -> e.assignStat = v, e -> e.assignStat)
            .add()
                .build();

        String assignStat;
    }
}
