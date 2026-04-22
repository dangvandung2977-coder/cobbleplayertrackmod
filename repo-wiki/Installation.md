# Installation

## Requirements
- Java 21
- Minecraft 1.21.1
- Fabric Loader
- Fabric API
- Cobblemon 1.7.3
- Phase 2 backend running

## Steps
1. build the bridge jar from this source
2. place it in the server `mods/` folder
3. start the server once
4. edit `config/cobblesyncbridge.json`
5. restart the server
6. verify backend receives `server/register`

## Config fields
- `serverName`
- `serverDisplayIp`
- `backendBaseUrl`
- `apiKey`
- `heartbeatIntervalTicks`
- `playerSyncIntervalTicks`
- `maxPlayersPerTick`
- `verboseLogging`
