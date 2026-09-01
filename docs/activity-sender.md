# Minecraft Activity Sender — Operations / Rollout

This document covers the NeoForge producer for the canonical Minecraft Activity Event v1 contract.

## Safety model

The sender is **disabled by default**. Activity reporting must never make Minecraft gameplay depend on API availability.

Event flow:

```text
NeoForge gameplay event
  -> canonical Activity Event v1 JSON
  -> durable local queue (fsync)
  -> background dispatcher
  -> fresh X-IVRM-Timestamp + fresh HMAC-SHA256
  -> POST /v1/minecraft/activity-events
```

The gameplay thread only appends to the local queue. It does not wait for HTTP.

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

HTTP is accepted only for localhost development. Remote Activity API endpoints require HTTPS.

## Durable state

Under the Minecraft game directory:

```text
config/ivrm/activity/queue.ndjson
config/ivrm/activity/dead-letter.ndjson
config/ivrm/activity/corrupt.ndjson
```

- `queue.ndjson`: active retry queue. Exact event body is retained across restart.
- `dead-letter.ndjson`: queue overflow, retry exhaustion, 409 conflict, and permanent receiver failures. Events are isolated, not silently deleted.
- `corrupt.ndjson`: malformed queue records quarantined during restore. Queue corruption does not block server startup.

Do not delete these files during incident response unless the retained events have been deliberately reconciled.

## Retry / idempotency

- `eventId` and body `occurredAt` are generated at the gameplay event and remain unchanged.
- Every HTTP attempt generates a fresh `X-IVRM-Timestamp`.
- HMAC is recomputed for every attempt using the unchanged body and event ID.
- HTTP `200` and `202` remove the queued event.
- HTTP `409` moves the event to dead-letter for investigation.
- HTTP `408`, `425`, `429`, and `5xx` use exponential backoff with jitter.
- Other `4xx` responses are treated as permanent and moved to dead-letter.
- Retry exhaustion moves the event to dead-letter instead of dropping it.

## Canonical event sources

- `player.login`: NeoForge `PlayerLoggedInEvent`
- `player.logout`: NeoForge `PlayerLoggedOutEvent`
- `player.heartbeat`: periodic online-player heartbeat
- `player.afk_changed`: emitted only when sampled AFK state changes
- `player.stat_delta`: NeoForge XP change represented as `stat=minecraft:experience_points`, `delta=<amount>`

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
7. Enable one non-critical server first and verify 200/202 ingestion, queue drain, and no TPS impact.
8. Enable remaining servers gradually.

## Rollback

1. Set `IVRM_ACTIVITY_ENABLED=false` and restart the affected Minecraft server.
2. Preserve `queue.ndjson`, `dead-letter.ndjson`, and accepted receiver records.
3. Stop downstream canonical processing separately if the fault is after durable ingest.
4. Fix/redeploy the receiver or producer.
5. Re-enable only after Contract / Consumer / Producer conformance is restored.

Disabling the sender stops new Activity emission. It does not delete historical or queued facts.

## Deferred management command

`/ivrm activity status|flush` remains a follow-up operator UX improvement. The MVP exposes the durable state files and startup/shutdown queue counts without adding a new command surface to this security-sensitive change.
