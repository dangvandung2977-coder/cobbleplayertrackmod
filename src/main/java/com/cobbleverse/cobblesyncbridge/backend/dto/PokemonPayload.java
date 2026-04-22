package com.cobbleverse.cobblesyncbridge.backend.dto;

import java.util.List;

public record PokemonPayload(
        int slot,
        String species,
        Integer dexNumber,
        String nickname,
        Integer level,
        String gender,
        String nature,
        String ability,
        String heldItem,
        String form,
        boolean shiny,
        List<String> moves,
        StatBlockPayload ivs,
        StatBlockPayload evs,
        StatBlockPayload stats,
        Integer hpCurrent,
        Integer hpMax
) {}
