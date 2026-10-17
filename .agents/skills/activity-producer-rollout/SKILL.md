---
name: activity-producer-rollout
description: ivrm-mcmodのActivity Event Producer実装、Contract conformance、durable journal、再送、段階的有効化・障害復旧を安全に扱う。
---

# Activity Producer Rollout

## 適用前確認

- `docs/activity-sender.md`、`contracts/ivrm/minecraft-activity.v1.bundle.json`、現行Java/Gradle、GitHub Issue/PR/CIを取得。
- [共通cross-service-integration](https://github.com/ivRooom/ivrm-platform/blob/main/.agents/skills/cross-service-integration/SKILL.md)、[data-migration](https://github.com/ivRooom/ivrm-platform/blob/main/.agents/skills/data-migration/SKILL.md)、[MCP接続](https://github.com/ivRooom/ivrm-platform/blob/main/.agents/skills/tool-access-and-mcp/SKILL.md) を併用する。
- 現在の正本: Producer MODは `ivrm-mcmod`、portable contractは `ivrm-contracts`、Consumerは `ivrm-web`、AWS PostgreSQLはimmutable ingest ledger、Supabaseはcanonical Activity state。実際のcurrent version / PR statusは毎回再取得。

## チェックリスト

1. Gameplay tickをブロックせず bounded in-memory ingress→背景dispatch→append-only durable queue journal（fsync）を維持する。`ingress.offer` 成功は揮発性メモリ内の受理に過ぎず、journal appendとfsyncの完了後にはじめてdurableとなる。最初のnetwork attempt前にdispatcherがfsyncを完了させる。fsync前のプロセス・ホスト停止でevent喪失の可能性がある。full時は拒否・観測し、無制限RAM化しない。
2. `eventId` / body `occurredAt` はretryでも不変。HTTP attemptごとに新しい `X-IVRM-Timestamp` とHMAC署名を生成する。
3. Receiver `202` は同一eventId・accepted・`replayed=false`、`200` は同一eventId・accepted・`replayed=true` を検証後にack/tombstone。曖昧なackではeventを消さない。
4. Queue/Dead-letter/Corrupt/Unreadable journalは永続性を守る。通常shutdownではingress閉鎖→`ingress.offer` に成功したeventのdurable flush→dispatcher停止の順序をテストし、flush失敗を成功扱いしない。API障害でもGameplayを止めない。
5. Eventのorderingはledger ingest順ではなくsource `occurredAt` 等のcontract条件で扱う。同時刻の異なるeventをIDやprocess-local sequence順で確定処理しない。
6. ProducerとConsumerの**同じcanonical bundle**でconformanceを検証。Contract freeze→互換Consumerの準備→Producer検証→Persistence migrationとReceiver deployment（疎通・durable accept確認）→Secret provision→限定Sender enableの順を明記。Receiver未準備でpermanent `4xx` が発生するとsenderはdead-letterへ移すため、先にsenderを有効化しない。
7. Java 25 + NeoForgeのCIビルドをBuild matrix全エントリ（`player-bridge` / `permission-integration`）で確認し、関係するテストと各エントリの実施済み・未実施を記録する。Deploy/sender enableはPR mergeとは別で計画する。

## 本番安全策

- Senderは原則 `IVRM_ACTIVITY_ENABLED=false` から開始。Live情報はHost read-only確認で判定、ドキュメントの記述だけで稼働中とは判断しない。
- Secretとjournalの中身、プレイヤー通信/個人データはPR/Issue/Agent outputへ貼らない。
- Provider MCPが無い場合は未接続と明記し、PR・Operator Runbookまでで止める。
- Receiver rollback時はProducer senderを先にdisable/quiesceし、queueとledgerを保持。順序無視のReplayや無承認のjournal削除は禁止。
- Production sender enable/Secret/DB migration/Deployは個別human approval必須。

## 結果

契約pin、CI/producer/consumer検証、queueの状態（集計のみ）、Rollout・Rollback・残課題、操作の承認/監査を記録。
