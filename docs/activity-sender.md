# Minecraft Activity Sender — Operations / Rollout

This document covers the NeoForge producer for the canonical Minecraft Activity Event v1 contract.

## Safety model

The sender is **disabled by default**. Activity reporting must never make Minecraft gameplay depend on API or disk latency.

Event flow:

```text
NeoForge gameplay event
  -> canonical Activity Event v1 JSON
  -> bounded in-memory ingress (non-blocking offer)
  -> background dispatcher
  -> append-only durable queue journal (fsync)
  -> fresh X-IVRM-Timestamp + fresh HMAC-SHA256
  -> POST /v1/minecraft/activity-events
```

The gameplay thread performs no filesystem or HTTP I/O. The dispatcher must durably fsync an event before its first network attempt.

There is intentionally a small crash window between a successful in-memory ingress offer and the dispatcher's first fsync. This trade-off prevents disk latency from blocking Minecraft ticks. An orderly `ServerStoppedEvent` performs a final ingress persistence/drain pass before the dispatcher terminates. If the process or host loses power before that pass, only events that had not yet reached `queue.ndjson` can be lost.

## Canonical contract pin

Producer conformance is pinned to:

```text
contracts/ivrm/minecraft-activity.v1.bundle.json
```

Current pinned Git blob:

```text
c1b53b337b28214897bee2464e9e5c0598931da2
```

Current SHA-256:

```text
7560e6a41d729f27aeadbeb5bd07a7072255a60f8830e93b45ca7863944337aa
```

The same snapshot must be used by `ivrm-contracts` PR #6 and `ivrm-web` Consumer conformance before merge.

## Configuration

Do not commit production secrets to GitHub, documentation, server properties, or issue comments.

Required only when enabling the sender:

| Environment variable | Purpose | Default |
| --- | --- | --- |
| `IVRM_ACTIVITY_ENABLED` | Enables sender | `false` |
| `IVRM_ACTIVITY_SERVER_ID` | Physical server/authentication identity | empty |
| `IVRM_ACTIVITY_SERVER_ROLE` | Logical role: `main` or `resource` | `main` |
| `IVRM_ACTIVITY_SERVER_SECRET` | HMAC secret | empty |
| `IVRM_ACTIVITY_BASE_URL` | Activity API origin | `https://api.ivrm.jp` |

Optional tuning:

| Environment variable | Default |
| --- | ---: |
| `IVRM_ACTIVITY_CONNECT_TIMEOUT_SECONDS` | `5` |
| `IVRM_ACTIVITY_REQUEST_TIMEOUT_SECONDS` | `10` |
| `IVRM_ACTIVITY_HEARTBEAT_SECONDS` | `30` |
| `IVRM_ACTIVITY_AFK_SECONDS` | `300` |
| `IVRM_ACTIVITY_QUEUE_MAX` | `10000` |
| `IVRM_ACTIVITY_MAX_ATTEMPTS` | `20` |

Equivalent JVM properties use the `ivrm.activity.*` names implemented by `ActivityConfig`.

HTTP is accepted only for localhost development (`localhost`, `127.0.0.1`, `[::1]`). Remote Activity API endpoints require HTTPS. Embedded credentials, query strings, fragments, and non-root base paths are rejected.

## Durable state

Under the Minecraft game directory:

```text
config/ivrm/activity/queue.ndjson
config/ivrm/activity/dead-letter.ndjson
config/ivrm/activity/corrupt.ndjson
config/ivrm/activity/queue.ndjson.unreadable-<timestamp>   # only after whole-file decode failure
```

- `queue.ndjson`: append-only active/retry/ack journal. Exact event body is retained across restart. Success/retry state is appended as a small record instead of rewriting the full backlog for every delivery. The dispatcher periodically compacts the journal using temp-file + fsync + atomic replace.
- `dead-letter.ndjson`: queue overflow, retry exhaustion, 409 conflict, and permanent receiver failures. An active event is removed only after dead-letter persistence and its queue tombstone both succeed.
- `corrupt.ndjson`: malformed individual queue records quarantined during restore.
- `queue.ndjson.unreadable-*`: exact original queue file isolated when UTF-8 decoding or whole-file reading fails. If the original file cannot be isolated, Activity sender initialization fails closed while Minecraft itself continues to start.

Do not delete these files during incident response unless the retained events have been deliberately reconciled.

## Retry / idempotency

- `eventId` and body `occurredAt` are generated at the gameplay event and remain unchanged.
- Every HTTP attempt generates a fresh `X-IVRM-Timestamp`.
- HMAC is recomputed for every attempt using the unchanged body and event ID.
- HTTP `200` and `202` append an acknowledgement tombstone before removing the active in-memory entry. If that journal write fails, the event remains active and may be replayed safely.
- HTTP `409` moves the event to dead-letter for investigation. If dead-letter persistence fails, the active copy is retained.
- HTTP `408`, `425`, `429`, and `5xx` use exponential backoff with jitter. Retry state is journaled before the in-memory schedule changes.
- Other `4xx` responses are treated as permanent and moved to dead-letter.
- Retry exhaustion moves the event to dead-letter instead of dropping it.
- The receiver's event-id idempotency is required because preserving data on local persistence failures can intentionally cause a replay rather than a loss.

## Canonical event sources

- `player.login`: NeoForge `PlayerLoggedInEvent`
- `player.logout`: NeoForge `PlayerLoggedOutEvent`
- `player.heartbeat`: periodic online-player heartbeat
- `player.afk_changed`: emitted only when sampled AFK state changes
- `player.stat_delta`: NeoForge XP change represented as `stat=minecraft:experience_points`, `delta=<amount>`

The dispatcher remains active through final `PlayerLoggedOutEvent` handling and is closed from `ServerStoppedEvent`.

All event-specific metadata is string-only `attributes`. Credentials and player chat must never be placed in attributes.

## Staged production rollout

Production enablement is intentionally outside the implementation PR.

Required order:

1. Merge/freeze canonical `ivrm-contracts` v1.
2. Confirm `ivrm-web` Consumer uses the exact same bundle and all CI/review gates are green.
3. Confirm this Producer uses the exact same bundle and all Java 25 / NeoForge tests are green.
4. Complete reviewed persistence migration and receiver deployment.
5. Provision each server secret out-of-band with least privilege.
6. Start with `IVRM_ACTIVITY_ENABLED=false` and verify server startup.
7. Enable one non-critical server first and verify 200/202 ingestion, queue drain, bounded ingress behavior, and no material TPS regression.
8. Enable remaining servers gradually.

## Rollback

1. Set `IVRM_ACTIVITY_ENABLED=false` and restart the affected Minecraft server.
2. Preserve `queue.ndjson`, `dead-letter.ndjson`, `corrupt.ndjson`, any `queue.ndjson.unreadable-*` file, and accepted receiver records.
3. Stop downstream canonical processing separately if the fault is after durable ingest.
4. Fix/redeploy the receiver or producer.
5. Re-enable only after Contract / Consumer / Producer conformance is restored.

Disabling the sender stops new Activity emission. It does not delete historical or queued facts.

## Deferred management command

`/ivrm activity status|flush` remains a follow-up operator UX improvement. The MVP exposes the durable state files and startup/shutdown queue counts without adding a new command surface to this security-sensitive change.
