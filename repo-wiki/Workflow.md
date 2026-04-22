# Workflow

## Runtime flow
1. server starts
2. bridge registers server
3. heartbeat loop begins
4. player joins
5. bridge upserts player
6. scheduled sweep queues sync
7. bridge sends:
   - player upsert
   - party payload
   - pokedex payload

## Build flow
1. Phase 1 = schema + API spec
2. Phase 2 = backend
3. Phase 3 = bridge mod
4. Phase 4 = web dashboard
