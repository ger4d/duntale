package com.duntale.zsquad.companion;

import com.duntale.zsquad.companion.CompanionService.ActiveCompanion;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Player command for managing NPC companions.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code /companion summon <roleName>} — Summon a companion NPC</li>
 *   <li>{@code /companion dismiss}           — Dismiss your active companion</li>
 * </ul>
 *
 * @since 1.4.0
 */
public class CompanionCommand extends CommandBase {

    private static final String GREEN = "#55FF55";
    private static final String RED = "#FF5555";
    private static final String YELLOW = "#FFEE55";
    private static final String GRAY = "#AAAAAA";
    private static final String CYAN = "#55FFFF";

    /**
     * Creates the /companion command.
     *
     * @param companionService the companion service
     */
    public CompanionCommand(@Nonnull CompanionService companionService) {
        super("companion", "Manage your NPC companion");
        this.addSubCommand(new SummonSubCommand(companionService));
        this.addSubCommand(new DismissSubCommand(companionService));
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage:").color(YELLOW));
        context.sendMessage(Message.raw("  /companion summon <roleName>").color(GRAY));
        context.sendMessage(Message.raw("  /companion dismiss").color(GRAY));
    }

    // -- Summon subcommand ---------------------------------------------

    private static class SummonSubCommand extends AbstractPlayerCommand {

        private final CompanionService companionService;
        private final RequiredArg<String> roleArg =
                this.withRequiredArg("roleName", "NPC role name to summon", ArgTypes.STRING);

        SummonSubCommand(@Nonnull CompanionService companionService) {
            super("summon", "Summon a companion NPC");
            this.companionService = companionService;
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            UUID playerId = playerRef.getUuid();
            String roleName = roleArg.get(context);

            if (companionService.hasCompanion(playerId)) {
                context.sendMessage(Message.raw("You already have a companion. Dismiss it first.").color(RED));
                return;
            }

            ActiveCompanion companion = companionService.summon(store, ref, playerId, roleName);
            if (companion == null) {
                context.sendMessage(Message.raw("Failed to summon companion. Check the role name.").color(RED));
                return;
            }

            // Persist preference only on explicit player choice
            companionService.persistPreference(playerId, roleName);

            context.sendMessage(
                    Message.raw("Summoned companion ").color(GREEN)
                            .insert(Message.raw(roleName).color(CYAN))
            );
        }
    }

    // -- Dismiss subcommand --------------------------------------------

    private static class DismissSubCommand extends AbstractPlayerCommand {

        private final CompanionService companionService;

        DismissSubCommand(@Nonnull CompanionService companionService) {
            super("dismiss", "Dismiss your active companion");
            this.companionService = companionService;
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            UUID playerId = playerRef.getUuid();

            if (!companionService.hasCompanion(playerId)) {
                context.sendMessage(Message.raw("You don't have a companion.").color(RED));
                return;
            }

            boolean dismissed = companionService.dismiss(store, ref, playerId);
            if (dismissed) {
                context.sendMessage(Message.raw("Companion dismissed.").color(GREEN));
            } else {
                context.sendMessage(Message.raw("Failed to dismiss companion.").color(RED));
            }
        }
    }
}
