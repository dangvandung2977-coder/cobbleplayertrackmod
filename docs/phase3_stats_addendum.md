# Phase 3 Stats Addendum

This addendum documents the extra stat fields explicitly extracted by the bridge.

## Added / emphasized fields
- `ivs`
- `evs`
- `stats`
- `hpCurrent`
- `hpMax`

## Why
These fields are useful for:
- detailed player profile pages
- party inspection
- future team-analysis features
- web modals showing full Pokémon breakdown

## Extraction strategy
The bridge now attempts, in order:
1. save-to-JSON extraction
2. map-like stat containers
3. stat objects with getters/fields
4. direct Pokémon getter fallbacks

## Notes
Because the integration is reflection-based, exact runtime method names can still vary. The adapter has been widened to make common Cobblemon runtime shapes easier to support without hard-coding one exact internal mapping.
