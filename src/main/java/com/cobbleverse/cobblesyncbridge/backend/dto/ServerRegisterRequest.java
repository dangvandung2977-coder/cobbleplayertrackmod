package com.cobbleverse.cobblesyncbridge.backend.dto;

public record ServerRegisterRequest(
        String name,
        String ip,
        String mcVersion,
        String cobblemonVersion,
        String modVersion
) {}
