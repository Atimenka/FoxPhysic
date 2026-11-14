# Block property table

`build_block_table.py` turns a Minecraft 1.21.1 block dump into the human-readable table, CSV export, and per-block JSON resource used by the mod.

## Inputs and outputs

- `blocks-dump.tsv` — block identifiers and properties captured from the Minecraft 1.21.1 default block states by `BlockDump.java`.
- Game-provided values: hardness (`BlockState.getDestroySpeed`) and blast resistance (`Block.getExplosionResistance`).
- Estimated values: material density and fragility. Minecraft does not define these properties; the script assigns material rules and explicit overrides.

Regenerate the Fabric source-table outputs from this directory:

```bash
python3 build_block_table.py \
  --dump blocks-dump.tsv \
  --csv ../../docs/block-physics.csv \
  --md ../../docs/BLOCK_PHYSICS.md \
  --json ../../fabric/src/main/resources/assets/foxphys/block-physics.json
cp ../../fabric/src/main/resources/assets/foxphys/block-physics.json \
   ../../neoforge/src/main/resources/assets/foxphys/block-physics.json
```

Review the generated files before packaging. Check the entry count and compare numeric fields if the generator rules or dump changed. Keep the two loader JSON resources byte-identical.

## Refreshing the Minecraft dump

`BlockDump.java` must run with Minecraft 1.21.1 classes and its required loader/bootstrap classes on the classpath. Build that classpath from the current Gradle cache for the game version being targeted, compile with annotation processing disabled, then write a new TSV into `scripts/blocks/blocks-dump.tsv`. Do not reuse an environment-specific classpath file or temporary stubs after a Gradle-cache reset.

The table format and property meanings are described in [`docs/BLOCK_PHYSICS.md`](../../docs/BLOCK_PHYSICS.md). Density/fragility numbers are estimates and must not be presented as vanilla Minecraft values.
