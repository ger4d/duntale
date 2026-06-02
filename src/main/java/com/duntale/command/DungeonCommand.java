package com.duntale.command;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeon.DungeonInstance;
import com.duntale.dungeon.DungeonInstanceService;
import com.duntale.dungeon.DungeonInstanceState;
import com.duntale.dungeon.FloorConfigAssetRepository;
import com.duntale.dungeon.FloorConfigService;
import com.duntale.progression.CombatScaling;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Admin command for inspecting and managing dungeon instances.
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * /dungeon list
 * /dungeon info <instanceId>
 * /dungeon end <instanceId>
 * /dungeon player <uuid>
 * /dungeon start
 * /dungeon leave
 * /dungeon tpout
 * /dungeon transition <instanceId>
 * /dungeon floorconfig [floor]
 * /dungeon floorconfig list
 * /dungeon floorconfig packs
 * }</pre>
 *
 * @since 1.6.0
 */
public class DungeonCommand extends CommandBase {

    private static final String GOLD = "#FFD700";
    private static final String WHITE = "#FFFFFF";
    private static final String GRAY = "#AAAAAA";
    private static final String YELLOW = "#FFEE55";
    private static final String GREEN = "#55FF55";
    private static final String RED = "#FF5555";
    private static final String AQUA = "#55FFFF";
    private static final int EXIT_APPROACH_OFFSET_BLOCKS = 4;

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final DungeonInstanceService dungeonInstanceService;
    private final FloorConfigService floorConfigService;
    private final SharedWorldRouter sharedWorldRouter;
    private final FloorTransitionParticipantPreparer floorTransitionParticipantPreparer;
    private final FloorTransitionRecovery floorTransitionRecovery;

    /** Routes a player back to the shared hub world with a status message. */
    @FunctionalInterface
    public interface SharedWorldRouter {

        /**
         * Routes the given player to the shared world.
         *
         * @param playerRef the player to route
         * @param statusMessage the message to send during routing
         */
        void route(@Nonnull PlayerRef playerRef, @Nonnull Message statusMessage);
    }

    /** Selects and camera-prepares runtime floor-transition participants. */
    @FunctionalInterface
    public interface FloorTransitionParticipantPreparer {

        /**
         * Selects online candidates in the source world and prepares them for a world transition.
         *
         * @param candidateIds     active members eligible for admin transition selection
         * @param sourceWorldName  the world the players must currently occupy
         * @return a future completing with the prepared transfer player UUIDs
         */
        @Nonnull
        CompletableFuture<Set<UUID>> prepare(
                @Nonnull Set<UUID> candidateIds,
                @Nonnull String sourceWorldName
        );
    }

    /** Re-enables dungeon controls for prepared players after a failed pre-transfer transition. */
    @FunctionalInterface
    public interface FloorTransitionRecovery {

        /**
         * Re-enables controls for prepared players who are still in the old world.
         *
         * @param preparedPlayerIds the players that received transition camera preparation
         * @param sourceWorldName   the old instance world name
         * @param instance          the instance metadata for portal restoration
         * @return a future completing after recovery attempts have been queued
         */
        @Nonnull
        CompletableFuture<Void> reEnable(
                @Nonnull Set<UUID> preparedPlayerIds,
                @Nonnull String sourceWorldName,
                @Nonnull DungeonInstance instance
        );
    }

