package com.cobbleverse.cobblesyncbridge.backend.dto;

import java.util.List;

public record PlayerSyncPartyRequest(
        String playerUuid,
        List<PokemonPayload> party
) {}
