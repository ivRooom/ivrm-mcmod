# Period Quest Sync — Operations / Rollout

This document covers the bridge-side consumer of ivrm-web
#1752: the period-synced FTB Quests chapters (daily / weekly /
monthly) that mirror the FTB-native slice of the portal quest
board. The API generates the chapter files from the board's
fixed assignments (`POST /v1/minecraft/period-quests`, #1752
S2); this module polls that payload, swaps the chapter files
in the FTB Quests book, and reloads the book. Evaluation and
rewards stay with the API exactly as for the permanent quest
line: the book is a view, the Web ledger is the source of
truth.

```text
API quest board assignments + definitions (source of truth)
  -> period chapter generator (deterministic json5 + payload hash)
  -> bridge poller (HMAC, presentation transport)
  -> verified payload -> staged file swap -> ftbquests reload
```

## Safety model

The module is **disabled by default**
(`IVRM_PERIOD_QUESTS_ENABLED`), independent of the Activity
sender and Presentation flags, and it only ever runs on the
**main** server (the API rejects other roles; the bridge also
skips non-main roles locally). It also stays off when FTB
Quests is not loaded — without the book there is nothing to
sync.

- Polling runs on a dedicated daemon thread; the server tick
  thread never performs HTTP or file IO for this module.
  Polls happen every `IVRM_PERIOD_QUESTS_POLL_SECONDS`
  (default 45s) **while at least one player is connected** —
  the design accepts the book catching up at the next login
  instead of reloading into an empty server, and a login pulls
  the next poll forward.
- **Path guards.** A payload may only write the three fixed
  period chapter files (`chapters/ivrm_period_daily.json5`,
  `..._weekly.json5`, `..._monthly.json5`) and the per-period
  lang files (`lang/<ja_jp|en_us>/chapters/<chapter id>.json5`).
  Deletions are narrower still: lang files only — chapter
  files are overwritten, never deleted. Anything else (the
  permanent quest line above all) is outside the guard, and a
  payload that names it fails validation as a whole, so
  nothing on disk changes.
- **Hash verification.** The bridge recomputes the payload
  hash (the generator's canonical SHA-256 over the file set)
  and applies nothing unless it matches the declared hash.
- **Staged swap.** Files are written in full to sibling temp
  files first, then renamed into place (atomic move where the
  filesystem offers one), then the payload's deletions run. A
  failure while staging changes nothing; a failure mid-commit
  leaves complete old or new files, never a truncated one,
  and the next poll re-applies and converges.
- **Reload.** After a swap, the runtime asks the NeoForge
  adapter to run `ftbquests reload` on the server thread with
  an operator-level command source — the same operation the
  operators ran over RCON for the permanent line. If the
  reload fails (or no server is available yet), the files stay
  swapped and the reload is retried on its own on later polls
  until it lands.
- **Failures keep the current book.** Transport errors,
  non-200 responses (including the API's 503 while
  `MINECRAFT_PERIOD_QUESTS_ENABLED` is unset on the API side),
  unparsable bodies, failed verification and failed writes all
  leave the current chapter files untouched and retry with
  exponential backoff (interval × 2 per consecutive failure,
  capped at 5 minutes). A stale book is visible — chapter
  titles carry their period dates — and an outdated chapter
  completed after its period is rejected by the evaluator's
  window check, so no reward can be mis-granted.

## Authentication

The endpoint shares the presentation HMAC transport: the same
per-server secret (`IVRM_ACTIVITY_SERVER_ID` /
`IVRM_ACTIVITY_SERVER_SECRET`), the same headers, and the same
canonical string signed with HMAC-SHA256. The bridge needs no
new credentials. The request body is exactly
`{"schemaVersion":1,"serverRole":"main"}`.

## Configuration

| Setting | Property | Default | Meaning |
| --- | --- | --- | --- |
| `IVRM_PERIOD_QUESTS_ENABLED` | `ivrm.periodQuests.enabled` | `false` | Master switch for the sync |
| `IVRM_PERIOD_QUESTS_POLL_SECONDS` | `ivrm.periodQuests.pollSeconds` | `45` | Poll interval while players are connected (15–3600) |

The book directory is fixed by FTB Quests:
`<gameDir>/config/ftbquests/quests`, and the payload's paths
are relative to it.

## Rollout (#1752 S4, not part of this change)

1. Deploy the bridge jar with the flag still off.
2. Create the API-side SSM parameter
   (`/ivrm/api/minecraft-period-quests`) and enable
   `MINECRAFT_PERIOD_QUESTS_ENABLED`, then set
   `IVRM_PERIOD_QUESTS_ENABLED=true` in the main server's
   `activity.env` and restart.
3. Observe the first 09:00 JST rotation: the day's chapters
   appear, kills count from the chapter load, completions land
   in the Web ledger and on the board, the previous day's
   chapter is replaced, and the permanent line (122 quests) is
   untouched.

Rollback: set `IVRM_PERIOD_QUESTS_ENABLED=false` and restart
(or just stop the polls), delete the three
`chapters/ivrm_period_*.json5` files plus the period lang
files, and run `ftbquests reload` — the book returns to the
permanent line only. Web-side records are append-only and
stay harmless.
