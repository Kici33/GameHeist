# GameHeist

A Minecraft Java **26.1.2** cooperative heist portfolio project. The [GDD](GDD.md) describes the intended game. This repository now includes a **guarded graybox objective loop** with patrols, suspicion, and alarm escalation. It is not yet the complete combat minigame or network.

## Build

Requirements: Java 25 and an internet connection for the first dependency download. The Gradle 9.1.0 wrapper is included.

Windows:

```powershell
.\gradlew.bat clean build
```

Linux/macOS:

```sh
bash ./gradlew clean build
```

Install **`heist-paper/build/libs/heist-paper-0.1.0-SNAPSHOT.jar`** in a dedicated Paper **26.1.2** server's `plugins` directory. The artifact bundles all project modules and the pinned MongoDB Java driver; do not install those jars separately. The compile API is pinned to `26.1.2.build.74-stable`. Use a matching client. Paper's [Java requirements](https://docs.papermc.io/paper/getting-started/) and [project setup](https://docs.papermc.io/paper/dev/project-setup/) document this platform line.

Review and accept Minecraft's EULA yourself when setting up the server. No server is automatically started and no EULA acceptance is written by this project.

## What works

- Java-only arena, objective, player profile, loadout, match, alarm, and result models.
- Immutable versioned arena definitions, bounded crews, dependency graph validation, timeout handling, and explicit match states.
- Instance manager with one default slot, separate worlds/state per session, player membership indexing, drain, asynchronous result acknowledgement, and retryable cleanup.
- Reverse-order ownership/cleanup of per-instance resources.
- Paper bootstrap, YAML arena loading, operator commands, role/loadout snapshots, and generated graybox worlds.
- Physical security, a 90-second drill with two seeded jams, proximity repairs, bag transport, and majority-vote extraction in `graybox:2`.
- Labeled placeholder props, crew HUD, carrying slowdown, and result summaries with secured bag counts.
- Configured guard patrols, field-of-view/line-of-sight detection, noise investigation, interrupted alarm attempts, pursuit, and last-seen search in `graybox:3`.
- Staggered guard updates, bounded path requests, stuck retirement, scoped NPC cleanup, and operator diagnostics.
- Admission gated on successful pack application when the development bypass is off.
- Read-only heartbeat/capacity endpoints: `http://127.0.0.1:8081/live` and `/ready`.
- Lobby profile loading, three editable preset slots, saved settings, and revision-checked MongoDB profiles/results (opt-in).
- Regression tests, dependency locks, reproducible jar settings, and CI configuration.

The profile repository is tested but **not yet connected to player menus or the practice command**. Practice joins currently select a starter loadout directly.

## Try an isolated practice session

Use a dedicated **26.1.2** test server; the plugin rejects other runtime versions. Configure `instances.fallback-world` to an existing world; it defaults to `world`. Hosting/management commands require `heist.admin` (operator by default). Ordinary players have `heist.play` by default and can list arenas and join a crew.

1. Start Paper with the built plugin.
2. Run `/heist arenas`.
3. As the host, run `/heist create graybox 3` (or use version 2 for an unguarded objective test).
4. Copy the returned UUID or use tab completion.
5. Run `/heist join <uuid> SCOUT`. Other players may join the same crew (up to four).
6. Run `/heist start <uuid>`.
7. Right click the blue security marker, then the orange drill marker. Repair each jam by right clicking the drill and staying within five blocks. Repairs take six seconds, or 4.5 seconds for a Technician.
8. After the drill finishes, pick up a gold bag and right click the green marker to secure it. Carry one at a time; three bags are required, with two optional bags. Click green again to vote for extraction. A strict crew majority starts a 15-second countdown; at least one member must stay within five blocks of green when it ends. If nobody is there, voting resets and the crew can retry.
9. Inspect `/heist results` as host. Players return to their original location/game mode/walk speed; the world unloads and its files are deleted asynchronously.
10. Use `/heist list` for pending persistence/cleanup errors. Failed cleanup retains capacity and retries after five seconds.

Version 3 adds two guards and two stone cover walls. Guards spawn when the match starts. Stay out of their facing cone or break line of sight behind cover. Normal detection takes three seconds of sustained visibility followed by a two-second alarm attempt. Losing sight interrupts the attempt. Sneaking halves suspicion gain; Scout multiplies it by 0.75; sprinting increases it to 1.4 times normal. Sprinting can be heard within eight blocks and a running drill within twelve. Noise prompts investigation but never supplies a hidden player's identity or directly raises an alarm.

Guard name labels show state and suspicion for this development map. `/heist guards <uuid>` is operator-only and shows decision state, remembered target, update count, failed path requests, and last update cost. The crew HUD identifies STEALTH/LOUD. Alarm escalation preserves objective progress. Guards pursue visible players and search their last seen position; they do not track new positions through walls.

`/heist alarm <uuid>` switches to loud state; `/heist stop <uuid>` aborts a session. `/heist drain` closes admission and profile edits, and reports when acknowledged work and cleanup make shutdown safe. See the [drain protocol](docs/draining.md). Raise `instances.maximum` to two in a local test server to exercise isolation; the intended deployed architecture remains one match per process.

All current sessions are practice sessions: **no rewards, no durable player progression, no NPC combat**. Physical interactions validate crew membership, game phase, proximity, and a server-side block ray trace. Offhand duplicate events are ignored. Bags are authoritative logical objects rather than inventory items; a real bag-slot visual is still future work. A player disconnect currently aborts the crew's practice session. The GDD's reconnect flow is not implemented yet.

`graybox:1` remains the original admin-driven lifecycle arena; version 2 keeps the unguarded physical loop. `/heist complete` works only on version 1, preventing physical state desynchronization. All three versioned manifests are installed without overwriting existing files. Restart after updating the plugin; do not hot-reload it.

## Resource pack

The bundled configuration explicitly enables `resource-pack.development-bypass` so a local graybox can be tested before any artwork exists. Startup logs warn about this.

To enforce a real pack, set the bypass to `false`, configure an HTTPS archive URL, pack UUID, and the archive's 40-character SHA-1 hash, then restart. Failed, declined, or timed-out packs cause a clear disconnect. Merely accepting a pack does not permit admission. No textures or pack archive are claimed to exist yet.

## Structure

| Module | Responsibility |
|---|---|
| `heist-domain` | Pure Java rules and immutable content; no Bukkit or infrastructure dependencies |
| `heist-runtime` | Instance ownership, world/persistence boundaries, development adapters, health endpoint |
| `heist-mongo` | Versioned profile/result documents, optimistic concurrency, bounded database workers |
| `heist-paper` | Server-thread adapter, plugin lifecycle, commands, configuration, world/player handling |

See [architecture and extension points](docs/architecture.md), [manual verification](docs/manual-verification.md), and [next implementation slices](docs/next-steps.md).

## Current operational limits

Redis, BungeeCord/lobby routing, Agones allocation, NPC damage/weapons/reinforcements, finished arenas, and custom assets are future work. Current guards are invulnerable native-mob placeholders with vanilla goals removed and damage disabled. Do not expose this practice adapter as a production network.

With `storage.mode: mongodb`, acknowledged profiles/results persist in MongoDB. The operator history shows only the last 100 acknowledged results from this server run. Memory mode loses all data at restart. Neither mode is a reward ledger. Health endpoints provide process/admission information, not historical metrics. The health listener defaults to loopback; binding it to a pod interface must be accompanied by network restrictions.

World generation/loading/unloading uses Paper's owning thread. World-file deletion runs on a dedicated executor. A hard process crash can leave generated `heist_<uuid>` directories; they are never automatically reused or broadly swept. Inspect orphan directories while the test server is stopped. Hot reload and live match recovery are not supported.

The automated build verifies Java compilation, objective/guard rules, bounded NPC scheduling, HTTP health checks, all arena manifests, result finalization, and alarm isolation. Real-client interactions, native pathfinding, cover visibility, labels/HUD, resource-pack loading, teleports, and world lifecycle require the manual smoke test.

## Profiles and storage

Players load their profile on connection. `/heist profile` shows the acknowledged revision, presets, and settings. While a load/save is pending, joining and further edits are blocked. Failed writes invalidate the cached profile; `/heist profile reload` reads the database again. Edits and reloads are lobby-only.

- `/heist preset 2 SCOUT` creates or replaces slot 2 and selects it. Missing earlier slots use the Technician starter loadout.
- `/heist preset 1` selects an existing slot.
- `/heist join <uuid>` captures the selected saved loadout. The optional role argument remains a practice-only override and does not edit the profile.
- `/heist settings sound off` disables the custom guard alarm sound. It does not mute vanilla Minecraft sounds.
- `/heist settings particles off` saves a reduced-particles preference for future effects; no custom particle effects currently consume it. Language remains English. Equipment is still the starter carbine/medkit catalog, without functional weapons or gadgets.

Storage defaults to explicit development memory mode. For persistence, set `storage.mode: mongodb` and `storage.database: gameheist` in the plugin configuration, and supply `HEIST_MONGODB_URI` in the server process environment. Keep credentials in deployment secrets, never in tracked configuration. Restart after changing storage mode. Existing memory data is not migrated. MongoDB failure never switches storage back to memory.

MongoDB writes use majority acknowledgement, profile revision comparisons, and unique document IDs. Match results are immutable and retry-safe; conflicting results are rejected. See the [storage design and tests](docs/storage.md) for deployment limits and local verification. Rewards, transaction receipts, and network-wide profile leases remain future work.

## Player statistics

Use `/heist stats graybox 3 1` to view your solo practice results for graybox version 3, or replace the final argument with your crew size (1–4). This command currently queries NORMAL difficulty, matching the practice creation command. Historic arena versions remain queryable even if their manifests are no longer installed.

Totals include wins, gameplay losses, aborted runs, stealth wins, and crew-secured bags. Aborts are separate from gameplay losses. Bags describe the whole crew's secured total across recorded outcomes, including aborts; they are not personal bag contributions. Statistics include only saved terminal results, so an active or unacknowledged run does not appear yet.

MongoDB calculates totals from immutable results, separated by player, arena version, difficulty, crew size, and practice mode. Repeated saves cannot double-count a result. Memory mode reports only the bounded recent history and can lose totals through eviction or restart. Requests have a five-second cooldown, bounded concurrency, and background database execution.

This is a personal practice summary, not a leaderboard or reward system. Revives, damage, individual contributions, playtime, and best times need additional gameplay tracking before they can be reported accurately.

## Drain before shutdown

Use `/heist drain`, let the active crew finish, and wait for `safe to stop=true` before ordinary shutdown. The optional authenticated HTTP request and `GET /drained` support future orchestration. Pending or ambiguous profile writes and unfinished world cleanup prevent completion. This does not yet provide Kubernetes deployment or hard-crash recovery. See [draining and verification](docs/draining.md).

## Operational metrics

InfluxDB export and a provisioned Grafana dashboard are implemented. They cover tick time/TPS, instance states, player counts, profile work, draining, and exporter failures. Metrics are disabled by default and use bounded background delivery. See [setup and verification](docs/observability.md). The local stack is independent of the Minecraft server and is not a Kubernetes deployment.

## Container and Kubernetes foundation

A non-root Java 25 image now packages pinned Paper 26.1.2 build 74 and the plugin. The Kubernetes lab template includes configuration, resource limits, probes, and a bounded preStop drain helper. It defaults to zero replicas with EULA acceptance disabled. See [build, configuration, and verification](docs/deployment.md). Image packaging and manifest schemas passed locally; a Minecraft server and live cluster have not been started.
