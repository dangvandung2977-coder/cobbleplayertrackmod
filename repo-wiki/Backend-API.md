# Backend API

The bridge expects:

## POST
- `/api/server/register`
- `/api/server/heartbeat`
- `/api/player/upsert`
- `/api/player/sync-party`
- `/api/player/sync-pokedex`

## Auth
All bridge requests send:

```http
Authorization: Bearer <api-key>
```

## Expected Phase 2 behavior
- register returns `serverId`
- heartbeat refreshes `lastSeen`
- player upsert is idempotent
- party sync replaces latest snapshot
- pokedex sync updates unlocked/caught state


## Party payload fields
Party sync is expected to include these stat fields when available:
- `ivs`
- `evs`
- `stats`
- `hpCurrent`
- `hpMax`
