# IVRM Minecraft Mod

IVRMのMinecraft 26.1.2 / NeoForge 26.1.2.81環境で使用するサーバー専用統合MODを管理します。

このRepositoryは、Minecraft Server内で動くIVRM独自NeoForgeコードの正本です。Modpack構成、Quest、Data Pack、Manifestは`ivrm-dailytech-adventure`で管理します。

## Target Architecture

```text
Minecraft Server
       │ NeoForge
       ▼
ivrm-mcmod
  ├ Player Bridge
  ├ Activity Event Sender
  └ Permission Integration
       │
       ▼
api.ivrm.jp / ivrm-web
       │
       ├ Activity
       ├ Rewards
       ├ Ranking
       └ Identity
       │
       ▼
Supabase / ivrm-core
```

全体設計は`ivRooom/ivrm-platform`の`docs/minecraft-integration-architecture.md`を正とします。

## 対象環境

- Minecraft 26.1.2
- NeoForge 26.1.2.81
- Java 25
- Main: `mc-main` / `25565/TCP`
- Resource: `mc-resource` / Router経由 `25999/TCP`

## Integrations

### Player Bridge

生活鯖（Main）と資源鯖（Resource）の間で、プレイヤーを安全に移動させ、道具・装備・採取物を引き継ぐサーバー専用連携機能です。

方針:

- `world/playerdata`はサーバー間で直接共有しない
- 移動前スナップショットと世代番号を保存する
- 同じ転送データを二重適用しない
- 接続失敗時に復元できる状態を残す
- 座標・ディメンション・リスポーン地点は同期しない
- インベントリ同期が完成するまで一般公開しない

詳細: [`docs/player-bridge/`](docs/player-bridge/)

### Permission Integration

既存の`ivrm_permission_control` MOD IDと`ivrm.play.*`ノードを維持しながら、IVRM独自の権限制御ソースをこのRepositoryへ集約します。

Source: [`mods/ivrm-permission-integration/`](mods/ivrm-permission-integration/)

Migration: [`docs/permission-integration-migration.md`](docs/permission-integration-migration.md)

### Activity Event Sender

NeoForgeイベントをCanonical Activity Eventへ変換し、`api.ivrm.jp`へ安全に送信する機能です。

予定する責務:

- login / logout / heartbeat / AFK / stat event
- eventIdによるidempotency
- API障害時のbounded durable queue
- retry / recovery
- service-to-service authentication

Activity、Rewards、Ranking、IdentityのビジネスロジックやSupabaseへの直接接続はMOD側へ持たせません。

Tracking: `ivRooom/ivrm-mcmod#4`

## ビルド

Player Bridge:

```bash
gradle --no-daemon clean build
```

Permission Integration:

```bash
gradle -p mods/ivrm-permission-integration --no-daemon clean build
```

GitHub Actionsでは両方を独立してビルドします。
