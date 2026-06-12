# Trade Picker — Next Session Starter Prompt

Paste the block below into a new Claude session inside `C:\Users\tomde\projects\tradepicker`. (Memory files will fill in the deep context — this prompt just sets the scope.)

---

# Next sprint kickoff — MC 26.2 compatibility

Load the project memories first to establish operating rules, current state, and communication constraints: `MEMORY.md`, `project_tradeoptimizer.md`, `project_tradepicker_roadmap.md`, `project_minecraft_modding.md`, `feedback_mc_modding.md`, and `feedback_authorship.md`. The roadmap memory's "Next sprint — MC 26.2 compatibility" section is the action-driving one.

## Where we left off (2026-06-08)

- **Trade Picker `1.1.1` is LIVE** on GitHub: https://github.com/polyvenom/tradepicker/releases/tag/v1.1.1 — both `tradeoptimizer-fabric-1.1.1.jar` and `tradeoptimizer-neoforge-1.1.1.jar` attached. Master at `6e24f24`, tag `v1.1.1`.
- Phase 2 (multi-loader port) is shipped. The mod runs on **Fabric AND NeoForge** for MC 26.1.2, with a single shared-logic `:common` module and a ServiceLoader-based platform seam (no Architectury runtime). 10-test in-game safety net passes 10/10 on both loaders. The NeoForge architecture / network API / data-driven gametest recipe is captured in `project_minecraft_modding.md`.
- Modrinth upload: I (the user, polyvenom) am handling that separately — don't redo it.

## Sprint goal

Ship **`1.2.0`** (Fabric + NeoForge) for **Minecraft 26.2** soon after the 26.2 patch drops on June 16th. **Scope = compatibility work only** — no new features, no Phase 3 work, no audit-item cleanup. The audit backlog stays separate.

## Start by answering these (one Bash + web check session)

1. **Has MC 26.2 actually dropped?** (today vs June 16th)
2. **Is NeoForge 26.2.x available on Maven yet?** Check `https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml`. NeoForge typically lags MC release by days to weeks. If not yet → plan a staggered release (ship Fabric first, NeoForge once it lands; that's a feature of the multi-loader work, not a problem).
3. **Fabric API for 26.2** — check `https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/` for the matching `+26.2.x` build.
4. **NeoForm for 26.2** — check `https://maven.neoforged.net/releases/net/neoforged/neoform/maven-metadata.xml`.

## Then the compat work (predictable steps once versions are known)

Branch off master to `compat-26.2` (don't push until ready). All commits authored as polyvenom.

1. Bump `gradle.properties`: `minecraft_version`, `fabric_api_version`, `neoforge_version`, `neoform_version`, `mod_version=1.2.0-rc1`.
2. Widen the MC range in `fabric/src/main/resources/fabric.mod.json` and `neoforge/src/main/resources/META-INF/neoforge.mods.toml` from `[26.1.2,26.2)` to whatever the new range should be. Same for the NeoForge dep range.
3. `./gradlew clean build -x test` on the whole project. Expect compile errors from MC API renames (26.2 is a minor bump so typically small, but check). Vanilla API changes likely affect classes in `:common` only — the platform shells should be stable. Fix as needed.
4. Run both gametest suites: `./gradlew :fabric:runGametest :neoforge:runGameTest`. These are the regression gate — if 10/10 stay green on both loaders, we're shippable.
5. Build playtest jars at `1.2.0-rc1`; I'll playtest both on real Minecraft 26.2 instances.
6. Once playtests pass: drop `-rc1` → `1.2.0`, update README "Minecraft 26.1.2" mentions to "26.2" (Install section + Building from source line), tag `v1.2.0`, push, GitHub release with both jars (one release, both attached — same pattern as v1.1.1).

## Open decisions for the user (me) before pushing

- **Staggered or simultaneous release?** If NeoForge 26.2 isn't out when Fabric 26.2 is ready, do we ship Fabric-only first as `1.2.0` and add the NeoForge jar later, or wait? My instinct is ship Fabric when it's ready (users want timely updates), then attach NeoForge jar to the same GitHub release once available. Confirm before I push.
- **Maintenance line for 26.1.2?** Usually one current version is enough — once 26.2 ships, 26.1.2 users still have `v1.1.1` available on GitHub/Modrinth (they don't break, they just don't get future updates). Only fork a `1.1.x` line if there's a specific reason.

## Standing constraints (don't forget)

- **Code ownership**: I own design/assets; you own code. Proactively flag design/asset choices (icon, listing copy) — don't assume.
- **Communication**: full technical detail first, then a plain-English / layman recap. Both — never skip one.
- **Authorship**: every commit, PR, release credit goes under `polyvenom`. NO `Co-Authored-By: Claude`. NO Claude mentions in commit messages, READMEs, release notes, MODRINTH.md.
- **No public-strategy language** in release copy ("repositioning", "rather than competing", etc.). Plain facts only.
- **Don't over-engineer**: this is a personal-use mod, not a commercial product. Compat scope is compat scope.
- **Trust the running game over headless tests** if they diverge. Gametests are the regression gate but not infallible.

## If we're idle before June 16th

Pull-forward options (low priority, just to be productive):
- Tackle a small audit backlog item (#7 UI literal localization is the most user-visible — would need a lang file added to `common`).
- Spike Phase 3 design flag #1 (the Mastery skill-tree's gating-vs-endless conflict from the PRD). Just design discussion, no code.
- Cleanup: the `:fabric` artifact is now `tradeoptimizer-fabric-*.jar`; if your `.minecraft/mods/` still has an old `tradeoptimizer-1.1.1-rc1.jar`, swap it for the released `tradeoptimizer-fabric-1.1.1.jar`.

Otherwise: ping me when 26.2 drops and we'll start the recon.
