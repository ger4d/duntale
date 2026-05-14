package com.duntale;

import com.duntale.config.asset.CustomizeCharacterConfigAsset;
import com.hypixel.hytale.math.vector.Transform;
import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parsed configuration for the character customization entry flow.
 */
public final class CustomizeCharacterConfig {

    private final String defaultCompanionRole;
    private final List<SetupSlot> setupSlots;
    private final CameraConfig camera;
    private final CompanionOffset companionOffset;

    public CustomizeCharacterConfig(
            @Nonnull String defaultCompanionRole,
            @Nonnull List<SetupSlot> setupSlots,
            @Nonnull CameraConfig camera,
            @Nonnull CompanionOffset companionOffset
    ) {
        this.defaultCompanionRole = Objects.requireNonNull(defaultCompanionRole, "defaultCompanionRole");
        this.setupSlots = List.copyOf(Objects.requireNonNull(setupSlots, "setupSlots"));
        this.camera = Objects.requireNonNull(camera, "camera");
        this.companionOffset = Objects.requireNonNull(companionOffset, "companionOffset");
    }

    @Nonnull
    public String defaultCompanionRole() {
        return defaultCompanionRole;
    }

    @Nonnull
    public List<SetupSlot> setupSlots() {
        return setupSlots;
    }

    @Nonnull
    public CameraConfig camera() {
        return camera;
    }

    @Nonnull
    public CompanionOffset companionOffset() {
        return companionOffset;
    }

    @Nonnull
    static CustomizeCharacterConfig defaultConfig() {
        return new CustomizeCharacterConfig(
            "Companion_Wolf_Black",
                List.of(),
                new CameraConfig(5.5F, -0.12F, 1.25F),
                new CompanionOffset(1.25D, 0.0D, 0.75D)
        );
    }

    @Nonnull
    public static CustomizeCharacterConfig fromAsset(@Nonnull CustomizeCharacterConfigAsset asset) {
        List<SetupSlot> parsedSlots = new ArrayList<>(asset.getSetupSlots().length);
        for (BsonDocument slotDocument : asset.getSetupSlots()) {
            parsedSlots.add(new SetupSlot(
                    readDouble(slotDocument, "X", 0.0D),
                    readDouble(slotDocument, "Y", 0.0D),
                    readDouble(slotDocument, "Z", 0.0D),
                authoredAngleToRadians(readDouble(slotDocument, "PlayerYaw", 0.0D)),
                    slotDocument.containsKey("CameraYaw")
                    ? authoredAngleToRadians(readDouble(slotDocument, "CameraYaw", 0.0D))
                            : null
            ));
        }

        BsonDocument cameraDocument = asset.getCamera();
        CameraConfig camera = new CameraConfig(
                (float) readDouble(cameraDocument, "Distance", 5.5D),
                (float) readDouble(cameraDocument, "Pitch", -0.12D),
                (float) readDouble(cameraDocument, "HeightOffset", 1.25D)
        );

        BsonDocument companionOffsetDocument = asset.getCompanionOffset();
        CompanionOffset companionOffset = new CompanionOffset(
                readDouble(companionOffsetDocument, "X", 1.25D),
                readDouble(companionOffsetDocument, "Y", 0.0D),
                readDouble(companionOffsetDocument, "Z", 0.75D)
        );

        return new CustomizeCharacterConfig(
                asset.getDefaultCompanionRole(),
                parsedSlots,
                camera,
                companionOffset
        );
    }

    private static double readDouble(@Nonnull BsonDocument document, @Nonnull String key, double fallback) {
        return document.containsKey(key) ? document.getNumber(key).doubleValue() : fallback;
    }

    @Nullable
    private static Float authoredAngleToRadians(@Nullable Double authoredAngle) {
        if (authoredAngle == null) {
            return null;
        }

        double absAngle = Math.abs(authoredAngle);
        double radians = absAngle > (Math.PI * 2.0 + 0.001)
                ? Math.toRadians(authoredAngle)
                : authoredAngle;
        return (float) radians;
    }

    /**
     * Configured staging location for one customization slot.
     */
    public record SetupSlot(
            double x,
            double y,
            double z,
            float playerYaw,
            @Nullable Float cameraYaw
    ) {
        @Nonnull
        public Transform toPlayerTransform() {
            return new Transform(x, y, z, 0.0F, playerYaw, 0.0F);
        }

        public float resolvedCameraYaw() {
            return cameraYaw != null ? cameraYaw : playerYaw;
        }
    }

    /**
     * Camera settings applied during customization.
     */
    public record CameraConfig(float distance, float pitch, float heightOffset) {
    }

    /**
     * Companion offset relative to the player staging position.
     */
    public record CompanionOffset(double x, double y, double z) {
    }
}