# IVRM Permission Integration

Minecraft 26.1.2 / NeoForge 26.1.2.81向けのサーバー専用権限制御MODです。

`ivrm-dailytech-adventure/mods/ivrm-permission-control`から`ivrm-mcmod`へ責務を移管するための互換実装です。移行時の権限・設定互換性を優先し、MOD IDは`ivrm_permission_control`を維持します。

## 判定する権限

- `ivrm.play.build`: ブロック破壊・設置
- `ivrm.play.craft`: クラフト制御用予約ノード
- `ivrm.play.container`: コンテナ制御用予約ノード
- `ivrm.play.interact`: ブロック・アイテム・エンティティ操作
- `ivrm.play.combat`: Mob・プレイヤーへの攻撃
- `ivrm.play.pickup`: アイテム拾得・ドロップ

LuckPermsへ直接依存せず、NeoForge PermissionAPIを使用します。サーバー側でLuckPermsのPermission handlerが有効な場合、既存のLuckPermsノードをそのまま利用します。

## 現在のMVP制限

- ブロック破壊
- ブロック設置
- ブロックへの左・右クリック
- アイテム使用
- エンティティ操作
- Mob・プレイヤーへの攻撃
- アイテム拾得
- アイテムドロップ

権限判定に失敗した場合は安全側で拒否します。拒否メッセージには3秒間のクールダウンがあります。

`ivrm.play.craft`と`ivrm.play.container`は既存互換のため登録を維持していますが、インベントリ内2x2クラフトや全MOD独自GUIを個別に遮断する処理はまだ実装していません。

## ビルド

Java 25とGradle 9.2.1を使用します。

```bash
gradle --no-daemon clean build
```

Repository rootから実行する場合:

```bash
gradle -p mods/ivrm-permission-integration --no-daemon clean build
```

成果物:

```text
mods/ivrm-permission-integration/build/libs/ivrm_permission_control-0.1.0.jar
```

## 移行上の注意

旧`ivrm-dailytech-adventure`側のソースは、この実装のCI・Main/Resource実機確認・Manifest更新が完了するまで削除しません。