    /**
     * Creates the /dungeon admin command.
     *
     * @param dungeonInstanceService the dungeon instance service
     * @param floorConfigService     the floor config service for per-floor overrides
     * @param sharedWorldRouter      callback for routing players back to the shared world
         * @param floorTransitionParticipantPreparer callback for runtime transition participant prep
         * @param floorTransitionRecovery callback for restoring controls after failed transitions
     */
    public DungeonCommand(
            @Nonnull DungeonInstanceService dungeonInstanceService,
            @Nonnull FloorConfigService floorConfigService,
            @Nonnull SharedWorldRouter sharedWorldRouter,
            @Nonnull FloorTransitionParticipantPreparer floorTransitionParticipantPreparer,
            @Nonnull FloorTransitionRecovery floorTransitionRecovery
    ) {
        super("dungeon", "Manage dungeon instances");
        this.dungeonInstanceService = Objects.requireNonNull(dungeonInstanceService, "dungeonInstanceService");
        this.floorConfigService = Objects.requireNonNull(floorConfigService, "floorConfigService");
        this.sharedWorldRouter = Objects.requireNonNull(sharedWorldRouter, "sharedWorldRouter");
        this.floorTransitionParticipantPreparer = Objects.requireNonNull(
            floorTransitionParticipantPreparer,
            "floorTransitionParticipantPreparer");
        this.floorTransitionRecovery = Objects.requireNonNull(floorTransitionRecovery, "floorTransitionRecovery");

        this.addSubCommand(new ListSubCommand());
        this.addSubCommand(new InfoSubCommand());
        this.addSubCommand(new EndSubCommand());
        this.addSubCommand(new PlayerSubCommand());
        this.addSubCommand(new StartSubCommand());
        this.addSubCommand(new LeaveSubCommand());
        this.addSubCommand(new TpOutSubCommand());
        this.addSubCommand(new TransitionSubCommand());
        this.addSubCommand(new FloorConfigSubCommand());
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        context.sendMessage(
            Message.raw("Usage: /dungeon list|info|end|player|start|leave|tpout|transition|floorconfig").color(YELLOW)
        );
        context.sendMessage(
                Message.raw("  list").color(GOLD)
                        .insert(Message.raw(" — list all active dungeon instances").color(GRAY))
        );
        context.sendMessage(
                Message.raw("  info <instanceId>").color(GOLD)
                        .insert(Message.raw(" — show instance details").color(GRAY))
        );
        context.sendMessage(
                Message.raw("  end <instanceId>").color(GOLD)
                        .insert(Message.raw(" — force-end an instance or retry ENDED cleanup").color(GRAY))
        );
        context.sendMessage(
                Message.raw("  player <uuid>").color(GOLD)
                        .insert(Message.raw(" — look up a player's active instance").color(GRAY))
        );
        context.sendMessage(
            Message.raw("  start").color(GOLD)
                .insert(Message.raw(" — start a dungeon instance (uses party or solo; theme comes from floor config)").color(GRAY))
        );
        context.sendMessage(
            Message.raw("  leave").color(GOLD)
                .insert(Message.raw(" — leave your active dungeon and return to the village").color(GRAY))
        );
        context.sendMessage(
            Message.raw("  tpout").color(GOLD)
                .insert(Message.raw(" — teleport near your active dungeon floor exit").color(GRAY))
        );
        context.sendMessage(
                Message.raw("  transition <instanceId>").color(GOLD)
                        .insert(Message.raw(" — advance instance to next floor").color(GRAY))
        );
        context.sendMessage(
            Message.raw("  floorconfig [floor]").color(GOLD)
                .insert(Message.raw(" — open floor config UI; pack selection happens in-page").color(GRAY))
        );
        context.sendMessage(
                Message.raw("  floorconfig list").color(GOLD)
                .insert(Message.raw(" — list active floor config asset breakpoints").color(GRAY))
        );
        context.sendMessage(
            Message.raw("  floorconfig packs").color(GOLD)
                .insert(Message.raw(" — list asset packs that can store floor config overrides").color(GRAY))
        );
    }

    // ============================================
    // list
    // ============================================

    private class ListSubCommand extends CommandBase {

        ListSubCommand() {
            super("list", "List all active dungeon instances");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            List<DungeonInstance> instances;
            try {
                instances = dungeonInstanceService.listNonEndedInstances();
            } catch (SQLException e) {
                context.sendMessage(Message.raw("Failed to query instances: " + e.getMessage()).color(RED));
                return;
            }

            if (instances.isEmpty()) {
                context.sendMessage(Message.raw("No active dungeon instances.").color(GRAY));
                return;
            }

            context.sendMessage(
                    Message.raw("Active Dungeon Instances (" + instances.size() + "):").color(GOLD).bold(true)
            );

            for (DungeonInstance instance : instances) {
                context.sendMessage(
                        Message.raw("  " + truncateId(instance.instanceId())).color(AQUA).monospace(true)
                                .insert(Message.raw(" [" + instance.state() + "]").color(stateColor(instance.state())))
                                .insert(Message.raw(" floor=" + instance.floorLevel()
                                        + " theme=" + instance.theme()
                                        + " world=" + instance.worldName()).color(GRAY))
                );
            }
        }
    }

    // ============================================
    // info
    // ============================================

    private class InfoSubCommand extends CommandBase {

