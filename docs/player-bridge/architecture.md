# IVRM Player Bridge アーキテクチャ

## 1. 目的

生活鯖（Main）と資源鯖（Resource）を別コンテナ・別ワールドのまま維持し、プレイヤーが持っている道具、装備、採取物を安全に引き継いで移動できるようにする。

## 2. 対象環境

| 項目 | Main | Resource |
|---|---|---|
| コンテナ | `mc-main` | `mc-resource` |
| Minecraft | 26.1.2 | 26.1.2 |
| NeoForge | 26.1.2.81 | 26.1.2.81 |
| Java | 25 | 25 |
| 接続 | 25565/TCP | Router経由25999/TCP |
| MOD | 80 JAR | Mainから固定コピーした80 JAR |
| Voice Chat | 24454/UDP | 24455/UDP |
| ワールド | `world` | `resource` |

## 3. 採用しない方式

### `world/playerdata`の直接共有

採用しない。両サーバーが同じUUIDのファイルを読み書きすると、キャッシュされた古い状態による上書き、座標・ディメンション混在、アイテム複製、消失、ワールドリセット後の不正位置復元が発生し得るため。

### 座標・ディメンションの同期

採用しない。移動先では各サーバーの安全なスポーン地点を使用する。

### インベントリを保持したまま転送

採用しない。転送失敗時や二重ログイン時に複製できるため、耐久性のあるスナップショット保存後に送信元の対象データをエスクロー状態へ移す。

## 4. 全体構成

```text
Main Server MOD
    │
    ├─ /resource
    │    ├─ プレイヤーロック取得
    │    ├─ インベントリをスナップショット化
    │    ├─ 共有ストアへ原子的に保存
    │    ├─ 送信元インベントリをクリア
    │    └─ Resourceへtransfer
    │
    ▼
Shared Bridge Store
    /bridge/players/<uuid>/
      current.nbt
      current.sha256
      lease.json
      history/
    ▲
    │
Resource Server MOD
    ├─ ログイン時に転送データ検証
    ├─ インベントリ適用
    ├─ 適用済み世代を記録
    └─ /mainで逆方向へ同じ処理
```

共有ストアは同じOCIホスト上の専用ディレクトリを両コンテナへ読み書き可能でマウントする。ファイルの確定は一時ファイル作成後の原子的renameで行い、同一プレイヤーの処理はロックファイルで直列化する。

## 5. 同期対象

初期リリースでは以下を対象とする。

- メインインベントリ
- ホットバー
- 防具4枠
- オフハンド
- 選択中スロット
- 経験値レベル・進捗・合計値
- ItemStackのData Components/NBT

次は初期リリースでは同期しない。

- 座標
- 向き
- ディメンション
- リスポーン地点
- ベッド位置
- エンダーチェスト
- 体力・満腹度・ポーション効果
- 統計・実績
- Project MMOなど各MOD固有のプレイヤー能力値

Sophisticated Backpackの中身がItemStack内のData Componentsだけで完全に保持されるかは、実機テストで確認する。確認できるまで中身入りバックパックを正式サポート対象としない。

## 6. コマンド

| コマンド | 実行場所 | 用途 |
|---|---|---|
| `/resource` | Main | Resourceへ移動 |
| `/main` | Resource | Mainへ帰還 |
| `/ivrmbridge status` | 両方 | 自分の転送状態確認 |
| `/ivrmbridge resume` | 両方 | 保留中転送を再開 |
| `/ivrmbridge recover` | 送信元 | 失敗した転送を復元 |
| `/ivrmbridge admin inspect <player>` | OP | 状態・世代・ロック確認 |
| `/ivrmbridge admin unlock <player>` | OP | 期限切れロック解除 |
| `/ivrmbridge admin rollback <player> <generation>` | OP | 指定世代を復元 |

一般ユーザーの`recover`は、対象転送が未適用であることを共有ストアから確認できる場合だけ許可する。

## 7. 状態遷移

```text
IDLE
  ↓ スナップショット保存
PREPARED
  ↓ 送信元データをエスクロー化
IN_TRANSIT
  ↓ 移動先ログイン・検証
IMPORTED
  ↓ 移動先プレイヤーデータ保存確認
COMMITTED
  ↓ 履歴化
IDLE
```

異常系:

```text
PREPARED   → ROLLED_BACK
IN_TRANSIT → RECOVERY_REQUIRED
IMPORTED   → COMMIT_RETRY
任意状態    → ADMIN_REVIEW
```

`generation`はプレイヤーごとに単調増加させる。移動先は、自分が最後に適用した世代以下のデータを拒否する。

## 8. 複製防止

- プレイヤーUUID単位の排他ロック
- 転送IDはUUIDで一意
- 世代番号は単調増加
- ペイロードSHA-256を保存
- 送信元クリア前にスナップショットをfsync相当で確定
- 移動先適用前に送信元・送信先・世代・チェックサムを検証
- 同じ転送IDを二度適用しない
- `IMPORTED`後は通常の送信元復元を禁止
- 古いロックの解除はTTLとOP操作を併用

## 9. 接続と自動起動

Resourceは`mc-resource-router`が25999/TCPで待機し、停止中の`mc-resource`を自動起動する。初期実装ではMODからDocker APIを操作しない。

`/resource`実行時はRouterへtransferする。Resourceの起動が間に合わず接続に失敗した場合、転送レコードを`IN_TRANSIT`のまま保持し、再接続または`/ivrmbridge recover`で復旧する。

## 10. セキュリティ

- Bridgeストアは外部公開しない
- 両コンテナのUID/GIDを1000へ統一
- SELinux対応bind mountを使用
- ストア権限はディレクトリ0750、データ0640を基本とする
- ログへアイテムNBT全文や秘密情報を出さない
- 管理コマンドはOPまたは専用権限ノードに限定

## 11. 公開条件

以下をすべて満たすまで一般公開しない。

- 空インベントリ往復
- 通常アイテム往復
- エンチャント・耐久値付き道具往復
- 防具・オフハンド往復
- 経験値往復
- スタック上限・満杯インベントリ往復
- 中身入りSophisticated Backpack検証
- Resource停止状態からの移動
- transfer失敗時の復元
- サーバー再起動中の復元
- 二重実行・連打防止
- 同一アカウントの二重ログイン防止
- 20回以上の連続往復で差分なし
