# Config

Example:

```json
{
  "serverName": "Cobbleverse Alpha",
  "serverDisplayIp": "play.cobbleverse.net",
  "backendBaseUrl": "http://127.0.0.1:8000",
  "apiKey": "CHANGE_ME",
  "heartbeatIntervalTicks": 600,
  "playerSyncIntervalTicks": 1200,
  "maxPlayersPerTick": 4,
  "verboseLogging": false
}
```

## Tuning
- `heartbeatIntervalTicks = 600` means every 30s
- `playerSyncIntervalTicks = 1200` means every 60s
- `maxPlayersPerTick` limits burst load
