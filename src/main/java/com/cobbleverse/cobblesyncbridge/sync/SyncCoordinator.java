package com.cobbleverse.cobblesyncbridge.sync;

import com.cobbleverse.cobblesyncbridge.CobbleSyncBridgeMod;
import com.cobbleverse.cobblesyncbridge.backend.BackendClient;
import com.cobbleverse.cobblesyncbridge.backend.dto.*;
import com.cobbleverse.cobblesyncbridge.cobblemon.CobblemonReflectionAdapter;
import com.cobbleverse.cobblesyncbridge.cobblemon.CobblemonSnapshot;
import com.cobbleverse.cobblesyncbridge.config.BridgeConfig;
import com.cobbleverse.cobblesyncbridge.util.SkinResolver;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.SharedConstants;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class SyncCoordinator {
    private final BridgeConfig config;
    private final BackendClient backendClient;
    private final CobblemonReflectionAdapter adapter;

    private final Queue<UUID> resyncQueue = new ConcurrentLinkedQueue<>();
    private final Set<UUID> queuedPlayers = ConcurrentHashMap.newKeySet();

    private long tickCounter = 0L;
    private long lastRegisterAttemptTick = -1L;
    private long lastHeartbeatTick = -1L;
    private long lastPlayerSweepTick = -1L;

    public SyncCoordinator(BridgeConfig config, BackendClient backendClient, CobblemonReflectionAdapter adapter) {
        this.config = config;
        this.backendClient = backendClient;
        this.adapter = adapter;
    }

    public void onServerStarted(MinecraftServer server) {
        tryRegisterServer(server);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            queuePlayer(player.getUuid());
        }
    }

    private void tryRegisterServer(MinecraftServer server) {
        if (backendClient.knownServerId() != null) {
            return;
        }

        lastRegisterAttemptTick = tickCounter;
        ServerRegisterRequest request = new ServerRegisterRequest(
                config.serverName(),
                config.serverDisplayIp(),
                SharedConstants.getGameVersion().getName(),
                "1.7.3",
                "0.1.0-alpha"
        );

        backendClient.registerServer(request).thenAccept(serverId -> {
            if (serverId != null) {
                CobbleSyncBridgeMod.LOGGER.info("[CobbleSyncBridge] Registered server id={}", serverId);
                backendClient.setKnownServerId(serverId);
            } else {
                CobbleSyncBridgeMod.LOGGER.warn("[CobbleSyncBridge] Register server returned no serverId");
            }
        }).exceptionally(error -> null);
    }

    public void onServerStopping(MinecraftServer server) {
        // No-op for now. Could add final offline heartbeat later.
    }

    public void onPlayerJoin(ServerPlayerEntity player, MinecraftServer server) {
        upsertPlayer(player);
        queuePlayer(player.getUuid());
    }

    public void onPlayerDisconnect(ServerPlayerEntity player, MinecraftServer server) {
        String serverId = backendClient.knownServerId();
        if (serverId == null) return;

        backendClient.upsertPlayer(new PlayerUpsertRequest(
                player.getUuidAsString(),
                player.getName().getString(),
                serverId,
                SkinResolver.skinRenderUrl(player.getUuid()),
                false
        ));
    }

    public void onServerTick(MinecraftServer server) {
        tickCounter++;

        if (shouldRetryRegister()) {
            tryRegisterServer(server);
        }

        if (shouldHeartbeat()) {
            sendHeartbeat(server);
        }

        if (shouldSweepPlayers()) {
            lastPlayerSweepTick = tickCounter;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                queuePlayer(player.getUuid());
            }
        }

        int processed = 0;
        int maxPlayersPerTick = Math.max(1, config.maxPlayersPerTick());
        while (processed < maxPlayersPerTick) {
            UUID next = resyncQueue.poll();
            if (next == null) break;
            queuedPlayers.remove(next);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(next);
            if (player != null) {
                syncPlayer(player);
            }
            processed++;
        }
    }

    public void queuePlayer(UUID uuid) {
        if (uuid != null && queuedPlayers.add(uuid)) {
            resyncQueue.offer(uuid);
        }
    }

    public void resyncNow(ServerPlayerEntity player) {
        if (player != null) {
            queuePlayer(player.getUuid());
        }
    }

    public String statusSummary() {
        return "serverId=" + backendClient.knownServerId()
                + ", queued=" + resyncQueue.size()
                + ", heartbeatTicks=" + config.heartbeatIntervalTicks()
                + ", playerSyncTicks=" + config.playerSyncIntervalTicks();
    }

    private boolean shouldHeartbeat() {
        return lastHeartbeatTick < 0 || (tickCounter - lastHeartbeatTick) >= Math.max(1, config.heartbeatIntervalTicks());
    }

    private boolean shouldRetryRegister() {
        if (backendClient.knownServerId() != null) {
            return false;
        }

        int retryTicks = Math.max(20 * 15, config.heartbeatIntervalTicks());
        return lastRegisterAttemptTick < 0 || (tickCounter - lastRegisterAttemptTick) >= retryTicks;
    }

    private boolean shouldSweepPlayers() {
        return lastPlayerSweepTick < 0 || (tickCounter - lastPlayerSweepTick) >= Math.max(1, config.playerSyncIntervalTicks());
    }

    private void sendHeartbeat(MinecraftServer server) {
        String serverId = backendClient.knownServerId();
        if (serverId == null || serverId.isBlank()) return;

        lastHeartbeatTick = tickCounter;
        backendClient.sendHeartbeat(new ServerHeartbeatRequest(
                serverId,
                server.getPlayerManager().getCurrentPlayerCount()
        ));
    }

    private void upsertPlayer(ServerPlayerEntity player) {
        String serverId = backendClient.knownServerId();
        if (serverId == null || serverId.isBlank()) return;

        backendClient.upsertPlayer(new PlayerUpsertRequest(
                player.getUuidAsString(),
                player.getName().getString(),
                serverId,
                SkinResolver.skinRenderUrl(player.getUuid()),
                true
        ));
    }

    private void syncPlayer(ServerPlayerEntity player) {
        String serverId = backendClient.knownServerId();
        if (serverId == null || serverId.isBlank()) return;

        upsertPlayer(player);

        CobblemonSnapshot snapshot = adapter.snapshot(player);

        backendClient.syncParty(new PlayerSyncPartyRequest(
                player.getUuidAsString(),
                snapshot.party()
        ));

        backendClient.syncPokedex(new PlayerSyncPokedexRequest(
                player.getUuidAsString(),
                snapshot.pokedexEntries()
        ));
    }
}
