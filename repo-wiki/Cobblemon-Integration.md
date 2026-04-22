# Cobblemon Integration

## Current strategy
This source uses a **reflection adapter** instead of a hard-coded direct internals binding.

## Why
Cobblemon internals and mappings can move between versions.
Keeping the bridge modular makes maintenance easier.

## Main extraction file
- `cobblemon/CobblemonReflectionAdapter.java`

## What it currently does
- tries to find Cobblemon singleton runtime
- tries to find party access methods
- tries to find pokedex access methods
- maps Pokémon through JSON save data and reflection fallback

## If it needs tweaking
Use:
- `/cobblesyncbridge inspect`

Then adjust:
- method-name predicates in `findPartyStore`
- method-name predicates in `findPokedexContainer`


## Stats extraction
The adapter now tries multiple fallback paths for Pokémon stats:
- JSON save data
- map-like stat containers
- stat objects with getters
- direct Pokémon getter fallbacks

This is mainly used for:
- IV
- EV
- current stats
- HP current / max
