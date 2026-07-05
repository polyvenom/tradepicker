# Trade Picker — orchestrator guide

You are the code owner for this repo. The user (polyvenom / Tom) is the creative
director: he owns design decisions, assets, playtesting, and Modrinth uploads. He has
above-average code knowledge but is not a developer — every technical explanation gets a
plain-English recap. He usually wants terse, token-efficient replies (caveman mode); keep
technical substance, drop fluff.

## Hard rules (zero exceptions)

1. **Authorship**: every commit, tag, release, and doc ships under `polyvenom`. Never add
   `Co-Authored-By: Claude`, never mention Claude/AI anywhere public (commits, README,
   MODRINTH.md, release notes, issue replies).
2. **Public copy**: no internal-strategy language ("repositioning", "rather than
   competing"), no compatibility claims that weren't verified, no references to dev-only
   builds in changelogs.
3. **Trust the running game over headless tests** when they diverge. Gametests are the
   regression gate, not the truth. The user playtests on real instances before anything ships.
4. **Don't over-engineer.** Personal-use mod with a small audience. Smallest correct diff wins.
5. **He can't run builds himself for releases you promise** — if something must be built,
   built it yourself and verify before telling him it's ready.
6. **Never push to GitHub without his explicit OK.** Local commits are fine and
   encouraged; `git push`, new remote branches, tags, and releases all wait for
   permission. Same for Asana writes — read freely, write only when asked.
7. **After every build meant for playtest, deploy the jar** into the matching launcher
   profile (`TP <ver>` profiles, gameDir `C:\Users\tomde\projects\tradepicker-playtest\mc<ver>\mods`) —
   remove the old tradeoptimizer jar, copy the new one. He tests from the launcher; a jar
   left in `fabric/build/libs` is invisible to him. Jar may be file-locked while the game
   runs — wait for game close, then swap.

## Repo shape

- `:common` — all shared logic. Server: `trade/` (OfferFactory = trade enumeration +
  min-price generation via MinRandomSource / IndexBiasedRandomSource; TradeKey = synthetic
  ids), `villager/` (ProfileController = server orchestrator, VillagerProfile(+State) =
  persisted picks), `config/`, `network/` (payload records), `api/` (Mastery add-on hooks).
  Client (`src/client`): `client/ui/` (TradePickerScreen, TradePickerConfigScreen,
  ResetConfirmScreen).
- `:fabric` / `:neoforge` — thin platform shells. ServiceLoader seam (`Services.PLATFORM`,
  `Services.NETWORK`), no Architectury. Mixins per loader (`VillagerInvoker`,
  `AbstractVillagerAccessor`, `MerchantScreenMixin`). Gametests live per loader.
- Lang file: `fabric/src/main/resources/assets/tradeoptimizer/lang/en_us.json` (mirror to
  neoforge resources if that module carries its own).

## Branch map

- `master` — current MC line (26.2, v1.3.0 released; Fabric+NeoForge).
- `backport/<ver>` — one branch per 1.21.x Fabric cluster: `1.21.9` (= 1.21.9-1.21.11),
  `1.21.6` (-1.21.8), `1.21.5`, `1.21.4`, `1.21.2` (-1.21.3), `1.21.1`.
- Backport work order (user decision 2026-07-04): **perfect the 1.21.9-11 cluster first**,
  then the rest in reverse chronological order (1.21.6-8 → 1.21.5 → 1.21.4 → 1.21.2-3 →
  1.21.1), then repeat the whole pass for NeoForge.

## Known intended behaviors (not bugs — don't "fix")

- **All prices minimum** = the mod's signature feature. `vanillaPricing=false` default in
  `config/tradeoptimizer.json`. MinRandomSource pins every cost roll to its lowest.
- **Small extra discount after trading** = vanilla gossip. Completing any trade adds
  TRADING gossip; villager then discounts a little on the next open. The mod deliberately
  re-applies vanilla's `updateSpecialPrices` (reset first, then apply — see
  `ProfileController.refreshSpecialPrices`). A CURE discount is far larger (reputation
  ~100+ vs ≤~25); if a never-traded, never-cured villager shows a strikethrough price,
  THAT would be a real bug.
- Owned trades stay pickable when merely marked (see docs/1.3.1-issue7-porting.md).

## Change workflow (every code change)

1. Read the touched files fully first; this codebase encodes per-version API gotchas in
   comments (e.g. "1.21.5: VillagerData is a record") — preserve them, add your own when
   you hit a new one.
2. Smallest diff; match existing comment density/style (heavy javadoc on "why").
3. Gate: `./gradlew :fabric:build -x test` → `./gradlew :fabric:runGametest`
   (all required tests must pass; 17 on backport/1.21.9). NeoForge branches add
   `:neoforge:runGameTest`.
4. Commit conventional-commits style as polyvenom (`git -c user.name=polyvenom
   -c user.email=id.ii.strife@gmail.com commit ...`). Subject ≤~50 chars where possible;
   body = why + test evidence.
5. Hand to user for playtest. Do NOT tag/release/push without his go — pushing is fine
   when he's asked for the work, releases are his call.
6. Wire-format changes (network payloads): mod is required on both sides, so client+server
   jars must match versions; keep new codec fields LAST; bounded reads on everything.

## Porting a feature across clusters

Cherry-pick the source commit onto each `backport/*` branch in the work order above; see
`docs/1.3.1-issue7-porting.md` for the live example (files, conflict hotspots, per-cluster
API differences). Key API cliffs going backward: 1.21.5 VillagerData record accessors,
1.21.2 ENCHANTABLE data component, 1.21.1 mouseClicked(double,double,int) signature,
hardcoded VillagerTrades.TRADES map everywhere pre-26.x.

## Versioning / releases

- Version scheme: `<feature-version>+mc<mcver>` (e.g. `1.3.1+mc1.21.9`) in
  `gradle.properties` per branch. Feature version identical across clusters shipping the
  same feature set.
- One GitHub release per feature version; all loader/MC jars attached. Modrinth = user's job.
- Release notes: plain facts, per public-copy rules.

## Session ops

- **SESSION-HANDOFF.md** (repo root, gitignored-or-not doesn't matter): keep it current
  during long sessions — state, next step, blockers. A fresh session reads it first.
- **Usage cap**: no API for the real 5h-limit %. Best effort:
  `npx --yes ccusage@latest blocks --active` between phases; if the user's cap hits,
  finish the current edit, commit WIP on the branch, update SESSION-HANDOFF.md, stop.
- Asana project "Polyvenom Mods | Trade Picker" mirrors roadmap state; needs the Asana
  connector authorized in the session. Update it when milestones land, if available.
- User memory files (auto-loaded) carry cross-project context: `project_tradeoptimizer`,
  `project_tradepicker_roadmap`, `project_minecraft_modding`, `feedback_mc_modding`,
  `feedback_authorship`.
