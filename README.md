# Trade Picker

**Pick the exact villager trades you want. No cycling. No luck.**

Trade Picker replaces the workstation-cycling grind with a direct choice screen. Right-click any villager and instead of getting whatever trades the RNG decided, you see every trade that villager could possibly offer at its current level — then you pick the two you want. Your picks lock in permanently at the cheapest possible price.

[Modrinth](https://modrinth.com/mod/trade-picker) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/trade-picker) · [Ko-fi](https://ko-fi.com/polyvenom)

---

## How it works

Right-click a villager. Instead of the standard merchant screen with whatever trades RNG assigned, you get a picker grid showing every trade that profession can offer at that level. Choose two, hit Confirm, and those trades are now that villager's permanent trades — always at vanilla's minimum price.

When the villager levels up, the picker opens again for the new tier. You stay in control all the way from Novice to Master.

**For librarians specifically:** the picker lists every tradeable enchantment at every level as its own card — Mending, Sharpness I through V, Fortune III, Silk Touch, every Treasure book — with a search box so you can filter by name. No more cycling through dozens of rerolls hoping for the right one.

---

## Features

- **Direct trade selection** — choose villager trades from a full list instead of rolling the dice
- **Librarian book picker** — every enchantment × level combination listed individually; search by name
- **Always minimum price** — all offers generated at vanilla's lowest possible cost; reputation discounts (curing, Hero of the Village) stack on top normally
- **Levels up with your villager** — picker opens at each new level (Novice → Apprentice → Journeyman → Expert → Master)
- **Restock works correctly** — picked trades restock on vanilla's normal schedule; villagers have to sleep and return to their workstation
- **Reset button** — wipe a villager back to Novice from any merchant screen (confirmation prompt included)
- **Multiplayer ownership** — first player to interact claims the villager; only the owner or a server op can reset or change trades
- **Server-only fallback** — players without the mod installed fall back to vanilla trading instead of a silent failure

---

## FAQ

**Does this work on servers?**  
Yes. Install on both server and client. If a player joins without the mod, they fall back to vanilla trading normally and get a one-time message explaining why.

**Will it conflict with my other mods?**  
It's compatible with Sodium, Iris, Lithium, EMI, JEI, REI, ModMenu (Fabric), and most other gameplay/utility mods on either loader. Don't install alongside other trade-cycling mods (VillagerTradefix, Trade Cycling, etc.) — they're solving the same problem a different way and will likely conflict.

**Do picks survive world reload / server restart?**  
Yes. Picks are saved per-villager in world data and survive restarts, chunk unloads, and updates.

**What happens to villagers I've already traded with before installing the mod?**  
Their existing trades are imported as-is and preserved. The picker only activates for unfilled levels going forward.

**Does it affect prices?**  
Prices are locked to vanilla's minimum roll. Curing a zombie villager or earning Hero of the Village discounts still apply on top of that, exactly as in vanilla.

**Can I choose villager trades in creative / single-player?**  
Yes — it works in any game mode.

**Is it compatible with data packs that add custom villager trades?**  
Yes. The picker enumerates the actual trade set registered for each profession and level, so custom trades from data packs appear in the picker alongside vanilla ones.

---

## Why not a trade cycling mod?

Trade cycling mods automate the workstation-breaking loop: they break and replace your lectern hundreds of times automatically until a librarian rolls the trade you want. It's faster than doing it by hand, but it's still RNG — you're just waiting on a machine instead of doing it yourself.

Trade Picker skips the RNG entirely. You choose which trades a villager gets directly. There's no cycling, no waiting, no probability involved. If you want Mending, you pick Mending. If you want Fortune III, you pick Fortune III. First try, every time.

---

## Install

Trade Picker runs on **Fabric** and **NeoForge** for Minecraft 26.1.2. Pick the jar that matches your loader.

### Fabric
1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.1.2
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `tradeoptimizer-fabric-<version>.jar` into your `mods` folder

### NeoForge
1. Install [NeoForge](https://neoforged.net/) 26.1.2 or newer in the 26.1 line
2. Drop `tradeoptimizer-neoforge-<version>.jar` into your `mods` folder

On a server: install on both server and client. Worlds carry over between loaders — the mod id and save data are identical, so a villager's picks survive moving the world from Fabric to NeoForge (or back). One small config file is optional (`config/tradeoptimizer.json`). Right-click a villager and it works.

---

## Building from source

```bash
git clone https://github.com/polyvenom/tradepicker.git
cd tradepicker
./gradlew build
# fabric jar:   fabric/build/libs/tradeoptimizer-fabric-<version>.jar
# neoforge jar: neoforge/build/libs/tradeoptimizer-neoforge-<version>.jar
```

Requires Java 25. Multi-module build: a `common` module holds the shared logic, the `fabric` module uses Fabric Loom, and the `neoforge` module uses ModDevGradle. Targets Fabric API `0.149.1+26.1.2` and NeoForge `26.1.2.75`.

---

## License

[CC0 1.0 Universal](LICENSE) — public domain. Do whatever you want with it.

**Author:** polyvenom · [Ko-fi](https://ko-fi.com/polyvenom) · [Issues](https://github.com/polyvenom/tradepicker/issues)
