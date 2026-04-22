package com.cobbleverse.cobblesyncbridge.backend.dto;

public record PlayerUpsertRequest(
        String uuid,
        String name,
        String serverId,
        String skinUrl,
        Boolean isOnline
) {}
