# Trade Picker

Stop breaking lecterns.

In vanilla Minecraft, securing the villager trades you want requires a tedious, non-skill-based loop: placing and breaking a workstation repeatedly until the RNG generates the desired result. If you are going to minmax your trades anyway, the workstation-breaking loop is simply a time sink. 

Unlike trade-cycling mods that automate the dice roll, **Trade Picker** eliminates the RNG entirely. It allows you to directly select the exact vanilla trades you want the first time, every time.

## How It Works

Right-click a villager and, instead of the standard random trade screen, you will be presented with a picker interface. This interface displays every trade that villager could possibly offer at its current level, all set to the lowest vanilla price. 

Choose two, confirm, and those become the villager's permanent trades. No breaking workstations, no re-rolling, no luck.

### Key Features

*   **Direct Selection:** Every time a villager levels up (Novice, Apprentice, Journeyman, Expert, Master), you pick its next two trades.
*   **Fully Expanded Enchantment Support:** For Librarians, the picker lists *every* tradeable enchantment at every level (e.g., Mending, Sharpness I–V, Fortune III) as its own distinct option.
*   **Search Functionality:** Filter the list instantly. Type "mending", "sharp", or "emerald" to find what you need without scrolling through hundreds of books.
*   **Best Vanilla Pricing:** Prices are hardcoded to vanilla's absolute minimum roll. In-game reputation discounts (Curing, Hero of the Village) still apply normally on top of these base prices.
*   **Reset Button:** A built-in reset option in the trade screen allows you to wipe a villager back to Novice (with a confirmation prompt) so you can re-pick from scratch if your needs change.

## Technical Details & Compatibility

When you interact with a villager that requires new trades, the server sends the picker GUI to your client. Once you make your selection, the server generates those exact trades using vanilla's own trade-generation pipeline—ensuring strict adherence to vanilla mechanics without homebrew price tables. Choices are saved per-villager in the world data and persist through reloads.

*   **Vanilla Friendly:** Does not add new items, blocks, or custom recipes. It only bypasses the RNG selection process.
*   **Compatibility:** Uses only vanilla rendering and a single merchant-screen mixin. It is highly compatible with optimization and UI mods like Sodium, Iris, Lithium, and ModMenu.
*   **Conflicts:** Incompatible with other trade-cycling mods. Uninstall them before using this mod.
*   **Requirements:** Needs to be installed on **both client and server** (single-player counts as both). Requires Fabric Loader 0.19+, Fabric API, Minecraft 26.1.2, and Java 25.

## License
Released into the Public Domain — CC0 1.0.
