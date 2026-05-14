package com.duntale.rpg;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Player command that opens the stat point assignment UI.
 *
 * <p>Usage: {@code /assignstats}
 *
 * <p>Opens the {@link StatAssignmentPage} where players can spend unassigned points
 * to increase individual RPG stats.
 *
 * @see StatAssignmentPage
 * @see RpgService#assignPoint(java.util.UUID, RpgStat)
 */
public class StatAssignCommand extends AbstractPlayerCommand {

    private static final String COLOR_RED = "#FF5555";
    private static final String COLOR_GRAY = "#AAAAAA";

    private final RpgService rpgService;

    /**
     * Creates a new /assignstats command.
     *
     * @param rpgService the RPG service for stat operations
     */
    public StatAssignCommand(@Nonnull RpgService rpgService) {
        super("assignstats", "Open stat point assignment UI");
        this.rpgService = rpgService;
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            context.sendMessage(Message.raw("Could not resolve player.").color(COLOR_RED));
            return;
        }

        int unassigned = rpgService.getUnassignedPoints(playerRef.getUuid());
        if (unassigned <= 0) {
            context.sendMessage(Message.raw("You have no stat points to assign.").color(COLOR_GRAY));
            return;
        }

        StatAssignmentPage page = new StatAssignmentPage(playerRef, rpgService);
        player.getPageManager().openCustomPage(ref, store, page);
    }
}
