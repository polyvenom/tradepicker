# Trade Optimizer

A villager trading companion for Minecraft 26.1.2 (Fabric).

## What it does

- Remembers every villager you've interacted with, even after they unload.
- Rates each trade Great / Good / Fair / Bad based on baseline price ranges.
- **Trade Index tab** — search by item or enchantment, jump to coordinates of a villager selling Mending, paper-for-emerald specials, etc.
- **Profession Planner tab** — totals of assigned villagers per job and a count of vacant workstations near you.
- Optional **trade cycling helper** for novice villagers (off by default; see below).

Default keybind: **V** (rebindable under Controls → Trade Optimizer).

## Install

1. Put `tradeoptimizer-x.y.z.jar` in your `mods` folder.
2. Requires Fabric Loader 0.19.2+ and Fabric API.
3. For multiplayer, the server must also have this mod installed for tracking and cycling to work.

## Build targets

- **`master` branch** — Minecraft 1.21.11 + Yarn mappings. This is what builds today; the jar is for 1.21.11 worlds.
- **`mojang-26.1.2-prep` branch** — full Mojang-mapped rewrite ready for 26.1.2. Blocked: Mojang hasn't published `client_mappings` for 26.1.2 on piston-meta, and Parchment doesn't ship 26.1.2 yet either. When either lands, merge the branch and the build resolves.

## Configuration

Config file: `config/tradeoptimizer.json`

| Key | Default | Description |
|---|---|---|
| `cyclingEnabled` | `false` | Master switch for the trade-cycling helper. |
| `cycleCooldownTicks` | `5` | Ticks between break and replace phases of a cycle. |
| `maxKnownVillagers` | `512` | Hard cap on the tracked-villager index. |
| `requireOpToCycle` | `true` | Only operators may issue cycle requests. |

## A note on trade cycling

The cycling helper is a quality-of-life feature for **singleplayer worlds and private servers where you control the rules**. It does NOT spoof timing, hide packets, or attempt to evade anti-cheat. It uses a normal custom mod channel: the server has to have this mod installed, and the admin has to enable `cyclingEnabled` in config. On servers that didn't opt in, the request packet is simply rejected — there is no fallback path.

If you're playing on a public server, ask the owner before turning this on.

## License

MIT — see [LICENSE](LICENSE).
