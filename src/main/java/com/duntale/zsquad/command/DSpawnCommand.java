package com.duntale.zsquad.command;

import com.duntale.zsquad.progression.AssetCatalog;
import com.duntale.zsquad.progression.CombatScaling;
import com.duntale.zsquad.progression.LeveledNpcSpawner;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;

/**
 * Testing command for the dungeon scaling system.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code /dspawn <npc> <count> <level> [--elite | --boss]} -- Spawn leveled NPCs near the player</li>
 *   <li>{@code /dspawn info <npc> <level>} -- Show scaled stats for all variants</li>
 * </ul>
 */
public class DSpawnCommand extends CommandBase {

    private static final String COMPANION_ROLE_PREFIX = "Companion_";

    private static final String GOLD = "#FFD700";
    private static final String WHITE = "#FFFFFF";
    private static final String GRAY = "#AAAAAA";
    private static final String YELLOW = "#FFEE55";
    private static final String GREEN = "#55FF55";
    private static final String RED = "#FF5555";

    private final LeveledNpcSpawner spawner;
    private final AssetCatalog assetCatalog;

    /**
     * Creates a new /dspawn command.
     *
     * @param spawner      the leveled NPC spawner
     * @param assetCatalog the asset catalog for base HP lookups
     */
    public DSpawnCommand(@Nonnull LeveledNpcSpawner spawner, @Nonnull AssetCatalog assetCatalog) {
        super("dspawn", "Dungeon scaling test commands");
        this.spawner = spawner;
        this.assetCatalog = assetCatalog;

        this.addSubCommand(new SpawnSubCommand());
        this.addSubCommand(new InfoSubCommand());
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage:").color(YELLOW));
        context.sendMessage(Message.raw("  /dspawn <npc> <count> <level> [--elite | --boss]").color(GRAY));
        context.sendMessage(Message.raw("  /dspawn info <npc> <level>").color(GRAY));
    }

    /**
     * Resolves the base HP for an NPC role without spawning it.
     */
    private int resolveBaseHp(@Nonnull String roleName) {
        return assetCatalog.getMonsterBaseHp(roleName);
    }

    // -- Spawn subcommand (default) -----------------------------------

    private class SpawnSubCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> npcArg =
                this.withRequiredArg("npc", "NPC role name (e.g. Zombie)", ArgTypes.STRING);
        private final RequiredArg<Integer> countArg =
                this.withRequiredArg("count", "Number of NPCs (1-20)", ArgTypes.INTEGER);
        private final RequiredArg<Integer> levelArg =
                this.withRequiredArg("level", "Dungeon level (1-60)", ArgTypes.INTEGER);
        private final FlagArg eliteFlag =
                this.withFlagArg("elite", "Spawn as elite variant (1.2x scale)");
        private final FlagArg bossFlag =
                this.withFlagArg("boss", "Spawn as boss variant");

