# Minecraft Presentation Module — Operations / Rollout

This document covers the bridge-side presentation features of
ivrm-web #1584 S3: the per-player sidebar and the
`/ivrm today` / `/ivrm board` commands, rendered from the API's
Presentation Snapshot (`POST /v1/minecraft/presentation/snapshot`,
#1584 S2).

## Safety model

The module is **disabled by default** and display-only. It never
evaluates quests or achievements, never grants XP or points, and
never writes to the API: evaluation and rewards stay with the API,
exactly as the Web member pages read them.

```text
API quest / achievement evaluation (source of truth)
  -> Presentation Snapshot (read-only, HMAC-signed polling)
  -> bridge PresentationCache (last good snapshot wins)
  -> per-player sidebar packets / /ivrm today chat output
```

- Polling runs on a dedicated daemon thread; the server tick
  thread never performs HTTP.
- The sidebar is rendered with vanilla scoreboard packets sent
  **only to the player it belongs to**. The objective is a
  detached client-side objective created from packets; it is
  never registered in the server scoreboard, so no player ever
  receives another player's progress, and TAB's scoreboard stays
  disabled and untouched.
- A failed fetch keeps the previous snapshot. Progress sections
  render while the snapshot is within the stale window
  (default 300s from fetch); beyond that the sidebar degrades
  to the server basics (online/active counts, world day) so
  stale progress is never shown as current.
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
| `IVRM_PRESENTATION_STALE_SECONDS` | How long a snapshot may render progress sections | `300` |

Equivalent JVM properties use the `ivrm.presentation.*` names
implemented by `PresentationConfig`. Poll failures back off
exponentially from the poll interval, capped at 5 minutes; a
login pulls the next poll forward.

## Player-facing surface

- Sidebar (vanilla scoreboard, per player): online/active
  counts, world day, today's Minecraft daily progress
  (incomplete items first, at most 3), achievement totals with
  the single closest next achievement, and the time until the
  daily reset from the API's `dailyResetsAt`.
- `/ivrm today`: full daily list and up to 3 next achievements
  in the player's own chat.
- `/ivrm board on|off`: per-player sidebar visibility,
  persisted across restarts.

Durable state (display preferences only):

```text
config/ivrm/presentation/board.properties   # only opt-outs are stored
```

## Not in this slice (S4)

Achievement celebration effects (Title / ActionBar / sound /
particles) are **not** implemented here. They need the API-side
Presentation Outbox and its event polling/ack flow (#1584 S4);
the S2 snapshot carries current state only, no completion
events, so there is nothing safe to celebrate from polling
alone. `/ivrm effects on|off` also arrives with S4.

The snapshot also carries no member level/XP totals or
Minecraft job, so the sidebar does not show them; adding them
is an API-side snapshot extension, not a bridge change.

## Rollback

1. Set `IVRM_PRESENTATION_ENABLED=false` and restart the
   affected Minecraft server.
2. The sidebar disappears (a stale client-side objective is
   dropped on disconnect); TAB and the Activity sender are
   untouched. `config/ivrm/presentation/` may be left in place.
