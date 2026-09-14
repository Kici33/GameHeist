# Player storage

`storage.mode` is `memory` by default and logs that data is lost on restart. Set it to `mongodb` and provide `HEIST_MONGODB_URI` to use persistent storage. The database name is configured separately as `storage.database`. The plugin bundles MongoDB Java sync driver 5.5.1, so it needs no runtime library download. MongoDB 8.0.12 is the tested local fixture, not a production deployment recommendation.

Collections:

| Collection | Identity | Write rule |
|---|---|---|
| `profiles` | String player UUID in `_id` | Schema 1, revision comparison, replacement revision = expected + 1 |
| `match_results` | String match UUID in `_id` | Immutable insert; identical retry accepted, conflicting retry rejected |

The built-in unique `_id` index enforces identity. Profile creation tolerates concurrent inserts. Unknown profile schema versions fail without resetting or overwriting data. Stored presets must also pass the current equipment catalog before admission. Result arrays are sorted for deterministic retry comparison, and timestamps retain their full precision as ISO strings.

Combat-enabled results add an optional `combatStats` array to schema 1, sorted by player UUID. Each entry records `playerId`, `damageDealt`, `damageTaken`, and `revives`; damage counts actual HP removed, and a revive is credited only on completion. Non-combat results omit the field to preserve existing retry identity. Contributions are frozen with the terminal result and appear in in-game feedback. The current statistics aggregation does not sum these fields. Serialization tests cover the new shape; a live MongoDB/client verification remains separate.

Profile writes use a conditional replacement without upsert, following the driver's [replacement operation](https://www.mongodb.com/docs/drivers/java/sync/current/crud/replace-documents/). Reads use primary/majority and writes use majority acknowledgement. This is optimistic concurrency, not a distributed player lease. A server with an already-loaded profile does not receive edits made elsewhere; network admission and cross-server synchronization remain future work.

Database operations run on two workers with a 128-entry queue and a five-second operation timeout. Queue saturation fails the returned future; it never runs database work on the caller. Profile failures require an explicit reload. Match finalization retries the same result and retains instance capacity until acknowledgement. `/ready` reports process capacity and drain state, not database reachability; profile admission separately requires a successful load.

Shutdown rejects new database work and lets accepted work drain on daemon workers. It does not block Paper waiting for storage. A process kill can lose unacknowledged operations; there is no local write-ahead journal or crash recovery. A Kubernetes termination grace period alone is not proof of flush completion. Use the implemented [drain/acknowledgement protocol](draining.md) before ordinary shutdown; integration into a Kubernetes lifecycle hook is still pending. Saved results are not reward receipts, and no rewards are applied. Statistics are read-time totals derived from these results.

## Isolated integration tests

The tests never use `HEIST_MONGODB_URI`. They connect only to localhost port 27028 and create/drop randomly named `heist_test_*` databases. Use a disposable fixture:

```powershell
docker run --detach --rm --name gameheist-storage-test -p 127.0.0.1:27028:27017 mongo:8.0.12
$env:HEIST_MONGO_TESTS = 'true'
.\gradlew.bat build --max-workers=1
docker stop gameheist-storage-test
Remove-Item Env:HEIST_MONGO_TESTS
```

The fixture verifies concurrent creation, competing profile writes, identical retries, persistence across clients, conflicting results, unknown schemas, and rejection after shutdown. Without the environment switch, seven integration tests are skipped; pure serialization and profile-session tests still run. CI starts the same isolated MongoDB service and enables the switch.

## Client acceptance checks

1. In memory mode, connect, wait for `/heist profile`, edit all three slots, select a slot, and join without a role override. Confirm the selected role's gameplay behavior.
2. Confirm preset/settings edits and profile reload are rejected during briefing and gameplay. Confirm a practice role override changes only the captured match loadout.
3. Turn sound off in the lobby, join a guarded arena, and trigger an alarm. Verify the custom bell is muted while the visual warning remains.
4. In MongoDB mode, edit presets/settings, confirm the new revision, restart the server, and verify the same profile. Finish a match and inspect `match_results`; `/heist results` intentionally starts empty after restart.
5. Disconnect/reconnect during a pending save. Confirm admission waits for a fresh read and that the previous connection cannot replace the new profile.
6. Stop the disposable database while loading/saving. Confirm the server continues ticking, profile admission fails, and `/heist profile reload` recovers after storage returns. If a result save fails, confirm the instance remains finalizing and later cleans up exactly once.

Automated storage tests do not replace these Paper/client acceptance checks.

## Statistics queries

`StatisticsRepository` returns immutable `PlayerStatistics` for one player and `StatisticsScope`. MongoDB uses an indexed participant/practice/arena/difficulty filter, exact roster size, and a server-side aggregation with a three-second execution limit. See the driver's [aggregation examples](https://www.mongodb.com/docs/drivers/java/sync/current/aggregation/aggregation-examples/) for the match/group operations. The first query creates the compound index on a storage worker; the database account needs index-creation permission. Failure is reported as unavailable and a later request retries initialization.

The query reads schema-1 results and makes no profile or counter writes. It needs neither a receipt nor a transaction to avoid duplicate counts, because each immutable result has a unique match ID. This is an initial query model; repeated scans of long player histories may eventually justify materialized totals and a transactional projection worker. Retaining the result history is necessary to retain these totals.

The command is self-only and practice-only, requires `heist.play`, and displays NORMAL difficulty explicitly. It limits pending requests to 128 and enforces a five-second per-connection cooldown. Only the owner thread polls futures or accesses players. Responses from disconnected sessions are discarded; a 15-second UI deadline releases a stalled request. MongoDB's worker queue and operation timeout still bound the underlying work.

Client follow-up: finish solo and two-player runs, query each crew-size partition, check abort/loss distinction, reconnect during a query, and verify no stale reply is delivered. Stop the disposable database and confirm an unavailable message rather than zero statistics. None of these in-game acceptance checks have been automated yet.
