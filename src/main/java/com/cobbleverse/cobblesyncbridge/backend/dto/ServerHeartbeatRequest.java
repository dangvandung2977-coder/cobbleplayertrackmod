package com.cobbleverse.cobblesyncbridge.backend.dto;

public record ServerHeartbeatRequest(
        String serverId,
        Integer onlinePlayerCount
) {}
