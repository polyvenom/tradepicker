# Trade Optimizer

In-merchant trade rating + targeted villager cycling for **Minecraft 26.1.2 (Fabric)**.

## What it does

When you right-click a villager and the trade GUI opens, Trade Optimizer:

- Draws a **colored rating chip** on each visible trade (Great / Good / Fair / Bad)
- On hover, shows a tooltip with the current emerald cost, the lowest price ever seen from that specific villager, and the vanilla baseline range
- Marks the chip with `*` and a "BEST ever" tooltip when the displayed price matches the lowest you've seen
- Lets you **cycle the villager's workstation** automatically until a specific trade rolls, then keep re-rolling for a cheaper price

## Keybinds

Default — rebind in **Controls → Trade Optimizer**:

| Key | Action |
|---|---|
| **Y** | Start cycling for the currently-selected trade (or re-roll once if already in FOUND state). |
| **Z** | Cancel an active cycle session. |

## How auto-cycle works

1. Trade with a villager — the mod silently snapshots their offers and history.
2. Pick the trade row you want.
3. Press **Y**. The server starts breaking and replacing the workstation block, waiting for the villager to re-roll trades each loop, up to `maxCycleAttempts` from config.
4. When the target trade appears, the cycle pauses in **FOUND** state — the status banner shows the price.
5. Press **Y** again to re-roll for a cheaper one. The `BEST` marker tells you when you're at the lowest price ever recorded.
6. Press **Z** to stop.

The cycle does NOT spoof timing or hide from anti-cheat. It uses normal `destroyBlock` and `setBlockAndUpdate` calls on a configurable cooldown — the same actions the player would do manually. Cycling is **off by default**; set `cyclingEnabled: true` in config to enable.

## Install

1. Drop `tradeoptimizer-x.y.z.jar` into your `mods` folder.
2. Requires **Fabric Loader 0.19.0+**, **Fabric API**, **Minecraft 26.1.2**, **Java 25**.
3. Both client AND server need the mod for cycling and rating overlays to work (single-player counts both sides on your machine).

## Config

`config/tradeoptimizer.json`:

| Key | Default | Description |
|---|---|---|
| `cyclingEnabled` | `false` | Master switch for auto-cycling. Off for safety on shared servers. |
| `cycleCooldownTicks` | `5` | Ticks between break and place phases. |
| `postPlaceWaitTicks` | `40` | Ticks to wait for the villager to re-roll after placement (2 seconds at default). |
| `maxCycleAttempts` | `200` | Hard cap on auto-cycle loops before giving up. |
| `maxKnownVillagers` | `512` | Cap on the persistent villager-history index. |
| `showMerchantOverlay` | `true` | Toggle rating chips. |
| `showMerchantTooltips` | `true` | Toggle hover tooltips. |

## Compatibility

- **Replaces `trade-cycling` (mrbysco's mod)** — uninstall it. Trade Optimizer's auto-cycle covers the same workstation flow and adds target detection + best-price tracking. If both are installed, the mod logs a warning on startup.
- Compatible with **Iris/Sodium**, **Lithium**, **ModMenu**, **YACL** — uses vanilla rendering primitives only, hooks the merchant screen via mixin which doesn't conflict with rendering or server tick mods.

## License

MIT — see [LICENSE](LICENSE).
