package com.duntale.loot;

import com.duntale.rpg.RpgService;
import com.duntale.rpg.RpgStat;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Fills a registered dungeon chest the first time it is opened, with the opener's Luck applied to
 * rarity promotion.
 *
 * <p>Reacts to {@link UseBlockEvent.Pre} (fired before the container window opens, so the contents
 * are present when the UI renders) on the acting player. {@link ChestLootService#rollAndFill}
 * no-ops for unregistered blocks and for chests already filled, so only the first opener of a
 * tracked chest triggers a roll. The event is never cancelled — the engine still opens the
 * container normally.
 */
public class ChestOpenLootSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {

    private final ChestLootService chestLootService;
    private final RpgService rpgService;

    public ChestOpenLootSystem(@Nonnull ChestLootService chestLootService,
                               @Nonnull RpgService rpgService) {
        super(UseBlockEvent.Pre.class);
        this.chestLootService = chestLootService;
        this.rpgService = rpgService;
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull UseBlockEvent.Pre event) {
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            return;
        }
        UUID playerId = uuidComponent.getUuid();
        int openerLuck = rpgService.getEffectiveStat(playerId, RpgStat.LUCK);

        Vector3i block = event.getTargetBlock();
        chestLootService.rollAndFill(world, block.x, block.y, block.z, openerLuck);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }
}
