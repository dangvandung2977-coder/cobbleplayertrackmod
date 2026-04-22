package com.cobbleverse.cobblesyncbridge.config;

public record BridgeConfig(
        String serverName,
        String serverDisplayIp,
        String backendBaseUrl,
        String apiKey,
        int heartbeatIntervalTicks,
        int playerSyncIntervalTicks,
        int maxPlayersPerTick,
        boolean verboseLogging
) {
    public static BridgeConfig defaults() {
        return new BridgeConfig(
                "Cobblemon Server",
                "play.example.net",
                "http://127.0.0.1:8000",
                "CHANGE_ME",
                20 * 30,
                20 * 60,
                4,
                false
        );
    }
}
