
# Phase 2 — backend build spec (summary)

## Goal
Build the backend before the Fabric bridge and before the web.

## Why
- the mod needs somewhere to send data
- the web needs somewhere to read data
- the backend is the contract center

## Required stack
- FastAPI
- PostgreSQL
- SQLAlchemy
- Alembic

## Required POST endpoints
- /api/server/register
- /api/server/heartbeat
- /api/player/upsert
- /api/player/sync-party
- /api/player/sync-pokedex

## Required GET endpoints
- /api/servers
- /api/servers/{id}/players
- /api/players/{uuid}
- /api/players/{uuid}/party
- /api/players/{uuid}/pokedex

## Required tables
- servers
- players
- player_party
- player_pokedex

## Core backend rules
- use bearer API key auth for mod requests
- validate every incoming payload
- keep party and pokedex sync independent
- use UUID as player identity key
