# CobbleSyncBridge Wiki

## What this repo is
A server-side Fabric bridge for Cobblemon tracking.

## Primary purpose
Send server, player, party, and Pokédex data from Minecraft to the backend so the web dashboard can show:

- tracked servers
- players per server
- party details
- Pokédex progress

## Repo layout
- `src/main/java/.../config` → config loading
- `src/main/java/.../backend` → API client + DTOs
- `src/main/java/.../sync` → heartbeat and player sync flow
- `src/main/java/.../cobblemon` → Cobblemon extraction layer
- `src/main/java/.../command` → admin/debug commands
- `docs/` → phase notes/specs

## Most important files
- `CobbleSyncBridgeMod.java`
- `sync/SyncCoordinator.java`
- `cobblemon/CobblemonReflectionAdapter.java`
