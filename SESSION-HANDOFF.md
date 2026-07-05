# Session handoff — live state

_Last update: 2026-07-04 (Fable 5 session)._ Read CLAUDE.md first for standing rules.

## Current focus

Backport polish, **1.21.9-1.21.11 Fabric cluster only** (`backport/1.21.9`), until the
user calls it perfect. Then remaining Fabric clusters in reverse chronological order,
then the NeoForge pass. Feature version now **1.3.1**.

## State of backport/1.21.9

- Flash/close-on-reopen bug: FIXED earlier (`9a07cd0`), user-playtested OK.
- Issue #7 QOL (owned-trade mark/hide + sort by type): DONE at `f505538`,
  build green, 17/17 gametests. **Awaiting user playtest** — UI changes
  (✔ mark, dimmed owned cards, new config checkbox layout, sort order) are
  gametest-blind; only a real client shows them.
- Price question resolved: min prices + small post-trade discount = intended
  (see CLAUDE.md "Known intended behaviors"). Reported to user 2026-07-04.

## Next steps (in order)

1. User playtests 1.3.1 on a 1.21.9 instance (picker marks, hide toggle via ModMenu
   settings, sort order, normal trading regression).
2. Fix anything the playtest surfaces; re-run gametests.
3. When cluster declared perfect: cherry-pick `f505538` to `backport/1.21.6`
   per `docs/1.3.1-issue7-porting.md`, and continue down the cluster order.
4. Release packaging (all clusters done): one v1.3.1 GitHub release, jars named per
   loader+MC version. User handles Modrinth.
5. Reply on GitHub issue #7 once shipped (as polyvenom, plain language, no AI mention).

## Playtest deploy state

- TP 1.21.9 launcher profile (`C:\Users\tomde\projects\tradepicker-playtest\mc1.21.9\mods`)
  still had the 1.3.0 jar during the user's first 1.3.1 playtest attempt — that's why no
  sorting appeared. Background watcher swaps in 1.3.1 as soon as the game closes
  (jar is file-locked while running). Verify the swap before the next playtest report.

## Blockers / notes

- Asana connector unauthorized in this session — project board not updated.
- Local commits on backport branches are NOT pushed (only master + two feature branches
  have remotes). Push policy: ask user before creating new remote branches.
- 1.21.9 jar after build: `fabric/build/libs/tradeoptimizer-fabric-1.3.1+mc1.21.9.jar`.
