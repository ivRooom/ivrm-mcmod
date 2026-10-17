---
name: player-bridge-recovery
description: ivrm-mcmodのMain↔Resource Player Bridge実装・データ整合・アイテム複製/消失の調査、転送失敗時の安全な復旧計画に使う。
---

# Player Bridge Recovery

## 正本

`docs/player-bridge/architecture.md`、`docs/player-bridge/data-contract.md`、`docs/player-bridge/test-plan.md` と現在の実コード/CI/本番ロールアウト状態を照合する。設計資料に書かれた状態遷移や管理コマンドが**実装済み・有効化済み**とは仮定しない。

[共通Minecraft運用](https://github.com/ivRooom/ivrm-platform/blob/main/.agents/skills/minecraft-operations/SKILL.md)、[incident-response](https://github.com/ivRooom/ivrm-platform/blob/main/.agents/skills/incident-response/SKILL.md)、[MCP接続基準](https://github.com/ivRooom/ivrm-platform/blob/main/.agents/skills/tool-access-and-mcp/SKILL.md)を併用。

## 転送・障害調査

1. 対象プレイヤーを公開ログ上は匿名化し、Main/Resource、発生時刻、transferId、generation、bridge state、サーバー停止状況をread-onlyで確認。
2. `world/playerdata` の直接共有をしない。座標/ディメンション/リスポーン地点は同期しない。
3. Durable snapshot/sha256/lease/generationの有効性を照合。送信元escrow後の二重writeや古いgeneration再適用、二重ログインを防止。
4. 状態遷移（`PREPARED`, `IN_TRANSIT`, `IMPORTED`, `COMMITTED`）と実装状態が一致するか確認。特に `IMPORTED` 後のsourceへの無条件rollbackは複製リスクがある。
5. Mainワールド、アイテム、Bridge storeの最新Backupと復元手順を先に検証。復旧操作はread-only調査・事前の別環境rehearsalを優先。
6. 反映済みか判定できないときは自動復元せず `ADMIN_REVIEW` として停止し、Operator承認のもと個別対処。
### `RECOVERY_REQUIRED` の遷移判定（運用上の判定基準）

`BridgeTransitions.java` は `RECOVERY_REQUIRED` から `ROLLED_BACK` / `IMPORTED` / `ADMIN_REVIEW` への遷移を**構文上許可**するが、そのまま復旧操作の安全性を保証しない。以下の確認は復旧操作に進むための提案基準であり、実際の復旧コマンド・実装の検証前に本番で状態を書き換えない。

- **共通証拠:** `state.json` のtransferId/generation/source/targetと `current.nbt`・SHA-256一致、関連する `lease.json` と両サーバーのonline/offline、送信元escrow、双方の最後に適用したgeneration/transferId、バックアップ時点、保留中処理を確認。矛盾/欠損/同時ログインがあれば操作を中断。
- **`ROLLED_BACK` を選べる条件:** 宛先で当該transferId/generationが未適用であり、プレイヤーデータにも当該アイテム・経験値が反映されていないことを両側で確認できる。送信元のスナップショットが完全で、適用済み世代より新しいデータを上書きせず、二重復元も発生しない。どれか一つでも不明ならこの遷移を選ばない。
- **`IMPORTED` を選べる条件:** 宛先に正しいtransferId/generationの適用証跡があり、対象データが宛先に存在することを確認できる。送信元が未復元・escrow状態であることも検証し、宛先の保存完了確認・commit retryへ進める。実保存が確認できないまま`COMMITTED`扱いにしない。
- **`ADMIN_REVIEW` を選ぶ条件:** 上記のいずれも証明できない、SHA/世代/leaseが不整合、宛先適用済みの可能性が否定できない、または証拠が欠ける場合。データを保全し、OP/管理者が個別に判断するまで自動復元しない。
- 各遷移は現行実装の対応コマンドとテストの有無を確認し、Operator承認・前後スナップショット/監査・復旧後のアイテム差分0を必須とする。

7. Release前は `docs/player-bridge/test-plan.md` に沿って正常往復、アイテムData Components、Backpack、満杯Inventory、連打、切断、Crash、replay、20回連続往復、Admin権限の拒否を検証。

## 公開/承認ゲート

- Phase 0〜4の検証、データ差分0、二重適用・複製・消失0、手動往復テストを満たすまで一般公開しない。
- Main/Resource JAR SHA-256一致、接続先、Docker/Network、Backup、Resource停止からの起動を確認。
- Liveプレイヤーデータ操作、admin rollback/unlock、本番のワールド再生成・削除、Bridgeの公開設定は明示human approval必須。
- MCP/SSH接続の存在だけでwrite permissionは付与されない。Secrets・Inventory NBT全文をログへ送信しない。

## 結果

影響した世代・原因仮説、保存証跡、救済方針と承認、テスト・復元の結果、残課題/Runbook更新をPR/Issueへ記録。
