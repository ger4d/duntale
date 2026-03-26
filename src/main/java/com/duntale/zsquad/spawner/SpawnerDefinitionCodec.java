package com.duntale.zsquad.spawner;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.model.SpawnEntry;
import com.duntale.dungeongen.model.SpawnerDefinition;
import com.duntale.dungeongen.model.SpawnerType;
import com.duntale.dungeongen.model.SpawnerVariant;
import com.duntale.dungeongen.model.TriggerConfig;
import com.duntale.dungeongen.model.TriggerType;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * BSON codec for {@link SpawnerDefinition} and its nested record types.
 * Used by {@link SpawnerComponent#CODEC} to persist spawner definitions across chunk save/load.
 */
final class SpawnerDefinitionCodec implements Codec<SpawnerDefinition> {

    static final SpawnerDefinitionCodec INSTANCE = new SpawnerDefinitionCodec();

    private SpawnerDefinitionCodec() {}

    @Nonnull
    @Override
    public SpawnerDefinition decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
        BsonDocument doc = bsonValue.asDocument();
        return fromBson(doc);
    }

    @Nonnull
    @Override
    public BsonValue encode(@Nonnull SpawnerDefinition def, ExtraInfo extraInfo) {
        return toBson(def);
    }

    @Nullable
    @Override
    public SpawnerDefinition decodeJson(@Nonnull RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
        // Component serialization is BSON-only; JSON path returns a placeholder.
        return placeholder();
    }

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        return new ObjectSchema();
    }

    // ── Conversion helpers ──────────────────────────────────────────

    @Nonnull
    static BsonDocument toBson(@Nonnull SpawnerDefinition def) {
        BsonDocument doc = new BsonDocument();
        doc.put("Id", new BsonInt32(def.id()));
        doc.put("X", new BsonInt32(def.x()));
        doc.put("Y", new BsonInt32(def.y()));
        doc.put("Z", new BsonInt32(def.z()));
        doc.put("RoomId", new BsonInt32(def.roomId()));
        doc.put("Type", new BsonString(def.type().name()));
        doc.put("TotalCount", new BsonInt32(def.totalCount()));
        doc.put("Variant", new BsonString(def.variant().name()));
        doc.put("FloorLevel", new BsonInt32(def.floorLevel()));
        doc.put("LevelVariance", new BsonInt32(def.levelVariance()));

        // Trigger
        BsonDocument trigDoc = new BsonDocument();
        trigDoc.put("Type", new BsonString(def.trigger().type().name()));
        trigDoc.put("ActivationRadius", new BsonDouble(def.trigger().activationRadius()));
        trigDoc.put("DeactivationRadius", new BsonDouble(def.trigger().deactivationRadius()));
        trigDoc.put("DelaySec", new BsonDouble(def.trigger().delaySec()));
        doc.put("Trigger", trigDoc);

        // SpawnPool
        BsonArray poolArr = new BsonArray();
        for (SpawnEntry e : def.spawnPool()) {
            BsonDocument entry = new BsonDocument();
            entry.put("NpcRole", new BsonString(e.npcRole()));
            entry.put("Weight", new BsonDouble(e.weight()));
            poolArr.add(entry);
        }
        doc.put("SpawnPool", poolArr);

        // SpawnOffsets
        BsonArray offsetArr = new BsonArray();
        for (Vec3i v : def.spawnOffsets()) {
            BsonDocument offset = new BsonDocument();
            offset.put("X", new BsonInt32(v.x()));
            offset.put("Y", new BsonInt32(v.y()));
            offset.put("Z", new BsonInt32(v.z()));
            offsetArr.add(offset);
        }
        doc.put("SpawnOffsets", offsetArr);
        return doc;
    }

    @Nonnull
    static SpawnerDefinition fromBson(@Nonnull BsonDocument doc) {
        int id = doc.getInt32("Id").getValue();
        int x = doc.getInt32("X").getValue();
        int y = doc.getInt32("Y").getValue();
        int z = doc.getInt32("Z").getValue();
        int roomId = doc.getInt32("RoomId").getValue();
        SpawnerType type = SpawnerType.valueOf(doc.getString("Type").getValue());
        int totalCount = doc.getInt32("TotalCount").getValue();
        SpawnerVariant variant = SpawnerVariant.valueOf(doc.getString("Variant").getValue());
        int floorLevel = doc.getInt32("FloorLevel").getValue();
        int levelVariance = doc.getInt32("LevelVariance").getValue();

        BsonDocument trigDoc = doc.getDocument("Trigger");
        TriggerConfig trigger = new TriggerConfig(
                TriggerType.valueOf(trigDoc.getString("Type").getValue()),
                trigDoc.getDouble("ActivationRadius").getValue(),
                trigDoc.getDouble("DeactivationRadius").getValue(),
                trigDoc.getDouble("DelaySec").getValue()
        );

        List<SpawnEntry> spawnPool = new ArrayList<>();
        for (BsonValue bv : doc.getArray("SpawnPool")) {
            BsonDocument entry = bv.asDocument();
            spawnPool.add(new SpawnEntry(
                    entry.getString("NpcRole").getValue(),
                    entry.getDouble("Weight").getValue()
            ));
        }

        List<Vec3i> spawnOffsets = new ArrayList<>();
        for (BsonValue bv : doc.getArray("SpawnOffsets")) {
            BsonDocument offset = bv.asDocument();
            spawnOffsets.add(new Vec3i(
                    offset.getInt32("X").getValue(),
                    offset.getInt32("Y").getValue(),
                    offset.getInt32("Z").getValue()
            ));
        }

        return new SpawnerDefinition(id, x, y, z, roomId, type, trigger,
                spawnPool, totalCount, spawnOffsets, variant, floorLevel, levelVariance);
    }

    /** Returns a no-op placeholder used when the JSON decode path is hit during registration validation. */
    @Nonnull
    private static SpawnerDefinition placeholder() {
        return new SpawnerDefinition(0, 0, 0, 0, 0,
                SpawnerType.FIXED,
                TriggerConfig.proximity(0),
                List.of(), 0, List.of(), SpawnerVariant.NORMAL, 1, 0);
    }
}
