package com.duntale.dungeon.preview;

import com.duntale.dungeongen.generator.GenerationArtifacts;
import com.duntale.dungeongen.generator.voxel.BlockCategory;
import com.duntale.dungeongen.generator.voxel.BlockGrid;
import com.duntale.dungeongen.model.DungeonBlueprint;
import com.duntale.dungeongen.model.MerchantDefinition;
import com.duntale.dungeongen.model.SpawnerDefinition;
import com.duntale.dungeongen.util.BlockResolver;
import com.hypixel.hytale.builtin.buildertools.prefabeditor.PrefabEditSessionManager;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolPrefabPreview;
import com.hypixel.hytale.protocol.packets.buildertools.ClipboardEntityChange;
import com.hypixel.hytale.protocol.packets.interface_.EditorBlocksChange;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.util.ColorParseUtil;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Converts dungeon generation artifacts into the prefab preview packet used by the client UI.
 *
 * @since 1.6.0
 */
public class DungeonPreviewPacketFactory {

    private static final String MARKER_MODEL_ID = "NPC_Spawn_Marker";
    private static final int PREVIEW_TILT = 23;
    private static final int PREVIEW_SPIN_SPEED = 27;
    private static final int PREVIEW_SCALE = 100;
    private static final int DEFAULT_BIOME_TINT = ColorParseUtil.colorToARGBInt(PrefabEditSessionManager.DEFAULT_TINT) & 16777215;
    private static final int DEFAULT_WATER_TINT = ColorParseUtil.colorToARGBInt(Environment.getUnknownFor("").getWaterTint()) & 16777215;

    private final Function<String, Integer> blockResolver;
    private final Function<String, Integer> fluidResolver;
    private final Function<String, Byte> fluidLevelResolver;
    private final Supplier<com.hypixel.hytale.protocol.Model> markerModelSupplier;

    /**
     * Creates a packet factory using the runtime Hytale asset maps.
     */
    public DungeonPreviewPacketFactory() {
        this(new BlockResolver());
    }

    /**
     * Creates a packet factory using the given dungeon block resolver.
     *
     * @param blockResolver resolver for dungeon block IDs
     */
    public DungeonPreviewPacketFactory(@Nonnull BlockResolver blockResolver) {
        this(blockResolver::resolve,
                DungeonPreviewPacketFactory::resolveFluidId,
                DungeonPreviewPacketFactory::resolveFluidLevel,
                DungeonPreviewPacketFactory::createMarkerModel);
    }

    DungeonPreviewPacketFactory(
            @Nonnull Function<String, Integer> blockResolver,
            @Nonnull Function<String, Integer> fluidResolver,
            @Nonnull Supplier<com.hypixel.hytale.protocol.Model> markerModelSupplier
    ) {
        this(blockResolver, fluidResolver, ignored -> (byte) 1, markerModelSupplier);
    }

    DungeonPreviewPacketFactory(
            @Nonnull Function<String, Integer> blockResolver,
            @Nonnull Function<String, Integer> fluidResolver,
            @Nonnull Function<String, Byte> fluidLevelResolver,
            @Nonnull Supplier<com.hypixel.hytale.protocol.Model> markerModelSupplier
    ) {
        this.blockResolver = blockResolver;
        this.fluidResolver = fluidResolver;
        this.fluidLevelResolver = fluidLevelResolver;
        this.markerModelSupplier = markerModelSupplier;
    }

