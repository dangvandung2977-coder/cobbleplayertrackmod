# CobbleSyncBridge — Phase 3 source

Server-side Fabric bridge for **Minecraft 1.21.1 + Cobblemon 1.7.3**.

This repo contains the **Phase 3 source** for the bridge mod that:
- registers the server with the backend
- sends heartbeat updates
- upserts players on join
- periodically syncs party + pokedex data
- includes Pokémon stat extraction (IV / EV / current stats / HP)

## Important note

The project is intentionally built around a **reflection-based Cobblemon adapter** so the core bridge compiles without a hard compile-time dependency on Cobblemon internals.

That gives you two advantages:
- the infrastructure side is ready immediately
- the final Cobblemon extraction layer is localized to a small set of classes

## What is fully implemented
- config loading/writing
- backend HTTP client
- DTOs for all phase 1/2 contracts
- Fabric lifecycle hooks
- join/leave + tick scheduling
- manual resync commands
- repo wiki markdown pages
- phase 1/2/3 docs bundle

## What may need final adjustment on your machine
The class names and method names used by Cobblemon's party / pokedex internals can differ from what the reflection adapter expects.  
So the likely final touch-up area is:

- `cobblemon/CobblemonReflectionAdapter.java`

Everything else in the bridge is already separated and ready.

## Commands
- `/cobblesyncbridge status`
- `/cobblesyncbridge resync <player>`
- `/cobblesyncbridge inspect`

## Config
The mod writes:
- `config/cobblesyncbridge.json`

Fill in:
- `backendBaseUrl`
- `apiKey`
- `serverName`
- `serverDisplayIp`

## Recommended next step
1. import this project
2. run server once to generate config
3. fill config
4. start backend
5. start server again
6. use `/cobblesyncbridge inspect` if party/pokedex extraction needs minor method-name tuning

See `repo-wiki/` and `docs/` for the full workflow and notes.


## Stats support
The Phase 3 source now explicitly maps:
- `ivs`
- `evs`
- `stats`
- `hpCurrent`
- `hpMax`

The main implementation is in `CobblemonReflectionAdapter.java` and includes broader reflection fallbacks for different Cobblemon runtime shapes.
