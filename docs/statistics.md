# Objective statistics, playtime and production rankings

`/heist stats <arena-id> <version> <crew-size> [NORMAL|HARD] [practice|production]`
retains its previous defaults (Normal/practice). It now displays recorded gameplay
milliseconds and the number of timed runs, plus personal objective actions and
personally secured bags. Scope always includes exact map/version, difficulty,
crew size and practice/production status. These are totals for retained records,
not a promise of lifetime totals after deliberate history eviction.

Objective actions are credited only for a successful security disable, first drill
installation, completed jam repair or bag deposit. Pickup/return, repeated clicks,
cancelled repairs, votes and automatic phase transitions earn no action credit.
The responsible player's immutable contribution is frozen with the terminal
result before persistence. Personal secured bags remain distinct from shared crew
bags. Legacy results without objective fields add no recorded contribution; this
means unknown historical contributions, not proof the player performed no work.

Gameplay duration excludes briefing as before. Aggregate duration sums only
recorded durations; `timedRuns/runs` exposes incomplete historical coverage. Missing
duration is never a zero-second best win. Optional objective serialization keeps
old result retry payloads unchanged. Repeated saves do not increment totals:
queries derive from the unique immutable results rather than mutable counters.

## Production eligibility and cache

`/heist leaderboard <arena-id> <version> <crew-size> [NORMAL|HARD]` returns the top
ten players by fastest timed production win, then win count descending, then UUID
ascending. Every crew member receives that run time within the exact crew-size
partition. The leaderboard's playtime sums eligible wins/losses; infrastructure
aborts are excluded from rankings. Scoped statistics can still report acknowledged
production aborts separately.

Only `MongoRewards.accept` creates a version-1 `result_eligibility` record, in the
same ownership-fenced transaction as the terminal result. A plain result insert
with `practice=false` cannot establish production provenance. Existing practice
and admin/debug runs are excluded; historical production rows without this
attestation are excluded until an operator independently verifies their origin.
There is no automatic legacy backfill or client-controlled validity flag.

`MongoStore.leaderboards().invalidate(matchId, reason)` is a trusted administrative
API. It retains the immutable result and an invalidation reason/time, removes the
result from production statistics and rankings, and clears the local ranking
cache. It is not exposed as a player command. Replaying the original result cannot
restore validity. This operation does not automatically claw back already granted
rewards; reward corrections require a separately reviewed administrative flow.

The process cache holds at most 64 scopes for ten seconds. New acceptance and
invalidation clear it locally; other processes reconcile on expiry. Queries time
out after three seconds and run on bounded storage workers. Cache loss, eviction
or process restart simply reruns the authoritative aggregation. Redis is not part
of correctness. UI requests have bounded concurrency, a five-second cooldown and
a timeout, and replies are discarded after disconnect or permission loss.

## Retention

`storage.practice-retention-days` is an integer from 0 to 36500; 0 disables expiry.
Newly inserted practice results receive `expiresAt = finishedAt + configured days`.
MongoDB TTL deletion is asynchronous. Existing rows retain their original policy;
changing this setting does not silently rewrite history. An identical retry with
a changed configuration retains its original expiry. Results without this field,
all authoritative production results, eligibility and reward receipts have no TTL.
Keep them together until a separately verified archival/compact-ledger design is
introduced; deleting production detail would change derived totals.

Memory mode remains an explicit bounded development history. Its eviction removes
old totals and deduplication entries; it is never a production leaderboard source.
The player command rejects production statistics/rankings without durable storage.

For operational log files on the operator host, `python ops/server/prune-logs.py
<logs-directory> --days 14` previews expired archives. Add `--apply` after reviewing
the list. The retention value is configurable; the tool touches only dated
`YYYY-MM-DD-N.log.gz` files in that exact directory. It preserves `latest.log`,
other files, directories and symlinks. No existing logs are deleted automatically
by this change. Configure an equivalent retention period in the cluster log
collector separately; ephemeral pod files are not durable audit storage.

## Verification

Domain tests cover contribution attribution, click/pickup deduplication, cancelled
and completed repair credit, immutable terminal contributions and aggregate time.
MongoDB/replica-set tests cover real authoritative acceptance, duplicate saves,
personal totals, missing legacy timing, scope separation, stable ties, cache
rebuild/restart, invalidation that survives replay, and practice-only expiry
metadata. The TTL test models eviction directly instead of waiting for the MongoDB
background monitor. Log-retention tests use disposable directories only.

Production match transport remains issue #10. Until it is connected, the live
practice game correctly produces no production leaderboard entries. Real-client
readability and large-history aggregation latency still need staging acceptance.
