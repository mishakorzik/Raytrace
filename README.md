# Raytrace

Raytrace is a self-contained packet-level anti-ESP and anti-freecam plugin for **Paper, Folia
and Canvas 1.21.4+ / 26.x**. It does not add blocks, commands for players, or dependencies; it
rewrites the packets already sent to each client.

## What it does

- **Terrain masking.** Blocks below the real surface are replaced in chunk packets with
  configured fake blocks (stone, deepslate, netherrack, end stone). A player earns cells by
  looking at them: a small ray pattern traces what they can legitimately see and sends the real
  blocks back for those cells only. The natural surface layer stays real so beaches, oceans,
  ice, trees and cave entrances do not turn into a flat stone world. Ores, chests, spawners and
  other valuable blocks inside that visual surface buffer are still masked until earned.
- **Entity hiding.** Mobs, players, **minecarts (including chest, hopper, furnace and TNT
  minecarts), boats, item frames, paintings, armor stands and dropped items** are hidden when every
  sampled ray from the eye to their silhouette is blocked by a wall or by terrain the viewer still
  sees as fake. The centre ray is checked first, so a plainly visible entity costs one ray instead
  of the full sample grid. Each kind has its own switch (`hide-mobs`, `hide-players`,
  `hide-vehicles`, `hide-decorations`, `hide-items`).
- **No spawn-then-remove.** The server introduces an entity to a client as one bundle (spawn,
  metadata, equipment, attributes). Raytrace looks inside the bundle, so an entity that is hidden
  at that moment is never announced to the client at all: no spawn packet, and no remove packet
  later. When it becomes visible the server's own tracker introduces it with its current state.
- **Effects and sounds.** Particles, block animations and distant sounds whose origin is hidden
  are dropped. Important sounds (explosions, ender dragon, wither, end crystal, portals) are
  always allowed, and explosions keep their knockback. Hidden chests and other block entities
  have their block-entity data suppressed as well.
- **Cross-version packet access.** Server internals are accessed reflectively. A missing lookup
  disables only that feature instead of breaking the connection or the plugin.

## Compatibility

- Paper, Folia or forks **1.21.4+ / 26.x**.
- Java 21+; Canvas 26.x needs Java 25.
- No other plugin, library or protocol library is required. The built jar is plain and
  self-contained.

## Commands and permissions

| Command | Permission | Purpose |
| --- | --- | --- |
| `/raytrace reload` | `raytrace.admin` | Reload `config.yml`, clear caches and rescan players. |

- `raytrace.bypass` is **not granted to operators by default**. Players with it bypass masking.
- Spectators are exempt automatically.
- `hide-effects: true` controls particles, animations and minor sounds.
- Block updates are always kept consistent with the terrain mask, regardless of `hide-effects`.

## How the mask is built

For every loaded chunk, Raytrace resolves a per-column ceiling from the configured layer bands
and the live height map. Everything below that ceiling is encoded as the fake block for that Y
band. `surface-buffer` keeps the top few blocks of natural soil real for a smooth coastline and
tree line. A second, un-buffered cutoff is used for ores, chests, spawners and block entities, so
with a fine grid (for example `grids.blocks.size: 2`) a shallow ore four blocks below the
surface is still hidden even when the surrounding dirt is real. Lower the buffer if you want an
even stricter surface.

When a player looks at a cell, the ray walk records the grid cells it passes through. Cells
within the occlusion allowance are marked visible for that player. The real block data for
those cells is sent with a normal multi-block change, so the client keeps correct collision and
does not see fake blocks where it has line of sight.

## Configuration notes

- `worlds.<world>.surface-buffer` is the number of blocks of **natural soil** above the cutoff
  that stay real. It is not a radius. `8` is a good visual default; smaller values hide more,
  larger values show more natural surface. Ores and containers inside the buffer remain hidden
  until their cell is earned. Negative disables surface awareness.
- `grids.blocks.size` is the terrain cell size. `10` is cheap and coarse; `2` is precise and
  much more update-heavy. A cell becomes fully real when earned, so a coarse grid also reveals
  a larger area around the player. Use `2` when precise hiding of very shallow ores matters.
- `grids.blocks.scan-ticks` is the minimum delay between full terrain scans. `1` means every
  tick. All timing in this config is in ticks, not milliseconds. Old
  `refresh-interval-millis` values are converted automatically (50 ms = 1 tick).
- `grids.entities.size` is the entity silhouette sample grid: `5` means up to 5x5 = 25 thin
  rays. The centre ray short-circuits visible entities. Smaller is faster but has coarser edges.
- `grids.entities.scan-ticks` is how often hidden living entities are rechecked.
- `grids.entities.scan-radius-blocks` should be at least the server's entity tracking range.
- `grids.entities.occlusion-blocks` is the number of solid blocks an entity ray may pass
  through. `1` hides behind one wall; higher values keep partially covered entities visible.
- `hide-effects` and `far-sound-distance` control particles, animations and minor sounds.

Performance:

- Reflection results (including "no such field") are cached; before, every movement packet of
  every entity cost several exceptions with stack traces on the Netty threads.
- Rays read block data straight from the server chunk (no Bukkit block object per step).
- Scans have a time budget (`max-scan-micros`) and resume where they stopped; nothing runs while
  the player stands still.
- Entity verdicts are reused while nothing moved; the entity list is filtered while it is
  collected; entity data is read through method handles.
- Revealed terrain is built from server block states and blocks that already match the fake block
  are not sent. Cells are pruned per chunk column instead of by scanning every earned cell.
- Unchanged chunk sections are copied byte for byte instead of re-encoded.
- The permission lookup runs every 2 seconds instead of every tick.

If your server already has a `config.yml`, the new defaults are **not** applied to it: set
`scan-ticks: 2`, `target-total-rays: 1600`, `max-steps-per-scan: 80000` and add
`max-scan-micros: 1000` (or delete the file and let it regenerate). The new `hide-*` keys default
to `true` when missing.

## Performance and bandwidth

- **Bandwidth:** masks are single-value palettes, so fully masked chunk sections usually encode
  smaller than real terrain. Chunks are still sent; Raytrace does not skip them. Hidden entities
  also drop their spawn, movement, equipment and metadata packets. Expect a useful reduction
  in busy areas, not a fixed percentage, because the saving depends on terrain and entity mix.
- **Client/render load:** hidden entities are not rendered, and uniform fake sections are
  simpler than detailed underground sections. The client still receives and renders the chunks
  themselves.
- **Server load:** the plugin adds ray scans and chunk rewriting. Terrain scans are budgeted and
  throttled by `scan-ticks`; entity checks are throttled separately. On a very busy server,
  raise `grids.blocks.scan-ticks`, lower `grids.entities.scan-ticks` frequency (higher number),
  or use a slightly smaller entity grid. The defaults are tuned to be conservative.
