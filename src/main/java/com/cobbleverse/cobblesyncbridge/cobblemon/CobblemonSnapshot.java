package com.cobbleverse.cobblesyncbridge.cobblemon;

import com.cobbleverse.cobblesyncbridge.backend.dto.PokedexEntryPayload;
import com.cobbleverse.cobblesyncbridge.backend.dto.PokemonPayload;

import java.util.List;

public record CobblemonSnapshot(
        List<PokemonPayload> party,
        List<PokedexEntryPayload> pokedexEntries
) {}
