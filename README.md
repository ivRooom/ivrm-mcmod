# IVRM Minecraft Mod

IVRMのMinecraft 26.1.2 / NeoForge 26.1.2.81環境で使用するサーバー連携MODを管理します。

## 最初の機能: IVRM Player Bridge

生活鯖（Main）と資源鯖（Resource）の間で、プレイヤーを安全に移動させ、道具・装備・採取物を引き継ぐためのサーバー専用連携機能です。

### 対象環境

- Minecraft 26.1.2
- NeoForge 26.1.2.81
- Java 25
- Main: `mc-main` / `25565/TCP`
- Resource: `mc-resource` / Router経由 `25999/TCP`

### 方針

- `world/playerdata`はサーバー間で直接共有しない
- 移動前スナップショットと世代番号を保存する
- 同じ転送データを二重適用しない
- 接続失敗時に復元できる状態を残す
- 座標・ディメンション・リスポーン地点は同期しない
- インベントリ同期が完成するまで一般公開しない

詳細は [`docs/player-bridge/`](docs/player-bridge/) を参照してください。