    /**
     * Builds a prefab preview packet for the given generation artifacts.
     *
     * @param artifacts generated dungeon artifacts
     * @return a populated prefab preview packet
     */
    @Nonnull
    public BuilderToolPrefabPreview createPacket(@Nonnull GenerationArtifacts artifacts) {
        BlockGrid grid = artifacts.blockGrid();
        PreviewBounds bounds = PreviewBounds.from(grid);
        int anchorX = bounds.centerX();
        int anchorY = bounds.centerY();
        int anchorZ = bounds.centerZ();

        BlockSelection selection = new BlockSelection(grid.getBlockCount(), markerCount(artifacts.blueprint()));
        selection.setAnchor(anchorX, anchorY, anchorZ);
        selection.setSelectionArea(bounds.min(), bounds.max());
        populateSelection(grid, selection);

        EditorBlocksChange editorPacket = selection.toPacket();
        BuilderToolPrefabPreview packet = new BuilderToolPrefabPreview();
        packet.tilt = PREVIEW_TILT;
        packet.spinSpeed = PREVIEW_SPIN_SPEED;
        packet.previewScale = PREVIEW_SCALE;
        packet.blocksChange = editorPacket.blocksChange;
        packet.fluidsChange = editorPacket.fluidsChange;
        packet.entityChanges = buildEntityMarkers(artifacts.blueprint(), anchorX, anchorY, anchorZ);
        packet.biomeTint = DEFAULT_BIOME_TINT;
        packet.waterTint = DEFAULT_WATER_TINT;
        return packet;
    }

