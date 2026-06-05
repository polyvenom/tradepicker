Trade Optimizer

Pick your villager's trades instead of cycling for them. Minecraft 26.1.2 (Fabric).
What it does

Right-click a villager and, instead of the random trade screen, you get a picker: every trade that villager could offer at its current level, shown at the lowest vanilla price. Choose two, confirm, and those become the villager's trades. No breaking workstations, no re-rolling, no luck.

    Every level is a fresh choice. Novice, Apprentice, Journeyman, Expert, Master — each time the villager levels up, you pick its next two trades.
    Enchanted books, fully expanded. A librarian's picker lists every tradeable enchantment at every level (Mending, Sharpness I–V, Fortune III, etc.) as its own option — each at vanilla's lowest price for that enchantment.
    Search. Type in the search box to filter — mending, sharp, emerald, protection — so you're not scrolling through a hundred books.
    Always the best base price. Prices are vanilla's own minimum roll. Reputation discounts (curing a zombie villager, Hero of the Village) still stack on top in-game.
    Reset. A Reset button in the trade screen wipes a villager back to Novice (with an "are you sure?") so you can re-pick from scratch.

Install

    Drop tradeoptimizer-x.y.z.jar into your mods folder.
    Requires Fabric Loader 0.19+, Fabric API, Minecraft 26.1.2, Java 25.
    Needs to be on both client and server (single-player counts as both).

How it works

When you interact with a villager that hasn't had trades picked for its current level, the server sends the picker to your client. Your selections come back, the server generates those exact trades at minimum cost using vanilla's own trade-generation pipeline (no homebrew price tables), applies them to the villager, and opens the trade screen. Choices are saved per-villager in the world data, so they survive logout and reload.

It does not add items, blocks, or recipes. It only changes how a villager's trade list is decided.
Compatibility

Replaces the need for trade-cycling mods — uninstall trade-cycling if you have it. Uses only vanilla rendering and a single merchant-screen mixin, so it plays nicely with Sodium, Iris, Lithium, ModMenu, and other mainstream Fabric mods.
License

Public domain — CC0 1.0.
