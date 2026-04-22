package com.cobbleverse.cobblesyncbridge.backend;

import com.cobbleverse.cobblesyncbridge.CobbleSyncBridgeMod;
import com.cobbleverse.cobblesyncbridge.backend.dto.*;
import com.cobbleverse.cobblesyncbridge.config.BridgeConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackendClient {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration BACKOFF_DURATION = Duration.ofSeconds(60);
    private static final int FAILURES_BEFORE_BACKOFF = 2;

    private final BridgeConfig config;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private volatile String knownServerId;
    private int consecutiveFailures;
    private long backoffUntilMillis;

    public BackendClient(BridgeConfig config) {
        this.config = config;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cobblesyncbridge-http");
            t.setDaemon(true);
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(this.executor)
                .build();
    }

    public CompletableFuture<String> registerServer(ServerRegisterRequest request) {
        return post("/api/server/register", request)
                .thenApply(body -> {
                    try {
                        ServerRegisterResponse response = GSON.fromJson(body, ServerRegisterResponse.class);
                        if (response != null && response.serverId() != null && !response.serverId().isBlank()) {
                            this.knownServerId = response.serverId();
                            return response.serverId();
                        }
                    } catch (Exception e) {
                        CobbleSyncBridgeMod.LOGGER.error("[CobbleSyncBridge] Failed to parse register response: {}", body, e);
                    }
                    return null;
                });
    }

    public CompletableFuture<Void> sendHeartbeat(ServerHeartbeatRequest request) {
        return post("/api/server/heartbeat", request).thenAccept(ignore -> {});
    }

    public CompletableFuture<Void> upsertPlayer(PlayerUpsertRequest request) {
        return post("/api/player/upsert", request).thenAccept(ignore -> {});
    }

    public CompletableFuture<Void> syncParty(PlayerSyncPartyRequest request) {
        return post("/api/player/sync-party", request).thenAccept(ignore -> {});
    }

    public CompletableFuture<Void> syncPokedex(PlayerSyncPokedexRequest request) {
        return post("/api/player/sync-pokedex", request).thenAccept(ignore -> {});
    }

    public String knownServerId() {
        return knownServerId;
    }

    public void setKnownServerId(String knownServerId) {
        this.knownServerId = knownServerId;
    }

    public void shutdown() {
        this.executor.shutdownNow();
    }

    private CompletableFuture<String> post(String path, Object payload) {
        long remainingBackoffMillis = remainingBackoffMillis();
        if (remainingBackoffMillis > 0) {
            if (config.verboseLogging()) {
                CobbleSyncBridgeMod.LOGGER.info(
                        "[CobbleSyncBridge] Skipping POST {} while backend is in backoff for {}s",
                        path,
                        Math.max(1, remainingBackoffMillis / 1000)
                );
            }
            return CompletableFuture.failedFuture(new IllegalStateException("Backend is in temporary backoff"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String body = GSON.toJson(payload);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(endpoint(path))
                        .header("Authorization", "Bearer " + config.apiKey())
                        .header("Content-Type", "application/json")
                        .timeout(REQUEST_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    recordSuccess();
                    throw new BackendResponseException("HTTP " + status + " from " + path + ": " + response.body());
                }
                if (config.verboseLogging()) {
                    CobbleSyncBridgeMod.LOGGER.info("[CobbleSyncBridge] POST {} ok", path);
                }
                recordSuccess();
                return response.body();
            } catch (BackendResponseException e) {
                CobbleSyncBridgeMod.LOGGER.warn("[CobbleSyncBridge] POST {} failed: {}", path, e.getMessage());
                throw e;
            } catch (HttpTimeoutException e) {
                CobbleSyncBridgeMod.LOGGER.warn(
                        "[CobbleSyncBridge] POST {} timed out after {}s. Check backendBaseUrl={} and backend health.",
                        path,
                        REQUEST_TIMEOUT.toSeconds(),
                        config.backendBaseUrl()
                );
                if (config.verboseLogging()) {
                    CobbleSyncBridgeMod.LOGGER.warn("[CobbleSyncBridge] Timeout cause for POST {}: {}", path, e.toString());
                }
                recordFailure(path);
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                CobbleSyncBridgeMod.LOGGER.warn("[CobbleSyncBridge] POST {} interrupted", path);
                throw new RuntimeException(e);
            } catch (IOException e) {
                CobbleSyncBridgeMod.LOGGER.warn("[CobbleSyncBridge] POST {} failed: {}", path, e.toString());
                if (config.verboseLogging()) {
                    CobbleSyncBridgeMod.LOGGER.error("[CobbleSyncBridge] POST {} failure details", path, e);
                }
                recordFailure(path);
                throw new RuntimeException(e);
            } catch (Exception e) {
                CobbleSyncBridgeMod.LOGGER.error("[CobbleSyncBridge] POST {} failed", path, e);
                recordFailure(path);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    private synchronized long remainingBackoffMillis() {
        long remaining = backoffUntilMillis - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    private synchronized void recordSuccess() {
        consecutiveFailures = 0;
        backoffUntilMillis = 0;
    }

    private synchronized void recordFailure(String path) {
        consecutiveFailures++;
        if (consecutiveFailures < FAILURES_BEFORE_BACKOFF) {
            return;
        }

        backoffUntilMillis = System.currentTimeMillis() + BACKOFF_DURATION.toMillis();
        CobbleSyncBridgeMod.LOGGER.warn(
                "[CobbleSyncBridge] Backend unreachable after {} failed requests; pausing HTTP sync for {}s. Last failed endpoint={}, backendBaseUrl={}",
                consecutiveFailures,
                BACKOFF_DURATION.toSeconds(),
                path,
                config.backendBaseUrl()
        );
        consecutiveFailures = 0;
    }

    private URI endpoint(String path) {
        String baseUrl = config.backendBaseUrl();
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(normalizedBase + normalizedPath);
    }

    private static final class BackendResponseException extends RuntimeException {
        private BackendResponseException(String message) {
            super(message);
        }
    }
}
