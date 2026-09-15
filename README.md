# GameHeist

A Minecraft Java **26.1.2** cooperative heist portfolio project. The [GDD](GDD.md) describes the intended game. This repository includes a **combat graybox objective loop** with patrols, suspicion, alarm escalation, carbine fire, and teammate revives. It is not yet the complete minigame or network; the combat slice still needs real-client playtesting.

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
- Opt-in `graybox:4` combat: carbine, telegraphed guard attacks, one reinforcement wave, incapacitation, revives, crew defeat, and saved combat contributions.
- Admission gated on successful pack application when the development bypass is off.
- Read-only heartbeat/capacity endpoints: `http://127.0.0.1:8081/live` and `/ready`.
- Lobby profile loading, three editable preset slots, saved settings, and revision-checked MongoDB profiles/results (opt-in).
- Regression tests, dependency locks, reproducible jar settings, and CI configuration.

Profiles are connected to practice joins through saved presets. Graphical player menus remain future work.

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
9. Inspect `/heist results [page]` as host: ten newest-first results per page, including arena, crew size, outcome, loot, and measured run time when available. This lists retained results from this server run, not full MongoDB history. Players return to their original location/game mode/walk speed; the world unloads and its files are deleted asynchronously.
10. Use `/heist list` for pending persistence/cleanup errors. Failed cleanup retains capacity and retries after five seconds.

Version 3 adds two guards and two stone cover walls. Guards spawn when the match starts. Stay out of their facing cone or break line of sight behind cover. Normal detection takes three seconds of sustained visibility followed by a two-second alarm attempt. Losing sight interrupts the attempt. Sneaking halves suspicion gain; Scout multiplies it by 0.75; sprinting increases it to 1.4 times normal. Sprinting can be heard within eight blocks and a running drill within twelve. Noise prompts investigation but never supplies a hidden player's identity or directly raises an alarm.

Guard name labels show state and suspicion for this development map. `/heist guards <uuid>` is operator-only and shows decision state, remembered target, update count, failed path requests, and last update cost. The crew HUD identifies STEALTH/LOUD. Alarm escalation preserves objective progress. Guards pursue visible players and search their last seen position; they do not track new positions through walls.

`/heist alarm <uuid>` switches to loud state; `/heist stop <uuid>` aborts a session. `/heist drain` closes admission and profile edits, and reports when acknowledged work and cleanup make shutdown safe. See the [drain protocol](docs/draining.md). Raise `instances.maximum` to two in a local test server to exercise isolation; the intended deployed architecture remains one match per process.

All current sessions are practice sessions: **no rewards or durable player progression**. Combat is enabled only by the new version 4 manifest. Physical interactions validate crew membership, game phase, proximity, and a server-side block ray trace. Offhand duplicate events are ignored. Bags remain authoritative logical objects; with the custom pack enabled, combat crews also get a cosmetic bag in hotbar slot 9 while carrying loot. A player disconnect currently aborts the crew's practice session. The GDD's reconnect flow is not implemented yet.

`graybox:1` remains the original admin-driven lifecycle arena; version 2 keeps the unguarded physical loop; version 3 keeps damage-free guard testing. `/heist complete` works only on version 1, preventing physical state desynchronization. All four versioned manifests are installed without overwriting existing files. Restart after updating the plugin; do not hot-reload it.

## Combat practice (`graybox:4`)

Create with `/heist create graybox 4`, join, then start using the same commands above. Combat starts with two patrol guards. Player inventory is temporarily replaced by a carbine in hotbar slot 1; original contents and selected slot are restored on normal exit, abort, disconnect, and graceful shutdown. Native Minecraft health is unchanged: the action bar shows authoritative combat HP, ammunition, and reload/revive progress.

- Left click with the carbine to fire. Each magazine has 12 rounds, shots are at least 300 milliseconds apart, range is 24 blocks, and three hits defeat a full-health guard. Walls and players block the shot; friendly fire is disabled. Any accepted shot raises the alarm, including a miss.
- Press the swap-hands key (default **F**) to reload for two seconds. This practice slice has unlimited reserve ammunition. Right click still operates objective markers.
- Loud guards aim for one second before dealing 10 damage (6 for solo crews), then wait 2.5 seconds before starting another aim. Breaking line of sight or moving beyond 16 blocks interrupts aim. The player receives a text warning before each attack.
- One responder wave arrives 15 seconds after the alarm: one responder for a solo crew, two for larger crews, spawning at existing patrol starts. There are no recurring waves. All actors share the squad's 24-guard hard cap and cleanup scope.
- At zero combat HP, players are downed and cannot move, fire, repair, collect loot, vote, or satisfy extraction presence. A carried bag returns exactly once to its original gold marker. Downing everyone produces a LOST result with reason `crew_incapacitated`.
- Sneak and right click a downed teammate, then keep sneaking within three blocks with clear sight. Reviving takes four seconds, or three for Support, and restores 50 HP. Damage, movement out of range, loss of sight, releasing sneak, firing, or starting a reload cancels the revive. There is no bleed-out timer in this slice.

