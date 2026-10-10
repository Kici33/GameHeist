# Database and telemetry acceptance (#12)

The build workflow runs the opt-in suites against disposable MongoDB 8.0.12 (standalone on port 27028 and replica set on 27029) and InfluxDB 2.7.12 (port 18086). It uses fixture credentials and randomly named databases, never the application's database URI or telemetry token.

`ops/acceptance/report.py` fails the acceptance step if any expected suite is absent, empty, skipped or unsuccessful. The `test-reports` workflow artifact contains the Gradle log, JUnit XML, HTML reports, acceptance summary and service logs. The job summary shows individual suite counts. A normal local build with the environment switches disabled does not establish service acceptance.

Coverage:

| Area | Evidence |
|---|---|
| Profiles | Concurrent creation, competing revision updates, identical retry, new-client persistence, future-schema rejection |
| Results/statistics | Immutable result retries, scoped records, combat contributions, objective contributions and gameplay duration across client restart |
| Rewards | Real replica-set transactions, replay, competing recovery workers and exactly-once receipt recovery |
| Database outage | Real wire connections severed through a loopback gate; admission fails, explicit reload recovers using the same client |
| Ambiguous profile write | Commit to MongoDB, suppress acknowledgement, reconnect waits then reads the committed replacement and clears the acknowledgement barrier |
| Result finalization | Commit to MongoDB, lose acknowledgement, retain instance/world until identical retry succeeds, store one result and release once |
| Reconnect | Old load completions cannot replace current profiles; superseded reconnects cannot start profile claims after pending writes; claims start on the owner tick |
| Capacity/drain | Two blocked storage workers plus 128 accepted queued tasks; immediate overflow rejection, shutdown refusal and completion of all accepted tasks |
| Telemetry | Live write/query, transient unavailable sink, bounded warning count, recovery on the same exporter, slow sink retains only the newest queued sample |

The wire gate and HTTP proxy affect only test-owned sockets. The result/profile acknowledgement tests deliberately inject lost replies after real durable writes, avoiding uncertain timing of packet loss around a commit. Service process restart is not implied by opening a fresh application storage client.

## Remaining hard-crash window

There is no local write-ahead journal. A forced process kill before durable acknowledgement can lose an operation that has not committed; a lost reply can also leave the caller unable to distinguish a committed write. Profiles reconcile an ambiguous write by a fresh read of the exact replacement. Ordinary draining waits for pending profile work, unresolved writes, result acknowledgement and world cleanup; an unresolved write keeps the barrier closed.

Once a result/reward job is committed, immutable retry and durable reward receipts prevent duplicate results/progression, and a new storage client can recover pending rewards. This does not recover a terminal result that never reached MongoDB. Telemetry is best effort: failed, superseded and shutdown-interrupted samples may be lost and are not a gameplay drain requirement.
