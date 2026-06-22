# Trade Picker

**Pick the exact villager trades you want. No cycling. No luck.**

Trade Picker replaces the workstation-cycling grind with a simple choice screen. Right-click any villager and, instead of getting whatever trades the RNG decided, you see every trade that villager could offer at its current level — then you pick the ones you want. Your picks lock in permanently at the cheapest vanilla price, and reputation discounts from curing or Hero of the Village still stack on top.

It adds **no new items, blocks, or recipes**. Everything you can pick is something that villager could already sell in vanilla — you just choose it instead of rolling for it.

---

## How it works

1. Right-click a villager.
2. A picker grid lists every trade available at that level. Type in the search box to filter (`mending`, `fortune`, `looting`, `weakness`, `emerald`, any item name).
3. Choose your trades and hit **Confirm**. They become that villager's permanent trades, at vanilla's lowest price.
4. When the villager levels up — Novice → Apprentice → Journeyman → Expert → Master — the picker opens again for that tier.

You pick as many trades as vanilla actually grants at that level (usually two). When there's genuinely nothing to choose, it just opens the shop.

---

## What you can pick, by villager

### 🧑‍🏫 Librarian
- **Sells:** enchanted books.
- **You pick:** every tradeable enchantment, listed individually at every level — Mending, Sharpness I–V, Fortune III, Looting III, Silk Touch, Infinity, every Treasure enchantment. No more breaking and replacing lecterns to chase one book.

### ⚔️ Weaponsmith
- **Sells:** enchanted swords and axes.
- **You pick:** the enchantment on the weapon instead of letting vanilla roll it — Sharpness, Smite, Bane of Arthropods, Looting, Fire Aspect, Knockback, Sweeping Edge, Unbreaking.

### 🛡️ Armorer
- **Sells:** enchanted diamond armor (helmet, chestplate, leggings, boots).
- **You pick:** the protection you want — Protection, Fire / Blast / Projectile Protection, plus piece-specific options like Respiration and Aqua Affinity (helmets), Feather Falling and Depth Strider (boots), Thorns, and Unbreaking.

### ⛏️ Toolsmith
- **Sells:** enchanted diamond tools (pickaxe, axe, shovel, hoe).
- **You pick:** Efficiency, Fortune, Silk Touch, Unbreaking — grab Fortune III or Silk Touch directly without the reroll.

### 🏹 Fletcher
- **Sells:** enchanted bows and crossbows, and tipped arrows.
- **You pick:** bow enchantments (Power, Punch, Flame, Infinity, Unbreaking), crossbow enchantments (Quick Charge, Piercing, Multishot, Unbreaking), and **the potion on tipped arrows** — including Arrow of Weakness for curing zombie villagers.

### 🎣 Fisherman
- **Sells:** an enchanted fishing rod.
- **You pick:** Luck of the Sea, Lure, Unbreaking.

### 🌾 Every other profession (Farmer, Cleric, Butcher, Shepherd, Mason, Cartographer, Leatherworker, Wandering Trader…)
- **You pick:** which trades that villager offers at each level, straight from its full vanilla pool. The enchantment-choosing above only applies to professions that sell enchanted items; everyone else simply lets you choose their regular trades.

Everything stays inside what that villager could roll in vanilla — including the level limits. A villager won't offer an enchantment, or an enchantment level, that it couldn't normally produce at that tier. Data packs and modded enchantments are picked up automatically, because the picker reads the actual trade list registered for each profession.

---

## Choosing how enchanted gear works (optional config)

Books always list every enchantment individually. For enchanted **gear** (weapons, armor, tools, bows, rods) you choose how much control you want:

- **Headline mode (default):** pick the main enchantment you want; the game still rolls its level and any vanilla bonus enchantments — so a sword can still come with an extra perk, keeping vanilla's variety.
- **Single mode:** pick the exact enchantment *and* its level. You get only that.
- **Cost scaling (off by default):** turn it on per profession to make rarer and higher-level enchantments cost more emeralds, up to vanilla's price cap — a little balance so picking the best isn't free.

Configure it in **ModMenu** (Fabric) or by editing `config/tradeoptimizer.json` (works on both loaders). The same file holds the long-standing **Vanilla Pricing** and **Vanilla Book Limits** toggles.

---

## Always the lowest price

Every trade is offered at vanilla's minimum possible price. Curing a villager or earning Hero of the Village applies its discount on top, exactly like vanilla. Picks survive world reloads, server restarts, and updates, and are saved per villager.

---

## Multiplayer

On shared servers, each villager is claimed by the first player to interact with it. Only that player — or a server operator — can reset or change its trades, so your emerald shop stays yours. Players without the mod installed fall back to normal vanilla trading and get a one-time message explaining why.

---

## Reset anytime

Hit the **Reset** button on any trade screen to wipe a villager back to Novice and re-pick from scratch. A confirmation prompt keeps it from firing by accident.

---

## Compatibility

- **Loaders:** Fabric and NeoForge, Minecraft 26.2.
- Works with Sodium / Embeddium, Iris / Oculus, Lithium / Starlight, EMI / JEI / REI, and ModMenu.
- Compatible with data packs that add or change villager trades.
- **Heads-up:** don't install alongside another trade-cycling mod — you only need one, and running two can conflict.

---

## Install

1. Install **Fabric Loader** or **NeoForge** for Minecraft 26.2.
2. On Fabric, also install [Fabric API](https://modrinth.com/mod/fabric-api).
3. Drop the matching Trade Picker jar into your `mods` folder.
4. **On a server:** install on both the server and the client.

Right-click a villager and it just works — no setup required.

---

## Source & License

- GitHub: <https://github.com/polyvenom/tradepicker>
- License: CC0 1.0 Universal (public domain)
- Author: polyvenom
- Issues / suggestions: GitHub Issues

## Support

If Trade Picker saves you an afternoon of lectern-smashing, a tip on [Ko-fi](https://ko-fi.com/polyvenom) is always appreciated. No pressure — the mod is and always will be free.
