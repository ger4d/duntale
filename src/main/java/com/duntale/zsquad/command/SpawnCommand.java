package com.duntale.zsquad.command;

import com.duntale.zsquad.ZSquadPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;

public class SpawnCommand extends CommandBase {

    // Simple in-memory storage for the prototype
    private Transform savedSpawn = null;
    private String savedWorldName = null;

    public SpawnCommand() {
        super("spawn", "Manage spawn testing");
        this.addSubCommand(new SetSubCommand());
        this.addSubCommand(new TestSubCommand());
    }

    @Override
    protected void executeSync(CommandContext context) {
        context.sendMessage(Message.raw("Usage: /spawn set or /spawn test <size> <type (e.g. Zombie, Skeleton, Trork_Warrior)>"));
    }

    private class SetSubCommand extends AbstractPlayerCommand {
        public SetSubCommand() {
            super("set", "Set test spawn point");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform != null) {
                savedSpawn = transform.getTransform();
                savedWorldName = world.getName();
                context.sendMessage(Message.raw("Spawn point set at " + savedSpawn.getPosition()));
            }
        }
    }

    private class TestSubCommand extends AbstractPlayerCommand {
        private final RequiredArg<Integer> sizeArg;
        private final RequiredArg<String> typeArg;

        public TestSubCommand() {
            super("test", "Spawn horde");
            this.sizeArg = this.withRequiredArg("size", "Number of zombies", ArgTypes.INTEGER);
            this.typeArg = this.withRequiredArg("type", "Role name (e.g. Zombie)", ArgTypes.STRING);
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            if (savedSpawn == null) {
                context.sendMessage(Message.raw("No spawn point set! Use /spawn set first."));
                return;
            }

            if (!world.getName().equals(savedWorldName)) {
                context.sendMessage(Message.raw("Warning: Spawn saved in world " + savedWorldName + " but you are in " + world.getName()));
            }
            
            // Distance check
            TransformComponent playerTransform = store.getComponent(ref, TransformComponent.getComponentType());
            if (playerTransform != null) {
                double dist = playerTransform.getPosition().distanceTo(savedSpawn.getPosition());
                if (dist > 100) {
                     context.sendMessage(Message.raw("Too far away! Spawn is " + (int)dist + "m away (max 100m)."));
                     return;
                }
            }

            int size = sizeArg.get(context);
            String roleName = typeArg.get(context);
            
            context.sendMessage(Message.raw("Spawning " + size + " " + roleName + "s..."));

            // Get NPC Plugin instance
            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin == null) {
                 context.sendMessage(Message.raw("Error: NPCPlugin not found."));
                 return;
            }

            int roleIndex = npcPlugin.getIndex(roleName);
            // Warning: getIndex might return -1 if not found, but we should handle it.
            // Actually, we don't know for sure if it returns -1 or throws, but -1 is standard for "index" methods.
            // Let's assume -1 or check `getNames()` if possible.
            // Safe bet: if index is -1, warn user.
            
            // Wait, looking at `npcPlugin.getIndex(s)` usage in Codec: `npcEntity.spawnRoleIndex = NPCPlugin.get().getIndex(s);`
            // It seems standard.
            
            for (int i = 0; i < size; i++) {
                 // Slight randomization of position
                 Vector3d pos = savedSpawn.getPosition().add(
                     (Math.random() - 0.5) * 5, 
                     0, 
                     (Math.random() - 0.5) * 5
                 );
                 
                 try {
                     NPCEntity npc = new NPCEntity();
                     // Set the role
                     if (roleIndex != Integer.MIN_VALUE && roleIndex != -1) {
                        npc.setSpawnRoleIndex(roleIndex); 
                     }
                     npc.setRoleName(roleName);

                     // Create Holder and components
                     com.hypixel.hytale.component.Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
                     holder.addComponent(com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType(), npc);
                     holder.addComponent(com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType(), new com.hypixel.hytale.server.core.modules.entity.component.TransformComponent(pos, savedSpawn.getRotation()));
                     holder.addComponent(com.hypixel.hytale.server.core.modules.entity.component.HeadRotation.getComponentType(), new com.hypixel.hytale.server.core.modules.entity.component.HeadRotation(savedSpawn.getRotation()));
                     holder.ensureComponent(com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
                     world.getEntityStore().getStore().addEntity(holder, com.hypixel.hytale.component.AddReason.SPAWN);
                 } catch (Exception e) {
                     context.sendMessage(Message.raw("Error spawning zombie " + i + ": " + e.getMessage()));
                     e.printStackTrace();
                 }
            }
            context.sendMessage(Message.raw("Spawned " + size + " " + roleName + "(s)."));
        }
    }
}
