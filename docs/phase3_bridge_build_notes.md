# Phase 3 — bridge source notes

This bundle contains the **Phase 3 Fabric bridge source**.

## What this phase is responsible for
- register tracked server with backend
- send heartbeat updates
- upsert players as they join/leave
- periodically sync player party + pokedex

## Why the bridge is split this way
The bridge code is intentionally separated into:
- `config/` → settings only
- `backend/` → pure HTTP + DTO layer
- `sync/` → lifecycle + scheduling
- `cobblemon/` → Cobblemon-specific extraction
- `command/` → admin/debug commands

That means if Cobblemon internals move, you mostly only touch:
- `cobblemon/CobblemonReflectionAdapter.java`

## Why reflection was chosen here
Because Cobblemon internal access points can move across versions and mappings.
With this layout:
- the mod infrastructure stays stable
- the last-mile extraction logic is isolated
- you can inspect runtime methods with `/cobblesyncbridge inspect`

## Practical expected flow
1. server boots
2. bridge registers server to backend
3. heartbeat runs every configured interval
4. online players are swept every configured interval
5. each player gets:
   - upsert
   - party sync
   - pokedex sync

## First file to check if data does not appear
- `config/cobblesyncbridge.json`
- then `cobblemon/CobblemonReflectionAdapter.java`


## Stats update
This revision also broadens stat extraction so the bridge sends `ivs`, `evs`, `stats`, `hpCurrent`, and `hpMax` whenever they can be resolved from Cobblemon runtime objects or save JSON.