        SpawnSubCommand() {
            super("spawn", "Spawn leveled NPCs near you");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            String npc = npcArg.get(context);
            int count = countArg.get(context);
            int level = levelArg.get(context);
            boolean elite = eliteFlag.get(context);
            boolean boss = bossFlag.get(context);

            // Mutually exclusive flags
            if (elite && boss) {
                context.sendMessage(Message.raw("Cannot use --elite and --boss together.").color(RED));
                return;
            }

            CombatScaling.NpcVariant variant = boss ? CombatScaling.NpcVariant.BOSS
                    : elite ? CombatScaling.NpcVariant.ELITE
                    : CombatScaling.NpcVariant.NORMAL;

            // Validation
            if (level < 1 || level > 60) {
                context.sendMessage(Message.raw("Level must be between 1 and 60.").color(RED));
                return;
            }
            if (count < 1 || count > 20) {
                context.sendMessage(Message.raw("Count must be between 1 and 20.").color(RED));
                return;
            }

            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin == null || npcPlugin.getIndex(npc) < 0) {
                context.sendMessage(Message.raw("Unknown NPC role: " + npc).color(RED));
                return;
            }

            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                context.sendMessage(Message.raw("Could not determine your position.").color(RED));
                return;
            }

            Vector3d basePos = transform.getPosition();
            String label = variant != CombatScaling.NpcVariant.NORMAL
                    ? variant.name() + " " + npc : npc;
            context.sendMessage(
                    Message.raw("Spawning " + count + "x ").color(YELLOW)
                            .insert(Message.raw(label).color(GOLD).bold(true))
                            .insert(Message.raw(" at Lv." + level + "...").color(YELLOW))
            );

            long startTime = System.nanoTime();
            int succeeded = 0;

            for (int i = 0; i < count; i++) {
                Vector3d pos = basePos.add(
                        (Math.random() - 0.5) * 6,
                        0,
                        (Math.random() - 0.5) * 6
                );

                Pair<Ref<EntityStore>, NPCEntity> result = spawner.spawn(store, npc, pos, level, variant);
                if (result != null) {
                    succeeded++;
                }
            }

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;

            context.sendMessage(
                    Message.raw("Spawned " + succeeded + "/" + count + " ").color(GREEN)
                            .insert(Message.raw(label).color(GOLD).bold(true))
                            .insert(Message.raw(" Lv." + level + " in " + elapsed + "ms").color(GREEN))
            );

            // Post-spawn feedback using runtime computation
            int baseHp = resolveBaseHp(npc);
            int scaledHp = CombatScaling.npcScaledHp(baseHp, level, variant);
            float damageMult = CombatScaling.npcDamageMult(level, variant);
            context.sendMessage(
                    Message.raw("  HP: " + scaledHp + " | DmgMult: x" + String.format("%.2f", damageMult)).color(GRAY)
            );
        }
    }

    // -- Info subcommand (dry run) ------------------------------------

    private class InfoSubCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> npcArg =
                this.withRequiredArg("npc", "NPC role name", ArgTypes.STRING);
        private final RequiredArg<Integer> levelArg =
                this.withRequiredArg("level", "Dungeon level (1-60)", ArgTypes.INTEGER);

        InfoSubCommand() {
            super("info", "Show scaled stats for all variants");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            String npc = npcArg.get(context);
            int level = levelArg.get(context);
            boolean companionRole = npc.startsWith(COMPANION_ROLE_PREFIX);

            if (level < 1 || level > 60) {
                context.sendMessage(Message.raw("Level must be between 1 and 60.").color(RED));
                return;
            }

            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin == null || npcPlugin.getIndex(npc) < 0) {
                context.sendMessage(Message.raw("Unknown NPC role: " + npc).color(RED));
                return;
            }

            context.sendMessage(
                    Message.raw("--- ").color(GRAY)
                            .insert(Message.raw(npc + " at Lv." + level).color(GOLD).bold(true))
                            .insert(Message.raw(" ---").color(GRAY))
            );

            int baseHp = resolveBaseHp(npc);
            if (companionRole) {
                int hp = CombatScaling.companionScaledHp(baseHp, level);
                float dmg = CombatScaling.companionDamageMult(level);
                context.sendMessage(statLine("COMPANION",
                    "HP: " + hp + " | DmgMult: x" + String.format("%.2f", dmg)));
                return;
            }

            for (CombatScaling.NpcVariant v : CombatScaling.NpcVariant.values()) {
                int hp = CombatScaling.npcScaledHp(baseHp, level, v);
                float dmg = CombatScaling.npcDamageMult(level, v);
                context.sendMessage(statLine(v.name(),
                        "HP: " + hp + " | DmgMult: x" + String.format("%.2f", dmg)));
            }
        }

        private Message statLine(@Nonnull String label, @Nonnull String value) {
            String padded = label + ": " + " ".repeat(Math.max(0, 16 - label.length()));
            return Message.raw("  " + padded).color(GRAY)
                    .insert(Message.raw(value).color(WHITE));
        }
    }
}