        private final RequiredArg<String> instanceIdArg =
                this.withRequiredArg("instanceId", "Instance ID (full or prefix)", ArgTypes.STRING);

        InfoSubCommand() {
            super("info", "Show dungeon instance details");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String query = instanceIdArg.get(context);

            DungeonInstance instance;
            try {
                instance = resolveInstance(query);
            } catch (SQLException e) {
                context.sendMessage(Message.raw("Failed to query instance: " + e.getMessage()).color(RED));
                return;
            }

            if (instance == null) {
                context.sendMessage(Message.raw("Instance not found: " + query).color(RED));
                return;
            }

            Set<UUID> roster = Set.of();
            String rosterLookupError = null;
            try {
                roster = dungeonInstanceService.getRoster(instance.instanceId());
            } catch (SQLException e) {
                rosterLookupError = e.getMessage();
            }

            context.sendMessage(Message.raw("Dungeon Instance Details").color(GOLD).bold(true));
            sendField(context, "ID", instance.instanceId());
            sendField(context, "State", instance.state().name(), stateColor(instance.state()));
            sendField(context, "World", instance.worldName());
            sendField(context, "Floor", String.valueOf(instance.floorLevel()));
            sendField(context, "Floor Y", String.valueOf(instance.floorY()));
            sendField(context, "Theme", instance.theme());
            sendField(context, "Seed", instance.seed() != null ? instance.seed() : "(random)");
            sendField(context, "Entrance", formatPosition(instance.entrancePosition()));
            sendField(context, "Exit", formatPosition(instance.exitPosition()));
            sendField(context, "Created", TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(instance.createdAt())));
            if (rosterLookupError != null) {
                sendField(context, "Roster", "(lookup failed)", YELLOW);
                sendField(context, "Roster Error", rosterLookupError, YELLOW);
                return;
            }
            sendField(context, "Roster", roster.isEmpty() ? "(none)" : roster.size() + " player(s)");
            for (UUID playerId : roster) {
                context.sendMessage(
                        Message.raw("    " + playerId).color(GRAY).monospace(true)
                );
            }
        }
    }

    // ============================================
    // end
    // ============================================

    private class EndSubCommand extends CommandBase {

        private final RequiredArg<String> instanceIdArg =
                this.withRequiredArg("instanceId", "Instance ID (full or prefix)", ArgTypes.STRING);

        EndSubCommand() {
            super("end", "Force-end a dungeon instance");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String query = instanceIdArg.get(context);

            DungeonInstance instance;
            try {
                instance = resolveInstance(query);
            } catch (SQLException e) {
                context.sendMessage(Message.raw("Failed to query instance: " + e.getMessage()).color(RED));
                return;
            }

            if (instance == null) {
                context.sendMessage(Message.raw("Instance not found: " + query).color(RED));
                return;
            }

            if (instance.state() == DungeonInstanceState.ENDED) {
                retryEndedCleanup(context, instance.instanceId());
                return;
            }

            String instanceId = instance.instanceId();
            DungeonInstanceState previousState = instance.state();

            try {
                dungeonInstanceService.forceEndInstance(instanceId).join();
            } catch (CompletionException e) {
                context.sendMessage(
                        Message.raw("Force-end claimed the instance but cleanup had errors: "
                                + describeFailure(e)).color(YELLOW)
                );
                return;
            } catch (SQLException | IllegalArgumentException | IllegalStateException e) {
                context.sendMessage(
                        Message.raw("Force-end failed: " + describeFailure(e)).color(RED)
                );
                return;
            }

            context.sendMessage(
                    Message.raw("Instance ").color(GREEN)
                            .insert(Message.raw(truncateId(instanceId)).color(AQUA).monospace(true))
                            .insert(Message.raw(" force-ended (was " + previousState + ").").color(GREEN))
            );
        }
    }

    // ============================================
    // player
    // ============================================

    private class PlayerSubCommand extends CommandBase {

        private final RequiredArg<String> uuidArg =
                this.withRequiredArg("uuid", "Player UUID", ArgTypes.STRING);

        PlayerSubCommand() {
            super("player", "Look up a player's active dungeon instance");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String uuidString = uuidArg.get(context);

            UUID playerId;
            try {
                playerId = UUID.fromString(uuidString);
            } catch (IllegalArgumentException e) {
                context.sendMessage(Message.raw("Invalid UUID: " + uuidString).color(RED));
                return;
            }

            DungeonInstance instance;
            try {
                instance = dungeonInstanceService.getActiveInstance(playerId);
            } catch (SQLException e) {
                context.sendMessage(Message.raw("Failed to query: " + e.getMessage()).color(RED));
                return;
            }

            if (instance == null) {
                context.sendMessage(
                        Message.raw("Player ").color(GRAY)
                                .insert(Message.raw(uuidString).color(WHITE).monospace(true))
                                .insert(Message.raw(" has no active dungeon instance.").color(GRAY))
                );
                return;
            }

            context.sendMessage(
                    Message.raw("Player ").color(GRAY)
                            .insert(Message.raw(uuidString).color(WHITE).monospace(true))
                            .insert(Message.raw(" is in instance:").color(GRAY))
            );
            context.sendMessage(
                    Message.raw("  " + truncateId(instance.instanceId())).color(AQUA).monospace(true)
                            .insert(Message.raw(" [" + instance.state() + "]").color(stateColor(instance.state())))
                            .insert(Message.raw(" floor=" + instance.floorLevel()
                                    + " world=" + instance.worldName()).color(GRAY))
            );
        }
    }

    // ============================================
    // start
    // ============================================

    private class StartSubCommand extends AbstractPlayerCommand {

        StartSubCommand() {
            super("start", "Start a dungeon instance");
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

                dungeonInstanceService.createInstanceForPlayer(playerId, 1)
                    .thenAccept(instance -> context.sendMessage(
                            Message.raw("Dungeon instance created: ").color(GREEN)
                                    .insert(Message.raw(truncateId(instance.instanceId())).color(AQUA).monospace(true))
                    ))
                    .exceptionally(throwable -> {
                        Throwable cause = throwable;
                        while (cause instanceof CompletionException && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        if (cause instanceof DungeonInstanceService.PartyStartPermissionException) {
                            context.sendMessage(
                                    Message.raw("Only the party owner can start a dungeon run.").color(RED)
                            );
                        } else if (cause instanceof DungeonInstanceService.UnsafePriorInstanceException) {
                            context.sendMessage(
                                    Message.raw("Cannot start: a party member is still entering or "
                                            + "changing floors in another dungeon. Try again shortly.").color(RED)
                            );
                        } else if (cause instanceof DungeonInstanceService.RosterValidationException rve) {
                            StringBuilder names = new StringBuilder();
                            for (UUID blocked : rve.getBlockedPlayers()) {
                                if (!names.isEmpty()) names.append(", ");
                                PlayerRef blockedRef = Universe.get().getPlayer(blocked);
                                names.append(blockedRef != null ? blockedRef.getUsername() : blocked.toString());
                            }
                            context.sendMessage(
                                    Message.raw("Cannot start: players already in a dungeon: ").color(RED)
                                            .insert(Message.raw(names.toString()).color(AQUA))
                            );
                        } else {
                            context.sendMessage(
                                    Message.raw("Failed to create instance: " + describeFailure(throwable)).color(RED)
                            );
                        }
                        return null;
                    });
        }
    }

    // ============================================
    // leave
    // ============================================

    private class LeaveSubCommand extends AbstractPlayerCommand {

        LeaveSubCommand() {
            super("leave", "Leave your active dungeon instance");
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

            dungeonInstanceService.leaveInstanceForPlayer(playerId)
                    .thenAccept(result -> {
                        switch (result.status()) {
                            case NOT_IN_INSTANCE -> context.sendMessage(
                                    Message.raw("You are not in an active dungeon.").color(YELLOW));
                        case LEFT_WITH_REMAINING -> sharedWorldRouter.route(
                            playerRef,
                            Message.raw("You left the dungeon. Your party members can continue it.")
                                .color(GREEN));
                        case ENDED_LAST_MEMBER -> sharedWorldRouter.route(
                            playerRef,
                            Message.raw("You left the dungeon. As the last member, the run was ended.")
                                .color(GREEN));
                        }
                    })
                    .exceptionally(throwable -> {
                        context.sendMessage(
                                Message.raw("Failed to leave dungeon: " + describeFailure(throwable)).color(RED)
                        );
                        return null;
                    });
        }
    }

    // ============================================
    // tpout
    // ============================================

    private class TpOutSubCommand extends AbstractPlayerCommand {
        TpOutSubCommand() {
            super("tpout", "Teleport near your active dungeon floor exit");
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
            CompletableFuture
                    .supplyAsync(() -> findActiveInstance(playerId))
                    .thenAccept(instance -> teleportToExit(context, playerRef, instance))
                    .exceptionally(throwable -> {
                        context.sendMessage(
                                Message.raw("Failed to look up dungeon instance: "
                                        + describeFailure(throwable)).color(RED)
                        );
                        return null;
                    });
        }

        @Nullable
        private DungeonInstance findActiveInstance(@Nonnull UUID playerId) {
            try {
                return dungeonInstanceService.getActiveInstance(playerId);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }

        private void teleportToExit(
                @Nonnull CommandContext context,
                @Nonnull PlayerRef playerRef,
                @Nullable DungeonInstance instance
        ) {
            if (instance == null) {
                context.sendMessage(Message.raw("You have no active dungeon instance.").color(RED));
                return;
            }
            if (instance.state() != DungeonInstanceState.ACTIVE) {
                context.sendMessage(
                        Message.raw("Cannot teleport to exit while instance is " + instance.state() + ".").color(RED)
                );
                return;
            }

            Ref<EntityStore> currentRef = playerRef.getReference();
            if (currentRef == null || !currentRef.isValid()) {
                context.sendMessage(Message.raw("Cannot teleport: player is not currently in a world.").color(RED));
                return;
            }

            Store<EntityStore> currentStore = currentRef.getStore();
            World currentWorld = currentStore.getExternalData().getWorld();
            if (currentWorld == null) {
                context.sendMessage(Message.raw("Cannot teleport: current world is unavailable.").color(RED));
                return;
            }

            currentWorld.execute(() -> queueExitTeleport(context, currentRef, instance));
        }

        private void queueExitTeleport(
                @Nonnull CommandContext context,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull DungeonInstance instance
        ) {
            if (!ref.isValid()) {
                context.sendMessage(Message.raw("Cannot teleport: player reference is no longer valid.").color(RED));
                return;
            }

            Universe universe = Universe.get();
            if (universe == null) {
                context.sendMessage(Message.raw("Cannot teleport: universe is unavailable.").color(RED));
                return;
            }

            World targetWorld = universe.getWorld(instance.worldName());
            if (targetWorld == null) {
                context.sendMessage(
                        Message.raw("Cannot teleport: dungeon world " + instance.worldName() + " is not loaded.").color(RED)
                );
                return;
            }

            Store<EntityStore> store = ref.getStore();
            Vec3i approachPosition = exitApproachPosition(instance);
            store.addComponent(
                    ref,
                    Teleport.getComponentType(),
                    Teleport.createForPlayer(targetWorld, toPlayerTransform(approachPosition))
            );
            context.sendMessage(
                    Message.raw("Teleported near dungeon exit at ").color(GREEN)
                        .insert(Message.raw(formatPosition(approachPosition)).color(AQUA))
                        .insert(Message.raw(". Walk into the portal to trigger it.").color(GRAY))
            );
        }
    }

    // ============================================
    // transition
    // ============================================

    private class TransitionSubCommand extends CommandBase {

        private final RequiredArg<String> instanceIdArg =
                this.withRequiredArg("instanceId", "Instance ID (full or prefix)", ArgTypes.STRING);

        TransitionSubCommand() {
            super("transition", "Advance instance to next floor");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String query = instanceIdArg.get(context);

            DungeonInstance instance;
            try {
                instance = resolveInstance(query);
            } catch (SQLException e) {
                context.sendMessage(Message.raw("Failed to query instance: " + e.getMessage()).color(RED));
                return;
            }

            if (instance == null) {
                context.sendMessage(Message.raw("Instance not found: " + query).color(RED));
                return;
            }

            String instanceId = instance.instanceId();

                DungeonInstanceService.FloorTransitionPreparation preparation = null;
                Set<UUID> transferPlayers = Set.of();
                try {
                preparation = dungeonInstanceService.prepareFloorTransition(instanceId);
                transferPlayers = floorTransitionParticipantPreparer.prepare(
                    preparation.activeRosterAfterExpansion(),
                    preparation.instance().worldName()).join();
                if (transferPlayers.isEmpty()) {
                    context.sendMessage(
                        Message.raw("Transition failed: no online active members are in world ")
                            .color(RED)
                            .insert(Message.raw(preparation.instance().worldName()).color(AQUA))
                    );
                    return;
                }

                DungeonInstance result = dungeonInstanceService.transitionFloor(
                    new DungeonInstanceService.FloorTransitionRequest(instanceId, transferPlayers)).join();
                context.sendMessage(
                        Message.raw("Transitioned instance ").color(GREEN)
                                .insert(Message.raw(truncateId(instanceId)).color(AQUA).monospace(true))
                        .insert(Message.raw(" to floor " + result.floorLevel()
                            + " with " + transferPlayers.size() + " transfer(s).").color(GREEN))
                );
            } catch (CompletionException e) {
                reEnablePreparedTransitionPlayers(transferPlayers, preparation);
                context.sendMessage(
                        Message.raw("Transition failed: " + describeFailure(e)).color(RED)
                );
            } catch (SQLException | IllegalArgumentException | IllegalStateException e) {
                reEnablePreparedTransitionPlayers(transferPlayers, preparation);
                context.sendMessage(
                        Message.raw("Transition failed: " + describeFailure(e)).color(RED)
                );
            }
        }
    }

    // ============================================
    // floorconfig
    // ============================================

    private class FloorConfigSubCommand extends AbstractPlayerCommand {

        private final OptionalArg<Integer> floorArg =
            this.withOptionalArg("floor", "Floor level (default 1)", ArgTypes.INTEGER);

        FloorConfigSubCommand() {
            super("floorconfig", "Manage per-floor generation overrides");
            this.addSubCommand(new FCListSubCommand());
            this.addSubCommand(new FCPacksSubCommand());
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            int floorLevel = floorArg.provided(context) ? floorArg.get(context) : CombatScaling.MIN_LEVEL;
            if (!CombatScaling.isSupportedLevel(floorLevel)) {
                context.sendMessage(Message.raw("Floor level must be between "
                    + CombatScaling.MIN_LEVEL + " and " + CombatScaling.MAX_LEVEL + ".").color(RED));
                return;
            }

            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }

            if (player.getGameMode() != GameMode.Creative) {
                context.sendMessage(Message.raw(
                        "/dungeon floorconfig requires Creative mode")
                        .color(RED));
                return;
            }

            player.getPageManager().openCustomPage(ref, store,
                    new FloorConfigPage(playerRef, floorLevel, floorConfigService));
        }

        // ── list ─────────────────────────────────────────────────────

        private class FCListSubCommand extends CommandBase {

            FCListSubCommand() {
                super("list", "List active floor config asset breakpoints");
            }

            @Override
            protected void executeSync(@Nonnull CommandContext context) {
                List<Integer> floors = floorConfigService.listDefinedFloors();

                if (floors.isEmpty()) {
                    context.sendMessage(
                            Message.raw("No floor config assets are active. All floors use code defaults.").color(GRAY)
                    );
                    return;
                }

                context.sendMessage(
                        Message.raw("Active Floor Config Assets (" + floors.size() + "):").color(GOLD).bold(true)
                );
                for (int floor : floors) {
                    context.sendMessage(
                            Message.raw("  Floor " + floor).color(AQUA)
                    );
                }
            }
        }

        // ── packs ────────────────────────────────────────────────────

        private class FCPacksSubCommand extends CommandBase {

            FCPacksSubCommand() {
                super("packs", "List asset pack floor-config targets");
            }

            @Override
            protected void executeSync(@Nonnull CommandContext context) {
                List<FloorConfigAssetRepository.PackChoice> choices = floorConfigService.listPackChoices();
                if (choices.isEmpty()) {
                    context.sendMessage(Message.raw("No asset packs are currently loaded.").color(GRAY));
                    return;
                }

                context.sendMessage(
                        Message.raw("Floor Config Asset Packs (" + choices.size() + "):").color(GOLD).bold(true)
                );

                for (FloorConfigAssetRepository.PackChoice choice : choices) {
                    String color = choice.isValidTarget() ? GREEN : (choice.writable() ? YELLOW : RED);
                    context.sendMessage(
                            Message.raw("  " + choice.name()).color(color).monospace(true)
                                    .insert(Message.raw(" — " + choice.status()).color(GRAY))
                    );
                }
            }
        }
    }

    // ============================================
    // Helpers
    // ============================================

    @Nonnull
    private static String stateColor(@Nonnull DungeonInstanceState state) {
        return switch (state) {
            case CREATING -> YELLOW;
            case ACTIVE -> GREEN;
            case TRANSITIONING -> AQUA;
            case ENDED -> GRAY;
        };
    }

    @Nonnull
    private static String truncateId(@Nonnull String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    @Nonnull
    private static String formatPosition(@Nonnull Vec3i pos) {
        return pos.x() + ", " + pos.y() + ", " + pos.z();
    }

    @Nonnull
    private static Vec3i exitApproachPosition(@Nonnull DungeonInstance instance) {
        Vec3i exit = instance.exitPosition();
        Vec3i entrance = instance.entrancePosition();
        int xDelta = entrance.x() - exit.x();
        int zDelta = entrance.z() - exit.z();

        if (Math.abs(xDelta) >= Math.abs(zDelta) && xDelta != 0) {
            return new Vec3i(exit.x() + Integer.signum(xDelta) * EXIT_APPROACH_OFFSET_BLOCKS, exit.y(), exit.z());
        }
        if (zDelta != 0) {
            return new Vec3i(exit.x(), exit.y(), exit.z() + Integer.signum(zDelta) * EXIT_APPROACH_OFFSET_BLOCKS);
        }
        if (xDelta != 0) {
            return new Vec3i(exit.x() + Integer.signum(xDelta) * EXIT_APPROACH_OFFSET_BLOCKS, exit.y(), exit.z());
        }
        return new Vec3i(exit.x(), exit.y(), exit.z() - EXIT_APPROACH_OFFSET_BLOCKS);
    }

    @Nonnull
    private static Transform toPlayerTransform(@Nonnull Vec3i position) {
        return new Transform(position.x() + 0.5D, position.y(), position.z() + 0.5D);
    }

    private static void sendField(
            @Nonnull CommandContext context,
            @Nonnull String label,
            @Nonnull String value
    ) {
        sendField(context, label, value, WHITE);
    }

    private static void sendField(
            @Nonnull CommandContext context,
            @Nonnull String label,
            @Nonnull String value,
            @Nonnull String valueColor
    ) {
        context.sendMessage(
                Message.raw("  " + label + ": ").color(GRAY)
                        .insert(Message.raw(value).color(valueColor))
        );
    }

    private void retryEndedCleanup(@Nonnull CommandContext context, @Nonnull String instanceId) {
        try {
            dungeonInstanceService.endInstance(instanceId).join();
        } catch (CompletionException e) {
            context.sendMessage(
                    Message.raw("Cleanup retry for ENDED instance had errors: " + describeFailure(e)).color(YELLOW)
            );
            return;
        } catch (SQLException | IllegalArgumentException | IllegalStateException e) {
            context.sendMessage(
                    Message.raw("Cleanup retry failed: " + describeFailure(e)).color(RED)
            );
            return;
        }

        context.sendMessage(
                Message.raw("Cleanup retried for ENDED instance ").color(GREEN)
                        .insert(Message.raw(truncateId(instanceId)).color(AQUA).monospace(true))
                        .insert(Message.raw(".").color(GREEN))
        );
    }

    private void reEnablePreparedTransitionPlayers(
            @Nonnull Set<UUID> transferPlayers,
            @Nullable DungeonInstanceService.FloorTransitionPreparation preparation
    ) {
        if (preparation == null || transferPlayers.isEmpty()) {
            return;
        }
        try {
            floorTransitionRecovery.reEnable(
                    transferPlayers,
                    preparation.instance().worldName(),
                    preparation.instance()).join();
        } catch (CompletionException ignored) {
            // Command feedback should report the transition failure; recovery is best-effort.
        }
    }

    @Nonnull
    private static String describeFailure(@Nonnull Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.toString();
    }

    /**
     * Resolves an instance by full ID or prefix match against non-ended instances.
     */
    @Nullable
    private DungeonInstance resolveInstance(@Nonnull String query) throws SQLException {
        DungeonInstance exact = dungeonInstanceService.getInstanceById(query);
        if (exact != null) {
            return exact;
        }

        String lowerQuery = query.toLowerCase();
        List<DungeonInstance> nonEnded = dungeonInstanceService.listNonEndedInstances();
        DungeonInstance match = null;
        for (DungeonInstance candidate : nonEnded) {
            if (candidate.instanceId().toLowerCase().startsWith(lowerQuery)) {
                if (match != null) {
                    return null;
                }
                match = candidate;
            }
        }
        return match;
    }
}
