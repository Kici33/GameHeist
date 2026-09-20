# Lobby queues and exact-backend routing

Issue #10 now has an opt-in Paper lobby/backend adapter for **practice matches only**. It implements consent-based crews, durable queues, backend registration, exact-target transfer and reservation consumption. It does not enable production rewards or claim real-client/proxy acceptance.

## Configure a staging network

Use one trusted BungeeCord proxy, one lobby and one or more dedicated Paper 26.1.2 backends with the same plugin build. Keep backend ports private and configure trusted UUID forwarding using the proxy/Paper deployment documentation. This adapter trusts the authenticated UUID supplied by that infrastructure; it is not a replacement for forwarding security. Do not expose offline-mode backend ports to clients.

All servers need `storage.mode: mongodb`, the same `storage.database` and `HEIST_MONGODB_URI` pointing at a MongoDB replica set. Allocation and recovery use transactions with snapshot reads and majority writes; standalone MongoDB is insufficient. Use unique health ports when running several processes on one host. Install the same arena manifests and required resource pack across servers; turn off the development pack bypass for acceptance testing.

Lobby config:

```yaml
network:
  role: lobby
  server-id: lobby-1
  arena: brasslock_bank
  arena-version: 1
storage:
  mode: mongodb
  database: gameheist
```

Normal backend config:

```yaml
network:
  role: backend
  server-id: heist-normal-1
  arena: brasslock_bank
  arena-version: 1
  difficulty: NORMAL
instances:
  maximum: 1
  fallback-world: world
storage:
  mode: mongodb
  database: gameheist
```

Each `server-id` must exactly match its entry in the proxy's server map. A second Hard backend uses a different ID and `difficulty: HARD`. A backend offers one arena version/difficulty and prepares an empty practice world before claiming a crew. No matching backend means the crew waits until its two-minute admission deadline. Default `network.role: disabled` preserves isolated local practice. `/heist create`, `join` and `start` are disabled in network mode, including namespaced command dispatch.

