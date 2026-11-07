## BLOCK GRID (grids.blocks)

The world is split into cubes of "size" x "size" x "size" blocks, called cells.
Below the configured layer cutoff, every block is sent to the player as fake stone
(or deepslate, netherrack, end stone) unless the player has "earned" that cell.

A cell is earned when one of the player's rays touches it:
  - Rays start at the player's eyes and fan out in a fixed pattern
    (target-total-rays = how many rays make up the full pattern).
  - A ray stops after passing through occlusion-grace-blocks solid blocks, so the
    player cannot earn cells far behind a wall.
  - When a cell is earned, the whole cell becomes real for that player only.
  - Cells that fall outside the player's view distance are forgotten and become
    fake again.

When it runs:
  - A full scan runs at most once every scan-ticks, and only if the player moved
    more than move-threshold-blocks or turned more than the yaw/pitch thresholds.
  - Five rays along the view direction run on every tick while the player moves or
    turns, so whatever you are looking at appears immediately.

Limits per scan (so one player can never stall the server):
  - max-scan-micros: time budget for one scan of one player.
  - max-steps-per-scan: maximum ray steps.
  - max-revealed-blocks-per-scan: maximum blocks revealed in one scan.
  If a scan runs out of budget, it stops and the next scan continues with the
  remaining rays and cells.

Size trade-off:
  - Bigger cells = fewer updates and less CPU, but a revealed cell exposes more
    real blocks around the point you looked at.
  - Smaller cells = more precise hiding, but more updates and more CPU.


## ENTITY GRID (grids.entities)

Every scan-ticks, entities inside scan-radius-blocks of the player are checked.
For each entity:
  1. One ray goes from the player's eyes to the centre of the entity. If it
     arrives, the entity is visible and the check ends (cost: 1 ray).
  2. Only if the centre is blocked, the plugin tests a grid of "size" x "size"
     sample points spread over the entity's body (size 5 = up to 25 rays). If any
     point is reachable, the entity is visible, so a mob peeking around a corner
     is still shown.
  3. If every point is blocked, or the entity is inside terrain the player has
     not earned, the entity is hidden.

occlusion-blocks is how many solid blocks may lie between the eye and a sample
point before that point counts as blocked.

Hidden entities are not sent to the player at all (spawn, movement, metadata and
sounds are dropped). When they become visible, the plugin re-sends them.

An entity is not traced again if neither it nor the player moved and no new cell
was earned; the previous verdict is reused for up to 40 ticks.

Size trade-off:
  - Bigger size = smoother partial visibility, more CPU in the worst case
    (fully hidden entities).
  - Smaller size = cheaper, but entities popping out from behind corners appear
    a little later.