Combat contributions (damage dealt, damage taken, and revives) appear in the result message, immutable saved results, and personal `/heist stats` totals. The medkit occupies slot 2: right click to restore up to 40 logical HP once per run. Full-health and downed players cannot consume it. Healing interrupts reload/revive actions; it does not change native hearts or erase damage statistics. The HUD shows charge availability. See the [combat smoke tests](docs/manual-verification.md#combat-and-recovery-graybox4) before treating the slice as playtested.

## Resource pack

The original **Brasslock 1.2** resource pack includes a carbine, medkit, loot bag, security terminal, extraction beacon, four drill states, and six original sound effects with English/Polish subtitles. Build with `.\gradlew.bat resourcePack` (also included in `build`). The ZIP, hashes, configuration excerpt, and model overview are generated in `build/resource-pack/`. See [installation, sources, and verification](docs/resource-pack.md).

For local preview, install the ZIP in each client's resource-pack folder and set `resource-pack.preview-models: true` while keeping the development bypass on. To enforce the pack, host the ZIP at an HTTPS URL, set the bypass to `false`, and configure the URL, pack UUID, and generated SHA-1. Custom models then enable automatically. Failed, declined, or timed-out packs disconnect the player; merely accepting a pack does not permit admission. Default bypass mode with preview off keeps vanilla visuals available. Client rendering and hosted pack delivery still require live verification.

## Structure

| Module | Responsibility |
|---|---|
| `heist-domain` | Pure Java rules and immutable content; no Bukkit or infrastructure dependencies |
| `heist-runtime` | Instance ownership, world/persistence boundaries, development adapters, health endpoint |
| `heist-mongo` | Versioned profile/result documents, optimistic concurrency, bounded database workers |
| `heist-paper` | Server-thread adapter, plugin lifecycle, commands, configuration, world/player handling |
| `heist-pack` | Offline resource-pack builder, original textures, validation, and model overview |

See [architecture and extension points](docs/architecture.md), [manual verification](docs/manual-verification.md), and [next implementation slices](docs/next-steps.md).

## Current operational limits

Redis, BungeeCord/lobby routing, Agones allocation, additional weapons/gadgets, sustained reinforcement pressure, finished arenas, custom NPC models, and ambient audio are future work. Guards use native-mob placeholders with vanilla goals removed and vanilla damage disabled; version 4 applies custom combat rules. Do not expose this practice adapter as a production network.

With `storage.mode: mongodb`, acknowledged profiles/results persist in MongoDB. The operator history shows only the last 100 acknowledged results from this server run. Memory mode loses all data at restart. Neither mode is a reward ledger. Health endpoints provide process/admission information, not historical metrics. The health listener defaults to loopback; binding it to a pod interface must be accompanied by network restrictions.

World generation/loading/unloading uses Paper's owning thread. World-file deletion runs on a dedicated executor. A hard process crash can leave generated `heist_<uuid>` directories; they are never automatically reused or broadly swept. Inspect orphan directories while the test server is stopped. Hot reload and live match recovery are not supported.

The automated build verifies Java compilation, objective/guard rules, bounded NPC scheduling, HTTP health checks, all arena manifests, result finalization, and alarm isolation. Real-client interactions, native pathfinding, cover visibility, labels/HUD, resource-pack loading, teleports, and world lifecycle require the manual smoke test.

## Profiles and storage

Players load their profile on connection. `/heist profile` shows the acknowledged revision, presets, and settings. While a load/save is pending, joining and further edits are blocked. Failed writes invalidate the cached profile; `/heist profile reload` reads the database again. Edits and reloads are lobby-only.

- `/heist preset 2 SCOUT` creates or replaces slot 2 and selects it. Missing earlier slots use the Technician starter loadout.
- `/heist preset 1` selects an existing slot.
- `/heist join <uuid>` captures the selected saved loadout. The optional role argument remains a practice-only override and does not edit the profile.
- `/heist settings sound off` disables plugin alarm, carbine, and drill effects, including their fallback sounds. It does not mute unrelated vanilla Minecraft sounds.
- `/heist settings particles off` saves a reduced-particles preference for future effects; no custom particle effects currently consume it. Language remains English. Equipment uses the starter carbine/medkit catalog; both work in combat arenas, including self/crew healing.

Storage defaults to explicit development memory mode. For persistence, set `storage.mode: mongodb` and `storage.database: gameheist` in the plugin configuration, and supply `HEIST_MONGODB_URI` in the server process environment. Keep credentials in deployment secrets, never in tracked configuration. Restart after changing storage mode. Existing memory data is not migrated. MongoDB failure never switches storage back to memory.

MongoDB writes use majority acknowledgement, profile revision comparisons, and unique document IDs. Match results are immutable and retry-safe; conflicting results are rejected. See the [storage design and tests](docs/storage.md) for deployment limits and local verification. Rewards, transaction receipts, and network-wide profile leases remain future work.

## Player statistics

`/heist stats` includes the fastest recorded winning run in the selected arena/version/difficulty/crew-size scope. Only wins with measured gameplay time qualify; legacy results and faster losses/aborts do not establish a best time. Memory mode uses retained history, while MongoDB queries saved results.

Clicking the carbine with an empty magazine starts the normal two-second reload. Repeated clicks do not restart it or queue a shot. Manual F remains available for a partial magazine.

While carrying loot, Shift+F returns the bag to its original marker for another crew member to collect. This restores movement speed and clears the cosmetic bag slot without securing loot or creating a dropped item. Plain F remains the carbine reload control.

Medkits can also heal a living teammate: select slot 2 and right click them within three blocks with a clear line of sight. The helper spends their single charge to restore up to 40 HP to the recipient. Full-health targets do not consume it. Sneak-right-click on a downed teammate still starts the existing revive action and does not spend the medkit.

Combat HUD shows teammates in a separate boss bar below the objective. Its fill is their average available logical HP (absent members contribute zero). Downed teammates appear first as `DOWN` and turn the bar red; unavailable teammates show `AWAY`. The bar omits your own entry and is absent in solo play. Personal health, ammo, medkit, and bag status remain on the action bar; the objective stays on its own boss bar. Names and crew bars are owned by the active match and removed during cleanup.

Use `/heist stats graybox 3 1` to view your solo practice results for graybox version 3, or replace the final argument with your crew size (1–4). This command currently queries NORMAL difficulty, matching the practice creation command. Historic arena versions remain queryable even if their manifests are no longer installed.

Totals include wins, gameplay losses, aborted runs, stealth wins, and crew-secured bags. Aborts are separate from gameplay losses. Bags describe the whole crew's secured total across recorded outcomes, including aborts; they are not personal bag contributions. Statistics include only saved terminal results, so an active or unacknowledged run does not appear yet.

MongoDB calculates totals from immutable results, separated by player, arena version, difficulty, crew size, and practice mode. Repeated saves cannot double-count a result. Memory mode reports only the bounded recent history and can lose totals through eviction or restart. Requests have a five-second cooldown, bounded concurrency, and background database execution.

Personal damage dealt, damage taken, and revives are summed across the same saved results, including aborted runs. Other crew members' combat contributions are excluded. Historical results without combat data add zero to these fields while still counting toward runs and outcomes. Use `/heist stats graybox 4 2` for two-player combat practice totals.

This is a personal practice summary, not a leaderboard or reward system. Measured run times and scoped best winning times are implemented; other individual objective contributions still need gameplay tracking. Use `/heist controls` for the current in-game control reference.

## Drain before shutdown

Use `/heist drain`, let the active crew finish, and wait for `safe to stop=true` before ordinary shutdown. The optional authenticated HTTP request and `GET /drained` support future orchestration. Pending or ambiguous profile writes and unfinished world cleanup prevent completion. This does not yet provide Kubernetes deployment or hard-crash recovery. See [draining and verification](docs/draining.md).

## Operational metrics

InfluxDB export and a provisioned Grafana dashboard are implemented. They cover tick time/TPS, instance states, player counts, profile work, draining, and exporter failures. Metrics are disabled by default and use bounded background delivery. See [setup and verification](docs/observability.md). The local stack is independent of the Minecraft server and is not a Kubernetes deployment.

## Container and Kubernetes foundation

A non-root Java 25 image now packages pinned Paper 26.1.2 build 74 and the plugin. The Kubernetes lab template includes configuration, resource limits, probes, and a bounded preStop drain helper. It defaults to zero replicas with EULA acceptance disabled. See [build, configuration, and verification](docs/deployment.md). Image packaging and manifest schemas passed locally; a Minecraft server and live cluster have not been started.

## Reservation admission foundation

Reservation models, MongoDB and process-local repositories, and runtime reserved-admission hooks are present. The local store serializes crew acquisition, backend assignment, and admission claims. It retains request identities until restart for retry detection and is intended only for development and tests.

Tests cover concurrent crew acquisition, stale backend identities, admission deadlines, retries, and partial-transfer retention. Expired unclaimed reservations can be replaced; partially admitted crews remain reserved until the assigned backend releases them after finalization and cleanup. BungeeCord transport and the complete lobby/coordinator path remain pending.
