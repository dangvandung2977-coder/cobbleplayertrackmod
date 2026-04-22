# Architecture

```txt
Minecraft Server
  -> Fabric lifecycle events
  -> SyncCoordinator
  -> BackendClient
  -> FastAPI backend
  -> PostgreSQL
  -> Next.js web
```

## Why this is split
### Fabric mod
Reads data from the live game/server.

### Backend
Stores, validates, and serves data.

### Web
Only renders backend data. It never touches Minecraft data directly.
