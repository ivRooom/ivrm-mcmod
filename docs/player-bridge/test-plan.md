# IVRM Player Bridge テスト計画

## 1. 方針

本番プレイヤーデータを使わず、専用テストユーザーと複製した検証環境で実施する。各テスト前後にインベントリのスロット、個数、耐久値、エンチャント、Data Components、経験値をダンプし、差分を比較する。

## 2. 段階

### Phase 0: ビルド・起動

- Java 25でビルド成功
- Minecraft 26.1.2 / NeoForge 26.1.2.81でMain役・Resource役の両方が起動
- クライアントへMODを追加せず接続可能
- `/resource`はMainだけ、`/main`はResourceだけで使用可能
- Bridgeストアが未マウントの場合はサーバーを安全停止または機能無効化

### Phase 1: 正常系

1. 空インベントリでMain→Resource→Main
2. 丸石1個で往復
3. 複数スタックで往復
4. 満杯インベントリで往復
5. 耐久値が減った道具で往復
6. エンチャント道具で往復
7. 名前・説明文付きアイテムで往復
8. 防具4枠とオフハンドを含めて往復
9. 経験値を含めて往復
10. Resourceで採取したアイテムをMainへ持ち帰る

### Phase 2: MODアイテム

- Sophisticated Backpack空状態
- Sophisticated Backpack中身入り
- SecurityCraftアイテム
- Project MMO関連アイテム
- Artifacts装備品
- Aquaculture 2の釣り具・魚
- Waystones関連アイテム
- AE2アイテム

中身入りSophisticated Backpackは、往復後に中身・アップグレード・設定がすべて一致するまで正式サポートしない。

### Phase 3: 異常系

- `/resource`連打
- `/main`連打
- 転送直前にクライアント切断
- Resource停止中に`/resource`
- Resource起動途中に再接続
- 共有ストア書き込み不可
- ペイロードSHA-256不一致
- 古いgenerationの再適用
- 同じtransferIdの再適用
- Main再起動中の転送
- Resource再起動中の転送
- `IN_TRANSIT`でサーバープロセス停止
- `IMPORTED`直後にサーバープロセス停止
- Bridgeストアの一時ファイルだけ残る
- leaseだけ残る

### Phase 4: 複製・消失耐性

- 送信元と送信先へ同時ログインを試行
- transfer失敗後に送信元へ再接続
- transfer成功後に送信元へ直接再接続
- 同一転送レコードを手動で再配置
- 期限切れleaseを再利用
- 20回連続往復
- 100回自動往復相当の単体テスト

## 3. 合格基準

- 正常系でアイテム差分0
- 正常系で経験値差分0
- ItemStackのシリアライズ・復元でData Components差分0
- 二重適用0件
- アイテム複製0件
- アイテム消失0件
- 未確定状態は必ず復旧可能または`ADMIN_REVIEW`へ停止
- 例外発生時にサーバークラッシュしない
- ログへアイテムNBT全文・秘密情報を出さない

## 4. 本番導入前チェック

- Mainのバックアップ作成
- Resourceはリセット可能な検証ワールドを使用
- Bridgeストアの権限・SELinuxラベル確認
- Main/Resourceで同じMOD JAR SHA-256を確認
- コマンド権限確認
- ロールバック手順確認
- OPが`inspect`・`unlock`・`rollback`を実行できる
- 一般ユーザーが管理コマンドを実行できない

## 5. 公開判定

Phase 0〜4をすべて合格し、最低1人の実利用者による手動往復テストを実施してから一般公開する。
