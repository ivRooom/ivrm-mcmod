# ivrm-mcmod Agent Rules

- README、`docs/activity-sender.md`、`docs/player-bridge/`、`docs/permission-integration-migration.md`、main/Issue/PR/CIの最新状態を最初に確認する。
- ローカルSkill: [`.agents/skills/README.md`](.agents/skills/README.md)。共通手順: [ivrm-platform Agent Skills](https://github.com/ivRooom/ivrm-platform/tree/main/.agents/skills)。
- Minecraft MODの責務はProducer/Bridge/Permissionのみ。Canonical Activity/Reward/Ranking/IdentityのDB正本をMOD内へ移動しない。
- Main Worldやplayerdataを無断変更/再生成しない。転送の復旧時は複製と消失、journal耐久性の双方を保護する。
- 本番Senderの有効化、Secrets、World・Inventory修復、デプロイ、DNS/IAM/費用操作はhuman approval必須。
- Java 25/NeoForge、Gradle Build matrix、Contract conformance、CIを最新確認して実施済・未実施を分ける。資格情報・Player NBTを公開しない。