    /**
     * Applies biome and water tint from the player's current world position.
     *
     * @param packet    packet to update
     * @param playerRef player reference used to locate the current chunk
     */
    public static void applyTintFromPlayerPosition(
            @Nonnull BuilderToolPrefabPreview packet,
            @Nonnull PlayerRef playerRef
    ) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            applyDefaultTint(packet);
            return;
        }

        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        Vector3d pos = playerRef.getTransform().getPosition();
        int x = MathUtil.floor(pos.x);
        int y = MathUtil.floor(pos.y);
        int z = MathUtil.floor(pos.z);
        long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
        WorldChunk chunk = world.getNonTickingChunk(chunkIndex);
        if (chunk == null || chunk.getBlockChunk() == null) {
            applyDefaultTint(packet);
            return;
        }

        BlockChunk blockChunk = chunk.getBlockChunk();
        packet.biomeTint = blockChunk.getTint(x, z);
        Environment environment = Environment.getAssetMap().getAsset(blockChunk.getEnvironment(x, y, z));
        if (environment == null || environment.getWaterTint() == null) {
            packet.waterTint = DEFAULT_WATER_TINT;
            return;
        }

        Color waterColor = environment.getWaterTint();
        packet.waterTint = (waterColor.red & 255) << 16 | (waterColor.green & 255) << 8 | waterColor.blue & 255;
    }

    private void populateSelection(@Nonnull BlockGrid grid, @Nonnull BlockSelection selection) {
        for (int x = 0; x < grid.getWidth(); x++) {
            for (int y = 0; y < grid.getHeight(); y++) {
                for (int z = 0; z < grid.getDepth(); z++) {
                    String blockId = grid.get(x, y, z);
                    if (blockId == null) {
                        continue;
                    }
                    if (grid.getCategory(x, y, z) == BlockCategory.FLUID || blockId.startsWith("Fluid_")) {
                        int fluidId = fluidResolver.apply(blockId);
                        byte fluidLevel = fluidLevelResolver.apply(blockId);
                        if (fluidId > 0 && fluidLevel > 0) {
                            selection.addFluidAtLocalPos(x, y, z, fluidId, fluidLevel);
                        }
                        continue;
                    }

                    int resolvedBlock = blockResolver.apply(blockId);
                    if (resolvedBlock > 0) {
                        selection.addBlockAtLocalPos(x, y, z, resolvedBlock, grid.getRotation(x, y, z), 0, 0);
                    }
                }
            }
        }
    }

    @Nonnull
    private ClipboardEntityChange[] buildEntityMarkers(@Nonnull DungeonBlueprint blueprint, int anchorX, int anchorY, int anchorZ) {
        List<ClipboardEntityChange> markers = new ArrayList<>(markerCount(blueprint));
        for (SpawnerDefinition spawner : blueprint.getSpawners()) {
            markers.add(createMarker(spawner.x(), spawner.y(), spawner.z(), anchorX, anchorY, anchorZ));
        }
        for (MerchantDefinition merchant : blueprint.getMerchants()) {
            markers.add(createMarker(merchant.x(), merchant.y(), merchant.z(), anchorX, anchorY, anchorZ));
        }
        return markers.toArray(ClipboardEntityChange[]::new);
    }

    @Nonnull
    private ClipboardEntityChange createMarker(int x, int y, int z, int anchorX, int anchorY, int anchorZ) {
        ClipboardEntityChange marker = new ClipboardEntityChange();
        marker.x = x - anchorX + 0.5F;
        marker.y = y - anchorY;
        marker.z = z - anchorZ + 0.5F;
        marker.scale = 1.0F;
        com.hypixel.hytale.protocol.Model model = markerModelSupplier.get();
        marker.model = model != null ? new com.hypixel.hytale.protocol.Model(model) : null;
        return marker;
    }

    private static int markerCount(@Nonnull DungeonBlueprint blueprint) {
        return blueprint.getSpawners().size() + blueprint.getMerchants().size();
    }

    private static int resolveFluidId(@Nonnull String blockId) {
        Fluid fluid = resolveFluid(blockId);
        return fluid == null ? -1 : Fluid.getAssetMap().getIndex(fluid.getId());
    }

    private static byte resolveFluidLevel(@Nonnull String blockId) {
        Fluid fluid = resolveFluid(blockId);
        return fluid == null ? 0 : (byte) fluid.getMaxFluidLevel();
    }

    private static Fluid resolveFluid(@Nonnull String blockId) {
        String baseName = blockId.startsWith("Fluid_") ? blockId.substring("Fluid_".length()) : blockId;
        return resolveFirstFluid(baseName + "_Source", blockId, baseName);
    }

    private static Fluid resolveFirstFluid(@Nonnull String... candidates) {
        for (String candidate : candidates) {
            Fluid fluid = Fluid.getAssetMap().getAsset(candidate);
            if (fluid != null) {
                return fluid;
            }
        }
        return null;
    }

    @Nonnull
    private static com.hypixel.hytale.protocol.Model createMarkerModel() {
        ModelAsset markerAsset = ModelAsset.getAssetMap().getAsset(MARKER_MODEL_ID);
        if (markerAsset != null) {
            return Model.createUnitScaleModel(markerAsset).toPacket();
        }
        com.hypixel.hytale.protocol.Model fallback = new com.hypixel.hytale.protocol.Model();
        fallback.assetId = MARKER_MODEL_ID;
        fallback.scale = 1.0F;
        return fallback;
    }

    private static void applyDefaultTint(@Nonnull BuilderToolPrefabPreview packet) {
        packet.biomeTint = DEFAULT_BIOME_TINT;
        packet.waterTint = DEFAULT_WATER_TINT;
    }

    private record PreviewBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        private static PreviewBounds from(@Nonnull BlockGrid grid) {
            int minX = grid.getWidth();
            int minY = grid.getHeight();
            int minZ = grid.getDepth();
            int maxX = -1;
            int maxY = -1;
            int maxZ = -1;

            for (int x = 0; x < grid.getWidth(); x++) {
                for (int y = 0; y < grid.getHeight(); y++) {
                    for (int z = 0; z < grid.getDepth(); z++) {
                        if (grid.get(x, y, z) == null) {
                            continue;
                        }
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        minZ = Math.min(minZ, z);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                        maxZ = Math.max(maxZ, z);
                    }
                }
            }

            if (maxX < 0) {
                return new PreviewBounds(0, 0, 0, 0, 0, 0);
            }
            return new PreviewBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private int centerX() {
            return Math.floorDiv(minX + maxX + 1, 2);
        }

        private int centerY() {
            return Math.floorDiv(minY + maxY + 1, 2);
        }

        private int centerZ() {
            return Math.floorDiv(minZ + maxZ + 1, 2);
        }

        @Nonnull
        private Vector3i min() {
            return new Vector3i(minX, minY, minZ);
        }

        @Nonnull
        private Vector3i max() {
            return new Vector3i(maxX, maxY, maxZ);
        }
    }
}