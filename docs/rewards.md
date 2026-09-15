# Transactional rewards and recovery

`MongoStore.rewards()` is the production reward boundary. Call
`accept(reservationId, authenticatedBackendAssignment, result)` from a trusted
backend transport. Do not accept caller identity from a player/client payload.
The current Paper game remains practice-only and does not call this production
API. `save(MatchResult)` alone never queues rewards.

Acceptance checks the exact server incarnation, match and generation, full
admitted roster, immutable loadouts, arena and difficulty against the reservation.
A transaction writes the reservation fence, immutable terminal result and one
pending job per rewarded participant. A concurrent reservation release conflicts
with that fence. Identical retries return successfully, including after release;
changed payloads and stale generations fail. Jobs cannot appear without a durable
result, and a failed transaction leaves neither result nor jobs partially saved.

Policy version 1 grants production wins `100 + 25 × secured bags` XP per player.
Practice, losses and infrastructure aborts grant none. The first win unlocks the
`brass` cosmetic reference and five wins unlock `veteran`. Values are provisional
balance choices. They confer no combat advantage. Unlock references are stored;
equipping/rendering these cosmetics is still part of the player/content UI work.

Recovery processes at most 16 jobs per Paper worker pass, with a five-second gap
and one outstanding batch per process. Multiple processes can recover together.
In one MongoDB transaction it validates the job against the durable result,
inserts the unique `match/player/version` receipt, increments progression, adds
cosmetic unlocks and marks the job complete. Both receipt and progression commit
or neither does. An existing matching receipt prevents another increment, even
when a scan replays a completed job after an acknowledgement was lost.

The driver retries transient transaction errors and unknown commit outcomes.
Failures left unresolved are deferred 30 seconds with `attempts`, `retryAfter` and
`lastError` in `reward_jobs`, allowing later jobs to proceed. Inspect pending jobs
with repeated failures; do not delete receipts to retry work. Do not expire
receipts independently of their results. The next process recovers durable jobs;
there is no in-memory queue whose loss can erase accepted rewards.

`player_progression` is separate from editable profile preferences. Profile CAS
writes therefore cannot replace reward totals with stale snapshots. Reward jobs
belong to the acknowledged match, not whichever lobby session happens to be
connected later. Network-wide profile session leases remain in issue #10; this
API verifies the existing durable match reservation ownership.

`/heist progression` reports acknowledged XP, wins, cosmetic references and pending
rewards. Its read uses a consistent transaction snapshot. Replies are polled on
the server thread, bounded and discarded after disconnect/replacement connection.
Memory storage cannot claim durable progression. Practice result messages remain
explicitly reward-free.

## Deployment and verification

MongoDB transactions require a replica set or supported sharded deployment.
Standalone MongoDB must not be used for production reward acceptance. Keep all
reward collections in the configured application database with majority writes.
The API is not exposed as an unauthenticated HTTP or player command endpoint.

For isolated integration tests, start MongoDB 8.0.12 bound to loopback on port
27029 with `--replSet heist-test`. Tests initialize that disposable replica set,
use random `heist_reward_test_*` databases and drop only those fixtures. Set
`HEIST_REWARD_TESTS=true` and run `:heist-mongo:test`. CI starts its own pinned
container. Never redirect the fixture to an application database.

Locally verified on a native MongoDB 8.0.12 replica set:

- 100 result submissions, recovery after opening a new store, and 100 subsequent
  result/recovery replays apply XP once.
- Concurrent worker clients and replayed pending flags do not duplicate effects.
- Stale generations, practice and conflicting payloads are rejected.
- Profile preference saves preserve independently committed progression.
- A corrupt job creates no receipt or progression and remains pending.
- A database validation failure after receipt insertion rolls back both writes;
  removing the failure allows recovery to apply the reward once.

These tests do not simulate a process kill during the driver's commit packet or
a replica-set election/network partition. Production transport wiring, complete
profile leases, cosmetic rendering and real-client pending/saved reward acceptance
remain before issue #7 can be closed.
