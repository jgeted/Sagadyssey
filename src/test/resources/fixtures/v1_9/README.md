# v1.9 migration fixtures

These are immutable copies of Sagadyssey `SavedData` files from three local test worlds:

- `worker`: 工人采集实验场
- `farmer`: 农民实验场
- `blacksmith`: 铁匠实验场

Only `sagadyssey*.dat` files are retained; chunks, player data and unrelated Minecraft state are intentionally excluded. `manifest.sha256` freezes the exact baseline. Migration tests must copy these resources to a temporary directory and must never modify the originals.
