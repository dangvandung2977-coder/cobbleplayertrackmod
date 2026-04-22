package com.cobbleverse.cobblesyncbridge.backend.dto;

public record ServerRegisterResponse(
        String serverId,
        String name,
        Boolean registered
) {}
