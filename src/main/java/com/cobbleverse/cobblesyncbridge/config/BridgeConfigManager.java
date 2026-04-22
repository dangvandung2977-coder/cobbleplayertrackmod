package com.cobbleverse.cobblesyncbridge.config;

import com.cobbleverse.cobblesyncbridge.CobbleSyncBridgeMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BridgeConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "cobblesyncbridge.json";

    public BridgeConfig loadOrCreate() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path path = configDir.resolve(FILE_NAME);

        try {
            Files.createDirectories(configDir);

            if (Files.notExists(path)) {
                BridgeConfig defaults = BridgeConfig.defaults();
                try (Writer writer = Files.newBufferedWriter(path)) {
                    GSON.toJson(defaults, writer);
                }
                CobbleSyncBridgeMod.LOGGER.warn("[CobbleSyncBridge] Created default config at {}", path);
                return defaults;
            }

            try (Reader reader = Files.newBufferedReader(path)) {
                BridgeConfig loaded = GSON.fromJson(reader, BridgeConfig.class);
                return loaded != null ? loaded : BridgeConfig.defaults();
            }
        } catch (IOException e) {
            CobbleSyncBridgeMod.LOGGER.error("[CobbleSyncBridge] Failed to load config, using defaults", e);
            return BridgeConfig.defaults();
        }
    }
}
