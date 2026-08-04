# IVRM Player Bridge データ契約

## 1. 保存単位

プレイヤーUUIDごとに1つの現在転送レコードと履歴を持つ。

```text
/bridge/players/<uuid>/
├─ current.nbt
├─ current.sha256
├─ state.json
├─ lease.json
└─ history/
   └─ <generation>-<transferId>.nbt
```

## 2. 転送レコード

NBTルートに以下を保存する。

| キー | 型 | 説明 |
|---|---|---|
| `schemaVersion` | int | データ形式のバージョン |
| `transferId` | string | 転送単位のUUID |
| `playerUuid` | string | 対象プレイヤーUUID |
| `playerName` | string | 監査用の最終確認名 |
| `sourceServer` | string | `main`または`resource` |
| `targetServer` | string | `main`または`resource` |
| `generation` | long | プレイヤーごとの単調増加世代 |
| `createdAt` | long | Unix epoch milliseconds |
| `updatedAt` | long | Unix epoch milliseconds |
| `selectedSlot` | int | 選択中ホットバースロット |
| `experienceLevel` | int | 経験値レベル |
| `experienceProgress` | float | レベル内進捗 |
| `totalExperience` | int | 合計経験値 |
| `inventory` | list | メイン・ホットバーのItemStack |
| `armor` | list | 防具4枠のItemStack |
| `offhand` | list | オフハンドのItemStack |

ItemStackはMinecraft 26.1.2の正式なシリアライズAPIを使用し、MODアイテムのData Componentsを保持する。独自にアイテムIDと個数だけへ変換しない。

## 3. 状態ファイル

`state.json`は運用・復旧判断用の小さなメタデータとし、アイテム本体は含めない。

```json
{
  "schemaVersion": 1,
  "transferId": "00000000-0000-0000-0000-000000000000",
  "playerUuid": "00000000-0000-0000-0000-000000000000",
  "sourceServer": "main",
  "targetServer": "resource",
  "generation": 1,
  "state": "IN_TRANSIT",
  "payloadSha256": "...",
  "createdAt": 0,
  "updatedAt": 0,
  "lastError": null
}
```

## 4. 状態値

| 状態 | 意味 |
|---|---|
| `IDLE` | 保留中転送なし |
| `PREPARED` | スナップショット確定済み、送信元未クリア |
| `IN_TRANSIT` | 送信元クリア済み、移動先未適用 |
| `IMPORTED` | 移動先へ適用済み、保存確認待ち |
| `COMMITTED` | 移動先保存済み、転送完了 |
| `ROLLED_BACK` | 送信元へ復元済み |
| `RECOVERY_REQUIRED` | 自動判断不可、復旧操作が必要 |
| `ADMIN_REVIEW` | OP確認が必要 |

## 5. 書き込み手順

1. 同じディレクトリに一時ファイルを作成する
2. NBTを最後まで書き込む
3. SHA-256を計算する
4. 一時ファイルを確定させる
5. `current.nbt`へ原子的renameする
6. `current.sha256`を原子的renameする
7. 最後に`state.json`を更新する

`state.json`が新しいのにペイロードが古い状態を作らない。状態更新は常にペイロード確定後に行う。

## 6. ロック契約

`lease.json`例:

```json
{
  "playerUuid": "00000000-0000-0000-0000-000000000000",
  "server": "main",
  "instanceId": "mc-main",
  "leaseId": "00000000-0000-0000-0000-000000000000",
  "acquiredAt": 0,
  "heartbeatAt": 0,
  "expiresAt": 0
}
```

- ログイン時にプレイヤー単位のリースを取得する
- オンライン中は定期的にheartbeatを更新する
- 有効な別サーバーリースがある場合はログインを拒否する
- TTL切れだけでは即時削除せず、対象サーバーが停止中または対象プレイヤーがオフラインであることを確認する
- 強制解除は監査ログへ残す

## 7. 適用済み世代

各サーバーはプレイヤーごとに最後に適用した`generation`と`transferId`を保存する。

- `generation`が最後の適用世代以下なら拒否
- 同じ`transferId`なら拒否
- `targetServer`が自分と一致しなければ拒否
- SHA-256不一致なら拒否
- UUID不一致なら拒否

## 8. 復旧ルール

### PREPARED

送信元インベントリがまだ残っているため、転送を破棄して`ROLLED_BACK`へ変更できる。

### IN_TRANSIT

移動先で未適用と確認できた場合のみ、送信元へ復元できる。移動先適用済みの可能性がある場合は`ADMIN_REVIEW`へ移す。

### IMPORTED

通常の送信元復元は禁止する。移動先の保存完了を再試行する。

### COMMITTED

完了済み。履歴参照だけ許可する。

## 9. 保持期間

- 現行レコード: 無期限
- 正常完了履歴: 30日または20世代の多い方
- 異常終了履歴: 管理者が解決するまで保持
- ログ: 30日を基本とする

## 10. 互換性

`schemaVersion`が未対応の場合は自動適用せず、`ADMIN_REVIEW`へ移す。MOD更新時に旧形式を破壊的に上書きしない。