Transfers use the official outgoing `BungeeCord` plugin channel, with the `Connect` subchannel and the exact allocated server name. The plugin does not accept client-supplied grants or incoming routing messages. See [Paper's plugin messaging reference](https://docs.papermc.io/paper/dev/plugin-messaging/). BungeeCord's request has no application-level arrival acknowledgement: Mongo admission consumption is the acknowledgement, and failed transport is reconciled against the same durable reservation.

## Player flow

1. Join the lobby; wait for the pack and acknowledged profile load. Choose saved loadouts before queuing.
2. The leader uses `/heistnet invite <online-player>`. Each invited player runs `/heistnet accept` within 30 seconds. Crews contain one to four consenting players. `/heistnet leave` leaves an unqueued crew; leader departure dissolves it.
3. `/heistnet status` shows the crew and confirmed reservation state. `/heistnet queue NORMAL` or `HARD` submits an immutable snapshot of the whole crew's selected loadouts. The graphical queue menu also offers these choices.
4. Edits and profile reloads are frozen while queued. Old route responses cannot unlock a newly submitted crew. An uncertain queue write keeps the crew frozen through its admission window, then reconciles durable state.
5. A compatible backend atomically claims the oldest waiting crew. It binds the reservation to its prepared match before publishing readiness. Lobby polling sends each member to that exact backend.
6. Backend entry requires the same server ID, process incarnation, match UUID and generation; successful pack application; atomic admission consumption for this connection attempt; and an acknowledged profile load. The match starts automatically only after the complete reserved crew arrives before the deadline.
7. `/heistnet cancel` cancels only an entirely unclaimed reservation. Once any admission has been consumed, the crew stays reserved until backend finalization or dead-backend recovery. A reconnect receives a fresh attempt ID and cannot reuse an earlier consumed admission.
8. An admitted disconnect retains its immutable backend slot for 90 seconds. A consumed reservation routes only that original participant back to the assigned live backend, where the current connection generation is checked before the same state is resumed. After saved results and cleanup, players transfer to a registered healthy lobby.

## Authority and failure behavior

- MongoDB is the authority. One active crew per player is protected by a unique index. A transaction serializes allocation against the exact server registry row and prevents two match IDs from occupying one incarnation. Repeating allocation for the same target returns the same reservation.
- Server names have 20-second database-time leases, renewed every five seconds. A second process cannot take a live name. Local admission stops after 15 seconds without a sufficiently recent heartbeat request acknowledgement; a delayed reply cannot revive a stalled owner thread. Match UUIDs are fresh for every prepared world; process incarnations are fresh after restart. The current per-match generation is 1 because this adapter never reuses a match UUID.
- Network profile repositories claim a fresh ownership token on an authorized connection's load. Saves require both that token and the expected revision, fencing old lobby/backend writers even if revisions match. Raw standalone profile writers cannot overwrite a network-owned document. All servers sharing these profiles must use network mode; switching existing network-owned profiles back to standalone writing requires an explicit migration, not simply toggling config.
- Profile claims are made only after the lobby confirms no active reservation or the backend confirms admission. In-flight results for disconnected connections are ignored; closed tokens cannot start new writes. Proxy authentication and one live connection per player are required. These are server-internal APIs, not public admission endpoints.
- Failed/partial transfers keep the reservation until its deadline. The healthy backend aborts incomplete briefing, saves the practice result, cleans the world and releases the reservation. A lobby may recover a partially claimed crew only after the exact assigned process loses its lease. Recovery is serialized with heartbeat/takeover; it never releases an active crew merely because its admission deadline passed.
- Unknown allocation outcomes stop that backend's admission and heartbeats rather than permit a second allocation. Restore service and restart it. Local failure attempts a healthy-lobby transfer; after five seconds, remaining connections are disconnected with a retry message. Hard process/proxy loss still relies on proxy fallback or client reconnect through a lobby; that lobby reconciles the durable reservation.
- An unverified backend connection is held outside gameplay while its route/pack/profile is checked. Normal completion waits for result acknowledgement and world cleanup. Failed storage/cleanup keeps the instance occupied; it is not silently made available.
- Polling reconciles routes every two seconds. There is no Redis session/cache dependency or disposable-message correctness path in this implementation. Redis notifications may later reduce polling, but must preserve this durable reconciliation path.
- Queue submissions and tracked local connections are bounded to 128 each; crews to four. Database work uses the existing bounded worker pool. Startup registration failure, drain and lease loss close admission. An empty prepared backend counts as ready for allocation; an occupied/finalizing backend does not.

## Automated evidence and remaining acceptance

`gradlew test` covers consent, expiry, crew capacity, queue locks and profile freeze/reload rules, together with the existing reservation/runtime admission tests. Enable `HEIST_REWARD_TESTS=true` against the disposable local replica-set fixture on `127.0.0.1:27029` to run `MongoNetworkIntegrationTest`. Its six tests cover competing allocations, one-slot fencing across match IDs, arena/difficulty ordering, readiness publication, duplicate connection attempts, lease takeover, partial-transfer recovery, cancellation/expiry, healthy-lobby selection and stale profile writes. They also verify recovery creates no progression reward.

On 2026-09-16, the full build passed with both Mongo opt-in suites enabled against isolated native MongoDB 8.0.12 fixtures (replica set on 27029; standalone persistence fixture on 27028). This is database/runtime evidence, not a BungeeCord or Minecraft-client test.

Before closing issue #10, run and record these staging checks:

- Two compatible backends race for one crew; all four clients arrive on one exact backend, once, with the queued loadouts. Repeat Normal/Hard and one-to-four-player crews.
- Refuse, delay or break one transfer; the arrived subset must not start. Cancel before any claim; then attempt cancellation after a claim and verify refusal, eventual cleanup and retry.
- Reconnect before/after consumption; replay an old assignment after backend restart; edit/reload through commands and already-open menus during queue/transfer. No stale write, duplicate entry or reward may occur.
- Stop a backend, stop the proxy, interrupt MongoDB and pause the server owner thread longer than its lease. Restore service; verify lobby fallback or explicit disconnect, durable recovery, no second match under the old incarnation and no stranded playable world.
- Finish a match and force result/cleanup retries. Return only after acknowledged cleanup; verify inventory restoration, resource-pack gates, UI messages and health readiness on real clients.
- Confirm forwarding/network isolation, measure warm allocation-to-ready latency and polling load. Production reward transport, Agones allocation, Redis acceleration, live-match reconnect grace and versioned fleet deployment remain separate unfinished work.
