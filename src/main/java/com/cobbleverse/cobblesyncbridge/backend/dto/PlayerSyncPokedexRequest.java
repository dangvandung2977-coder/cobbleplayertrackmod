package com.cobbleverse.cobblesyncbridge.backend.dto;

import java.util.List;

public record PlayerSyncPokedexRequest(
        String playerUuid,
        List<PokedexEntryPayload> entries
) {}
