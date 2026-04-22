package com.cobbleverse.cobblesyncbridge.backend.dto;

public record PokedexEntryPayload(
        String species,
        Integer dexNumber,
        boolean unlocked,
        boolean caught,
        Boolean seen
) {}
