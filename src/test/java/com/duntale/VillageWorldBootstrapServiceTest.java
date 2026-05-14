package com.duntale;

import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.codec.lookup.MapKeyMapCodec;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Options;
import com.hypixel.hytale.server.core.asset.AssetRegistryLoader;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("VillageWorldBootstrapService")
class VillageWorldBootstrapServiceTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void ensureCoreAssetStores() throws Exception {
        if (Options.getOptionSet() == null) {
            Options.parse(new String[]{"--bare"});
        }
        if (HytaleServer.get() == null) {
            HytaleServer server = allocateInstance(HytaleServer.class);
            setField(server, "eventBus", new EventBus(false));
            setStaticField(HytaleServer.class, "instance", server);
        }
        AssetRegistryLoader.init();
    }

    @Test
    @DisplayName("Should return the already loaded village world without loading or creating a copy")
    void shouldReturnAlreadyLoadedVillageWorld() {
        WorldConfig config = staleVillageConfig();
        TestWorld loadedWorld = testWorld(config);

        FakeRuntimeAccess runtimeAccess = new FakeRuntimeAccess();
        runtimeAccess.loadedWorld = loadedWorld;

        VillageWorldBootstrapService service = new VillageWorldBootstrapService(runtimeAccess);

        World resolvedWorld = service.ensureVillageWorldReady().join();

        assertSame(loadedWorld, resolvedWorld);
        assertEquals(0, runtimeAccess.loadWorldCalls);
        assertEquals(0, runtimeAccess.makeWorldCalls);
        assertVillageConfig(config);
        assertEquals(1, loadedWorld.getExecuteCalls());
    }

    @Test
    @DisplayName("Should load an existing saved village world when it is present on disk")
    void shouldLoadExistingSavedVillageWorld() {
        WorldConfig config = staleVillageConfig();
        TestWorld loadedWorld = testWorld(config);

        FakeRuntimeAccess runtimeAccess = new FakeRuntimeAccess();
        runtimeAccess.loadable = true;
        runtimeAccess.loadWorldResult = CompletableFuture.completedFuture(loadedWorld);

        VillageWorldBootstrapService service = new VillageWorldBootstrapService(runtimeAccess);

        World resolvedWorld = service.ensureVillageWorldReady().join();

        assertSame(loadedWorld, resolvedWorld);
        assertEquals(1, runtimeAccess.loadWorldCalls);
        assertEquals(0, runtimeAccess.makeWorldCalls);
        assertVillageConfig(config);
        assertEquals(1, loadedWorld.getExecuteCalls());
    }

    @Test
    @DisplayName("Should copy the authored template and create the village world when missing")
    void shouldCreateVillageWorldFromTemplateWhenMissing() throws Exception {
        Path templatePath = tempDir.resolve("Server/Instances/Village");
        Files.createDirectories(templatePath.resolve("nested"));
        Files.writeString(templatePath.resolve("nested/marker.txt"), "village-template");
        Files.writeString(templatePath.resolve("instance.bson"), "{}\n");

        WorldConfig templateConfig = staleVillageConfig();
        templateConfig.setDisplayName(null);
        UUID templateUuid = templateConfig.getUuid();

        Path worldPath = tempDir.resolve("universe/worlds/village");
        FakeRuntimeAccess runtimeAccess = new FakeRuntimeAccess();
        runtimeAccess.templatePath = templatePath;
        runtimeAccess.validatedWorldPath = worldPath;
        runtimeAccess.loadConfigResult = CompletableFuture.completedFuture(templateConfig);

        VillageWorldBootstrapService service = new VillageWorldBootstrapService(runtimeAccess);

        World resolvedWorld = service.ensureVillageWorldReady().join();

        assertSame(runtimeAccess.madeWorld, resolvedWorld);
        assertEquals(1, runtimeAccess.makeWorldCalls);
        assertTrue(Files.exists(worldPath.resolve("nested/marker.txt")));
        assertEquals("Village", runtimeAccess.lastMadeConfig.getDisplayName());
        assertNotEquals(templateUuid, runtimeAccess.lastMadeConfig.getUuid());
        assertVillageConfig(runtimeAccess.lastMadeConfig);
        assertEquals(1, runtimeAccess.madeWorld.getExecuteCalls());
    }

    private static void assertVillageConfig(WorldConfig config) {
        assertEquals("Village", config.getGameplayConfig());
        assertEquals(GameMode.Adventure, config.getGameMode());
        assertFalse(config.isDeleteOnRemove());
        assertNull(InstanceWorldConfig.get(config));
    }

    private static WorldConfig staleVillageConfig() {
        try {
            WorldConfig config = allocateInstance(WorldConfig.class);
            setField(config, "hasChanged", new AtomicBoolean());
            setField(config, "pluginConfig", new MapKeyMapCodec.TypeMap<>(WorldConfig.PLUGIN_CODEC));
            config.setUuid(UUID.randomUUID());
            config.setGameplayConfig("Default");
            config.setGameMode(GameMode.Creative);
            config.setDeleteOnRemove(true);
            InstanceWorldConfig.ensureAndGet(config);
            config.markChanged();
            return config;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create test WorldConfig", e);
        }
    }

    private static TestWorld testWorld(WorldConfig config) {
        try {
            TestWorld world = allocateInstance(TestWorld.class);
            setField(world, "testWorldConfig", config);
            return world;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create test World", e);
        }
    }

    private static void setStaticField(Class<?> type, String fieldName, Object value) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    @SuppressWarnings("removal")
    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        UNSAFE.putObject(target, UNSAFE.objectFieldOffset(field), value);
    }

    private static <T> T allocateInstance(Class<T> type) throws Exception {
        return type.cast(UNSAFE.allocateInstance(type));
    }

    private static Unsafe lookupUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to access Unsafe", e);
        }
    }

    private static final Unsafe UNSAFE = lookupUnsafe();

    private static final class TestWorld extends World {

        private WorldConfig testWorldConfig;
        private int executeCalls;

        private TestWorld() throws IOException {
            super("test-world", Path.of("."), null);
            throw new UnsupportedOperationException("TestWorld uses Unsafe allocation only");
        }

        @Override
        public WorldConfig getWorldConfig() {
            return testWorldConfig;
        }

        @Override
        public void execute(Runnable command) {
            executeCalls++;
            command.run();
        }

        private int getExecuteCalls() {
            return executeCalls;
        }
    }

    private static final class FakeRuntimeAccess implements VillageWorldBootstrapService.RuntimeAccess {

        private World loadedWorld;
        private boolean loadable;
        private CompletableFuture<World> loadWorldResult = CompletableFuture.failedFuture(
                new IllegalStateException("loadWorldResult was not configured")
        );
        private Path templatePath;
        private Path validatedWorldPath;
        private CompletableFuture<WorldConfig> loadConfigResult = CompletableFuture.failedFuture(
            new IllegalStateException("loadConfigResult was not configured")
        );
        private int loadWorldCalls;
        private int makeWorldCalls;
        private TestWorld madeWorld;
        private WorldConfig lastMadeConfig;

        @Override
        public World getLoadedWorld(String worldName) {
            return loadedWorld;
        }

        @Override
        public boolean isWorldLoadable(String worldName) {
            return loadable;
        }

        @Override
        public CompletableFuture<World> loadWorld(String worldName) {
            loadWorldCalls++;
            return loadWorldResult;
        }

        @Override
        public Path validateWorldPath(String worldName) {
            return validatedWorldPath;
        }

        @Override
        public Path resolveTemplatePath(String templateAssetName) {
            return templatePath;
        }

        @Override
        public CompletableFuture<WorldConfig> loadConfig(Path configPath) {
            return loadConfigResult;
        }

        @Override
        public CompletableFuture<World> makeWorld(String worldName, Path worldPath, WorldConfig config) {
            makeWorldCalls++;
            lastMadeConfig = config;
            madeWorld = testWorld(config);
            return CompletableFuture.completedFuture(madeWorld);
        }
    }
}