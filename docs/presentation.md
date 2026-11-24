# Minecraft Presentation Module — Operations / Rollout

This document covers the bridge-side presentation features of
ivrm-web #1584: the per-player TAB-list footer (the default
board surface since #33) and sidebar, plus the
`/ivrm today` / `/ivrm board` commands, rendered from the API's
Presentation Snapshot (`POST /v1/minecraft/presentation/snapshot`,
#1584 S2), and — since S4 — the achievement effects delivered
through the Presentation Outbox
(`POST /v1/minecraft/presentation/events` + `/events/ack`).

## Safety model

The module is **disabled by default** and display-only. It never
evaluates quests or achievements, never grants XP or points, and
never writes to the API: evaluation and rewards stay with the API,
exactly as the Web member pages read them.

```text
API quest / achievement evaluation (source of truth)
  -> Presentation Snapshot (read-only, HMAC-signed polling)
  -> bridge PresentationCache (last good snapshot wins)
  -> per-player TAB footer / sidebar packets / /ivrm today chat output

API completion / unlock ledgers
  -> Presentation Outbox (deterministic event ids, S4)
  -> bridge event polling -> render once -> local ledger + ack
  -> Title / ActionBar / chat / sound / particles (achiever only)
```

- Polling runs on a dedicated daemon thread; the server tick
  thread never performs HTTP.
- The sidebar is rendered with vanilla scoreboard packets sent
  **only to the player it belongs to**. The objective is a
  detached client-side objective created from packets; it is
  never registered in the server scoreboard, so no player ever
  receives another player's progress, and TAB's scoreboard stays
  disabled and untouched.
- The TAB footer is sent with `ClientboundTabListPacket`
  **only to the player it belongs to**. That packet couples
  header and footer, so the bridge always sends an **empty
  header**: while the module is enabled it owns the tab-list
  customisation. The TAB plugin foreseen by #1584 S1 is not
  deployed; before it (or anything else that sets a tab-list
  header/footer) is introduced, header/footer ownership must
  be coordinated — as long as both sides send the packet,
  each update overwrites the other side's field.
- A failed fetch keeps the previous snapshot. Progress sections
  render while the snapshot is within the stale window
  (default 300s from fetch); beyond that the sidebar degrades
  to the server basics (online/active counts, world day) so
  stale progress is never shown as current, while the TAB
  footer is cleared entirely (an empty footer is sent once)
  so no stale summary lingers on the glance surface.
- Disabling the module (or the API-side
  `MINECRAFT_PRESENTATION_ENABLED` flag, which makes the API
  answer 503) removes the dynamic sections; Minecraft and the
  Activity sender are unaffected.

## Authentication

The snapshot endpoint intentionally mirrors the activity
ingest: same per-server secret, same headers
(`X-IVRM-Server-Id` / `X-IVRM-Timestamp` / `X-IVRM-Event-Id` /
`X-IVRM-Signature`) and the same HMAC-SHA256 canonical string.
The event id is a fresh per-request UUID because the read-only
endpoint stores no event identity. The module therefore reuses
the Activity sender's identity settings
(`IVRM_ACTIVITY_BASE_URL`, `IVRM_ACTIVITY_SERVER_ID`,
`IVRM_ACTIVITY_SERVER_ROLE`, `IVRM_ACTIVITY_SERVER_SECRET`);
both must be present when the module is enabled, regardless of
whether the Activity sender itself is enabled. Secret values
must never be committed or logged.

## Configuration

| Environment variable | Purpose | Default |
| --- | --- | --- |
| `IVRM_PRESENTATION_ENABLED` | Enables the module | `false` |
| `IVRM_PRESENTATION_SNAPSHOT_SECONDS` | Snapshot poll interval | `45` |
| `IVRM_PRESENTATION_EVENT_POLL_SECONDS` | Outbox event poll interval (S4) | `15` |
| `IVRM_PRESENTATION_EFFECTS_ENABLED` | Master switch for S4 effects (chat records still arrive while off) | `true` |
| `IVRM_PRESENTATION_STALE_SECONDS` | How long a snapshot may render progress sections | `300` |

Equivalent JVM properties use the `ivrm.presentation.*` names
implemented by `PresentationConfig`. Poll failures back off
exponentially from the poll interval, capped at 5 minutes; a
login pulls the next poll forward.

## Player-facing surface

- TAB-list footer (default board surface, #33): the same
  per-player summary as the sidebar — online/active counts,
  world day, today's Minecraft daily progress (incomplete
  items first, at most 3), achievement totals with the single
  closest next achievement, and the time until the daily
  reset from the API's `dailyResetsAt`. It is visible only
  while the player holds TAB, is sent on login and whenever
  the rendered text changes, and is cleared while the
  snapshot is outside the stale window. The footer is not
  affected by `/ivrm board`.
- Sidebar (vanilla scoreboard, per player): the same summary
  as the TAB footer, always on screen. **Opt-in since #33**
  (default off) — before #33 it was on by default and proved
  too intrusive in play.
- `/ivrm today`: full daily list and up to 3 next achievements
  in the player's own chat.
- `/ivrm board on|off`: per-player sidebar visibility,
  persisted across restarts. Only opt-ins are stored.
  Migration note: files written before #33 stored opt-outs
  (`off` rows); those rows are dropped on first load. Their
  owners keep the same effective state — sidebar hidden —
  because off is now the default, and the old model never
  recorded an opt-in, so nobody is silently kept on: a player
  who wants the sidebar runs `/ivrm board on` once.
- `/ivrm effects on|off`: per-player achievement effects,
  persisted across restarts (S4).

Durable state (display preferences and duplicate suppression
only — never game state):

```text
config/ivrm/presentation/board.properties      # only opt-ins are stored
config/ivrm/presentation/effects.properties    # only opt-outs are stored
config/ivrm/presentation/delivered.properties  # handled outbox event ids (S4)
```

## Achievement effects (S4)

The API mirrors quest completions, all-clears and achievement
unlocks into its Presentation Outbox (deterministic event ids;
the API's ledgers stay the only source of truth). The bridge
polls the outbox every `IVRM_PRESENTATION_EVENT_POLL_SECONDS`
while players are connected and renders each event **once, for
the achiever only**:

| Event | Rendering |
| --- | --- |
| Daily quest completed | ActionBar + chat, small sound |
| Weekly / monthly quest completed | ActionBar + chat, level-up sound, a few particles |
| All-clear | Title + subtitle, challenge sound, firework particles |
| Achievement unlocked | Title + subtitle; sound/particles graded by the API's effect profile (`celebration` / `grand`) |
| Login bonus, granted for today (JST) | ActionBar + chat, level-up sound (volume 0.7), happy-villager particles |
| Login bonus, granted for a previous day | Chat only |

Delivery is at-least-once end to end and made exactly-once in
practice by two acks: after rendering, the bridge records the
event id in `delivered.properties` and acks it to the API; if
an ack is lost, the re-polled event is suppressed by the local
ledger (and acked again). Effects for one player are spaced at
least 2 seconds apart.

Offline catch-up (design §11-3): events that completed while
the player was offline are delivered at their next login —
achievements as one simplified Title each, quest completions
as chat summary lines only. Events whose presentation window
(`expiresAt`: end of the quest period, 7 days for unlocks) has
closed are never rendered; the API sweeps them to expired.

Login bonuses (#1712) follow their own grading: the payload's
`date` is the JST stat date the bonus was granted for. While
that date is still today (JST) the bonus renders in full
whenever the player receives it — even at a later same-day
login; once the date has rolled over, the delivery is the
chat record only, mirroring the catch-up rule.

While `IVRM_PRESENTATION_EFFECTS_ENABLED` is off, or for a
player who ran `/ivrm effects off`, events still produce their
chat completion record and are acked — only the Title /
ActionBar / sound / particles are skipped.

The snapshot's `profile` section (member level / total XP /
selected Minecraft job) is parsed-ready on the API side since
S4; the sidebar does not render it yet — that display is a
small follow-up, not a bridge contract change.

## Rollback

1. Set `IVRM_PRESENTATION_ENABLED=false` and restart the
   affected Minecraft server.
2. The sidebar disappears (a stale client-side objective is
   dropped on disconnect) and the TAB footer stops updating
   (the client drops the tab-list customisation on
   disconnect); the Activity sender is untouched.
   `config/ivrm/presentation/` may be left in place.
