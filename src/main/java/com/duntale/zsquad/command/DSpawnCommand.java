package com.duntale.zsquad.command;

import com.duntale.zsquad.progression.LeveledNpcSpawner;
import com.duntale.zsquad.progression.ScalingDataCache;
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
 *   <li>{@code /dspawn <npc> <count> <level> [--elite]} -- Spawn leveled NPCs near the player</li>
 *   <li>{@code /dspawn info <npc> <level>} -- Show scaled stats without spawning</li>
 * </ul>
 */
public class DSpawnCommand extends CommandBase {

    private static final String GOLD = "#FFD700";
    private static final String WHITE = "#FFFFFF";
    private static final String GRAY = "#AAAAAA";
    private static final String YELLOW = "#FFEE55";
    private static final String GREEN = "#55FF55";
    private static final String RED = "#FF5555";

    private final LeveledNpcSpawner spawner;
    private final ScalingDataCache scalingCache;

    /**
     * Creates a new /dspawn command.
     *
     * @param spawner      the leveled NPC spawner
     * @param scalingCache the scaling data cache
     */
    public DSpawnCommand(@Nonnull LeveledNpcSpawner spawner, @Nonnull ScalingDataCache scalingCache) {
        super("dspawn", "Dungeon scaling test commands");
        this.spawner = spawner;
        this.scalingCache = scalingCache;

        this.addSubCommand(new SpawnSubCommand());
        this.addSubCommand(new InfoSubCommand());
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage:").color(YELLOW));
        context.sendMessage(Message.raw("  /dspawn <npc> <count> <level> [--elite]").color(GRAY));
        context.sendMessage(Message.raw("  /dspawn info <npc> <level>").color(GRAY));
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
            String label = elite ? "ELITE " + npc : npc;
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

                Pair<Ref<EntityStore>, NPCEntity> result = spawner.spawn(store, npc, pos, level, elite);
                if (result != null) {
                    succeeded++;
                }
            }

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            ScalingDataCache.MonsterScaledData data = scalingCache.getMonsterScaled(npc, level);

            context.sendMessage(
                    Message.raw("Spawned " + succeeded + "/" + count + " ").color(GREEN)
                            .insert(Message.raw(label).color(GOLD).bold(true))
                            .insert(Message.raw(" Lv." + level + " in " + elapsed + "ms").color(GREEN))
            );

            int hp = elite ? data.eliteHp() : data.scaledHp();
            float dmg = elite ? data.eliteDamage() : data.scaledDamage();
            context.sendMessage(
                    Message.raw("  HP: " + hp + " | Dmg: " + String.format("%.1f", dmg)
                            + " | DmgMult: x" + String.format("%.2f", data.damageMult())).color(GRAY)
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
            super("info", "Show scaled stats without spawning");
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

            if (level < 1 || level > 60) {
                context.sendMessage(Message.raw("Level must be between 1 and 60.").color(RED));
                return;
            }

            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin == null || npcPlugin.getIndex(npc) < 0) {
                context.sendMessage(Message.raw("Unknown NPC role: " + npc).color(RED));
                return;
            }

            ScalingDataCache.MonsterScaledData data = scalingCache.getMonsterScaled(npc, level);

            context.sendMessage(
                    Message.raw("--- ").color(GRAY)
                            .insert(Message.raw(npc + " at Lv." + level).color(GOLD).bold(true))
                            .insert(Message.raw(" ---").color(GRAY))
            );
            context.sendMessage(statLine("Scaled HP", String.valueOf(data.scaledHp())));
            context.sendMessage(statLine("Scaled Damage", String.format("%.1f", data.scaledDamage())));
            context.sendMessage(statLine("Damage Mult", String.format("x%.3f", data.damageMult())));
            context.sendMessage(statLine("Elite HP", String.valueOf(data.eliteHp())));
            context.sendMessage(statLine("Elite Damage", String.format("%.1f", data.eliteDamage())));

            if (data.scaledHp() == 0) {
                context.sendMessage(
                        Message.raw("  (No scaling data found -- is the database populated?)").color(RED)
                );
            }
        }

        private Message statLine(@Nonnull String label, @Nonnull String value) {
            String padded = label + ": " + " ".repeat(Math.max(0, 16 - label.length()));
            return Message.raw("  " + padded).color(GRAY)
                    .insert(Message.raw(value).color(WHITE));
        }
    }
}
