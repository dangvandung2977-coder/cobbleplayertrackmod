package com.cobbleverse.cobblesyncbridge;

import com.cobbleverse.cobblesyncbridge.backend.BackendClient;
import com.cobbleverse.cobblesyncbridge.command.BridgeCommands;
import com.cobbleverse.cobblesyncbridge.config.BridgeConfig;
import com.cobbleverse.cobblesyncbridge.config.BridgeConfigManager;
import com.cobbleverse.cobblesyncbridge.cobblemon.CobblemonReflectionAdapter;
import com.cobbleverse.cobblesyncbridge.sync.SyncCoordinator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CobbleSyncBridgeMod implements ModInitializer {
    public static final String MOD_ID = "cobblesyncbridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static CobbleSyncBridgeMod instance;

    private BridgeConfigManager configManager;
    private BridgeConfig config;
    private BackendClient backendClient;
    private CobblemonReflectionAdapter cobblemonAdapter;
    private SyncCoordinator syncCoordinator;

    public static CobbleSyncBridgeMod getInstance() {
        return instance;
    }

    @Override
    public void onInitialize() {
        instance = this;

        this.configManager = new BridgeConfigManager();
        this.config = this.configManager.loadOrCreate();
        this.backendClient = new BackendClient(this.config);
        this.cobblemonAdapter = new CobblemonReflectionAdapter();
        this.syncCoordinator = new SyncCoordinator(this.config, this.backendClient, this.cobblemonAdapter);

        registerLifecycle();
        registerCommands();

        LOGGER.info("[CobbleSyncBridge] Initialized. Backend={}, heartbeatTicks={}, playerSyncTicks={}",
                config.backendBaseUrl(),
                config.heartbeatIntervalTicks(),
                config.playerSyncIntervalTicks());
    }

    private void registerLifecycle() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                syncCoordinator.onPlayerJoin(handler.player, server));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                syncCoordinator.onPlayerDisconnect(handler.player, server));
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                BridgeCommands.register(dispatcher, syncCoordinator, cobblemonAdapter));
    }

    private void onServerStarted(MinecraftServer server) {
        syncCoordinator.onServerStarted(server);
    }

    private void onServerStopping(MinecraftServer server) {
        syncCoordinator.onServerStopping(server);
        backendClient.shutdown();
    }

    private void onServerTick(MinecraftServer server) {
        syncCoordinator.onServerTick(server);
    }

    public BridgeConfig config() {
        return config;
    }

    public void reloadConfig() {
        this.config = this.configManager.loadOrCreate();
        if (this.backendClient != null) {
            this.backendClient.shutdown();
        }
        this.backendClient = new BackendClient(this.config);
        this.syncCoordinator = new SyncCoordinator(this.config, this.backendClient, this.cobblemonAdapter);
    }
}
