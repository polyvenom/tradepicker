# Session handoff — live state

_Last update: 2026-07-06 (Sonnet 5 session)._ Read CLAUDE.md first for standing rules.

## Current focus

All Fabric clusters for 1.3.1 are shipped. Remaining work: NeoForge pass (on hold,
user call, low usership), then user's own Modrinth upload.

## 2026-07-06 — ALL FABRIC BACKPORTS SHIPPED
- User confirmed playtesting complete (all clusters passed).
- Pushed backport/1.21.1, 1.21.2, 1.21.4, 1.21.5, 1.21.6 to origin (previously local-only).
- Built Fabric jars for all 5 clusters, uploaded to the existing v1.3.1 GitHub release
  alongside the already-live 26.2/26.1/1.21.9 jars — release now has all 8 Fabric jars.
- Updated release notes with the full download table (all 8 version ranges).
- User handles Modrinth upload from here.

## 2026-07-04 night — v1.3.1 RELEASED
- Playtests PASSED (1.21.9 + 26.2). Pushed master + backport/1.21.9, tagged v1.3.1,
  GitHub release live with 26.2 jar + 1.21.9-11 jar. Modrinth = user.
- Backport/1.21.6/.5/.4/.2/.1: playtest passed 2026-07-06, pushed + jars shipped (see above).

## 2026-07-04 evening update — ALL FABRIC PORTS DONE

- Sort fix (plain trades before enchanted cards) added after playtest feedback: `e2555ee`.
- 1.3.1 cherry-picked to every Fabric line: 1.21.6, 1.21.5, 1.21.4, 1.21.2, 1.21.1,
  26.1.x, master(26.2). All gametests green, all TP launcher profiles + the main
  .minecraft (26.2) updated. Full table: docs/1.3.1-issue7-porting.md "Porting log".
- Asana Backport QA task has the same status comment (write permission granted 2026-07-04).
- NeoForge pass ON HOLD (user call — low usership).
- Awaiting: user playtest of sort fix on 1.21.9 + spot-checks of other clusters, then
  push + v1.3.1 GitHub release on his go.

## State of backport/1.21.9

- Flash/close-on-reopen bug: FIXED earlier (`9a07cd0`), user-playtested OK.
- Issue #7 QOL (owned-trade mark/hide + sort by type): DONE at `f505538`,
  build green, 17/17 gametests. **Awaiting user playtest** — UI changes
  (✔ mark, dimmed owned cards, new config checkbox layout, sort order) are
  gametest-blind; only a real client shows them.
- Price question resolved: min prices + small post-trade discount = intended
  (see CLAUDE.md "Known intended behaviors"). Reported to user 2026-07-04.

## Next steps (in order)

1. Reply on GitHub issue #7 now that it's shipped (as polyvenom, plain language, no AI mention).
2. User uploads all 8 Fabric jars to Modrinth.
3. NeoForge pass, if/when user decides to revisit (currently on hold, low usership).

## Playtest deploy state

- TP 1.21.9 launcher profile (`C:\Users\tomde\projects\tradepicker-playtest\mc1.21.9\mods`)
  still had the 1.3.0 jar during the user's first 1.3.1 playtest attempt — that's why no
  sorting appeared. Background watcher swaps in 1.3.1 as soon as the game closes
  (jar is file-locked while running). Verify the swap before the next playtest report.

## Blockers / notes

- Asana connector unauthorized in this session (2026-07-06) — project board not updated.
  Same status as 2026-07-04; still needs manual auth before Claude can read/write it.
- All backport branches (1.21.1 through 1.21.9, 26.1.x) are now pushed to origin.
- Jars after build land in `fabric/build/libs/tradeoptimizer-fabric-1.3.1+mc<version>.jar`.
