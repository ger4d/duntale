package com.duntale.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Codec for asset fields that intentionally preserve arbitrary JSON objects.
 */
public final class RawBsonDocumentCodec implements Codec<BsonDocument> {

    public static final RawBsonDocumentCodec INSTANCE = new RawBsonDocumentCodec();

    private RawBsonDocumentCodec() {
    }

    @Override
    @Nonnull
    public BsonDocument decode(@Nonnull BsonValue bsonValue, @Nonnull ExtraInfo extraInfo) {
        return bsonValue.asDocument();
    }

    @Override
    @Nonnull
    public BsonValue encode(@Nonnull BsonDocument document, @Nonnull ExtraInfo extraInfo) {
        return document;
    }

    @Override
    @Nonnull
    public BsonDocument decodeJson(@Nonnull RawJsonReader reader, @Nonnull ExtraInfo extraInfo) throws IOException {
        return readDocument(reader);
    }

    @Override
    @Nonnull
    public Schema toSchema(@Nonnull SchemaContext context) {
        return new ObjectSchema();
    }

    @Nonnull
    private BsonDocument readDocument(@Nonnull RawJsonReader reader) throws IOException {
        reader.consumeWhiteSpace();
        int first = reader.read();
        if (first != '{') {
            throw new IOException("Expected JSON object while reading BsonDocument");
        }

        StringBuilder json = new StringBuilder("{");
        int depth = 1;
        while (depth > 0) {
            int next = reader.read();
            if (next == -1) {
                throw new IOException("Unexpected end of JSON object while reading BsonDocument");
            }

            json.append((char) next);
            if (next == '"') {
                appendStringRemainder(reader, json);
            } else if (next == '{') {
                depth++;
            } else if (next == '}') {
                depth--;
            }
        }

        return BsonDocument.parse(json.toString());
    }

    private void appendStringRemainder(@Nonnull RawJsonReader reader, @Nonnull StringBuilder json) throws IOException {
        while (true) {
            int next = reader.read();
            if (next == -1) {
                throw new IOException("Unexpected end of JSON string while reading BsonDocument");
            }

            json.append((char) next);
            if (next == '\\') {
                int escaped = reader.read();
                if (escaped == -1) {
                    throw new IOException("Unexpected end of JSON string escape while reading BsonDocument");
                }
                json.append((char) escaped);
            } else if (next == '"') {
                return;
            }
        }
    }
}