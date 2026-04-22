# Troubleshooting

## Server registers but players do not sync
Check:
1. `serverId` is being stored after register
2. backend auth key is correct
3. online players are being swept
4. bridge logs show party/pokedex extraction warnings

## Party sync empty
Likely adjustment point:
- `CobblemonReflectionAdapter.findPartyStore`

## Pokédex sync empty
Likely adjustment point:
- `CobblemonReflectionAdapter.findPokedexContainer`

## What to do first
Run:
- `/cobblesyncbridge inspect`

Then inspect log output to see the available methods on the Cobblemon singleton/storage objects.
