# Phase 2 backend reference

The bridge expects these endpoints to exist:

POST
- /api/server/register
- /api/server/heartbeat
- /api/player/upsert
- /api/player/sync-party
- /api/player/sync-pokedex

GET
- /api/servers
- /api/servers/{id}/players
- /api/players/{uuid}
- /api/players/{uuid}/party
- /api/players/{uuid}/pokedex
