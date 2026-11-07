# Raytrace API

Raytrace exposes a small Java API (`com.heonezen.raytrace.api.RaytraceAPI`) so other plugins can
ask what a given viewer can currently see, reveal terrain for a viewer, or reset a player's
visibility. This document describes how to use it.

## Adding the dependency

### In your `plugin.yml`

Load Raytrace before (or at least together with) your plugin, and check its presence at
runtime:

```yaml
name: MyPlugin
main: com.example.MyPlugin
softdepend:
  - Raytrace
```

### On the compile classpath

Put the `Raytrace-<version>.jar` from the Raytrace release on your compile classpath. The API
classes live in `com.heonezen.raytrace.api`. No extra dependencies are needed: everything the
API takes or returns are standard Bukkit types.

## Getting the API

```java
import com.heonezen.raytrace.api.RaytraceAPI;

RaytraceAPI api = RaytraceAPI.get();
if (api == null) {
    // Raytrace is not enabled; your plugin should keep working without it.
    return;
}
// or: if (RaytraceAPI.isAvailable()) { ... }
```

`get()` returns `null` unless Raytrace is enabled, so always null-check before use (or guard
with `isAvailable()`). The instance is registered by `RaytracePlugin` on enable and cleared
on disable.

## Methods

### Reading visibility (safe from any thread)

| Method | Returns |
| --- | --- |
| `boolean isBypassed(Player)` | Viewer is exempt from the protection (spectator mode or the `raytrace.bypass` permission, cached; permission changes take effect within a couple of seconds). |
| `boolean isBlockVisible(Player viewer, int x, int y, int z)` | The block's terrain cell is currently revealed for the viewer. |
| `boolean isBlockHidden(Player viewer, int x, int y, int z)` | The block is currently masked for the viewer. |
| `boolean isEntityHidden(Player viewer, Entity entity)` | The entity is currently withheld from the viewer. |
| `boolean isEntityVisible(Player viewer, Entity entity)` | The entity is currently visible to the viewer. |
| `boolean isWorldProtected(World world)` | The protection is enabled in this world. |

### Actions (thread-sensitive)

| Method | Notes |
| --- | --- |
| `void forceRevealBlock(Player viewer, int x, int y, int z)` | Reveals the terrain cell containing the block to the viewer immediately, e.g. after the viewer edits it. **Call on the thread owning the player** (Folia: the player's region thread). |
| `void resetPlayer(Player player)` | Re-evaluates everything for the viewer, the same as Raytrace does on teleport/respawn. **Call on the thread owning the player.** |

## Examples

### Hide a nameplate / interaction only when Raytrace would hide the entity

```java
@EventHandler
public void onInteract(PlayerInteractEntityEvent event) {
    RaytraceAPI api = RaytraceAPI.get();
    if (api != null && !api.isBypassed(event.getPlayer())
            && api.isEntityHidden(event.getPlayer(), event.getRightClicked())) {
        event.setCancelled(true);
        event.getPlayer().sendActionBar(Component.text("There is nothing there."));
    }
}
```

### Show a custom block hint only for viewers who can actually see the block

```java
RaytraceAPI api = RaytraceAPI.get();
boolean canSee;
if (api == null || !api.isWorldProtected(block.getWorld())) {
    canSee = true; // no protection to ask about
} else {
    canSee = api.isBlockVisible(viewer, block.getX(), block.getY(), block.getZ());
}
```

### Reveal a block a player just mined underground

```java
@EventHandler
public void onBreak(BlockBreakEvent event) {
    RaytraceAPI api = RaytraceAPI.get();
    if (api != null) {
        api.forceRevealBlock(event.getPlayer(),
                event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
    }
}
```

## Notes

- **Shaded vs. plain jar.** If you shade the Raytrace jar into your plugin, exclude the
  `com/heonezen/raytrace/api/` classes from shading (or keep them unrelocated) so that the
  types your plugin calls are the same as the type Raytrace registers.
- **Compatibility.** The API is designed for the supported range (Minecraft 1.21.4+ and the
  26.x line). Method signatures use only Bukkit types, so they do not depend on NMS internals.
- **Semantics of "visible".** Raytrace hides entities with a short grace period: an entity
  that became occluded is kept visible for 0.5 seconds so briefly ducking behind a corner does
  not flicker it, and underground (hidden-terrain) hides are immediate. A `false` result for
  `isEntityHidden` therefore means "currently/briefly visible", and a spawn of a hidden entity
  never reaches the client in the first place.
