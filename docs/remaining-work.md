# Remaining work — final handoff, 2026-09-15

The practice prototype is implemented but not accepted through real-client testing. The full release defined in GDD.md is not complete. This list separates missing implementation from verification; GDD tuning numbers must be reconciled with current prototype rules before release.

## Immediate acceptance gates

- User acceptance of Minecraft EULA, then start the staged local Paper server. EULA currently remains false; no Minecraft server was started in this session.
- Run the local HTTP readiness checker successfully against the actual server. The current report records connection refusal, not a pass.
- Complete the two-player graybox:4 checklist: all controls, guard navigation/cover, ammo/reload, self/crew healing, down/revive/interruption, loot return/deposit, votes/extraction, time limits, result feedback, isolation and inventory/world cleanup.
- Test the unmodified 26.1.2 client with Brasslock 1.2: hand/inventory transforms, prop placement, HUD readability at multiple scales, spatial audio, subtitles, mute behavior, pack failure/reload/cache/timeout paths.
- Run MongoDB and Influx integration suites with their isolated services and review CI outcomes. Normal local builds skip opt-in service tests; no new live database acceptance is claimed.
- Test profile persistence, concurrent updates, reconnect during requests, unavailable database, finalization retries and restart recovery as documented.

## Gameplay still missing from the GDD release

- Finished Brasslock Bank with authored routes, cover, patrols, roof/staff access and extraction area; replace the sparse graybox.
- Timed briefing and lobby practice/tutorial area, keycard/camera infiltration, vault support/power objective, authored escape activation and warnings for players outside extraction.
- Reconnect slot grace, vulnerable disconnected avatar, one-time loot handling, authoritative state restoration and short-handed continuation. Current disconnect aborts the entire practice run.
- Resolve and implement intended bleedout/spectator/limited-rescue rules; current revive durations differ from GDD hypotheses. Do not silently assume the GDD's provisional numbers were accepted through playtesting.
- Additional weapon behaviors and gadgets, threat marking, full role benefits/tradeoffs, camera disruption and detection modifiers.
- Normal/Hard gameplay differences and solo-to-four-player scaling beyond current initial rules. Tune match duration, damage, detection, repairs and loot through playtests.
- Sustained bounded reinforcement director and advanced navigation/cover/stuck recovery/debug overlays beyond current guard implementation; profile before increasing pressure.

## Player progression and persistence

- Transactional reward acceptance, receipts, durable recovery and XP/cosmetic unlock references now exist, with six replica-set integration tests including 100 replays and rollback. Finish production transport/profile-lease integration, equippable cosmetic UI and real-client saved/pending acceptance. Practice rewards remain disabled. See [reward implementation and verification](rewards.md).
- Network-wide profile leases/session ownership, stale generation rejection across coordinator/result writes, reconciliation and documented crash-loss handling.
- Validate the implemented crew/practice-session/loadout/settings/personal-results menus on a real client. Named presets, default cosmetic references, PL/EN menu labels and sound/particle/motion/notification preferences are implemented; full gameplay localization, progression-driven cosmetic choices and network matchmaking remain. See [menu behavior and acceptance checklist](player-menus.md).
- Objective contributions, aggregate recorded playtime, authoritative production leaderboards, cache reconciliation/invalidation and configurable practice/log retention are implemented. Validate command readability and large-history latency in staging; production matches still depend on issue #10. See [statistics semantics and verification](statistics.md).

## Network and deployment

- End-to-end lobby/crew queue/coordinator workflow connected to reservation repositories; backend registration, exact-server routing, BungeeCord transport, failed-transfer compensation and healthy-lobby return.
- Redis invalidation/session/cache integration with reconciliation where required; reward correctness must remain independent of disposable messages.
- Agones allocation and fleet lifecycle, immutable world templates and asynchronous preparation, exact version/pack binding, two independent match pods.
- Validate Kubernetes manifests in a real cluster: forwarding and network isolation, restricted credentials/RBAC/secrets, startup/readiness/liveness, draining/termination, proxy/backend/pod failures and visible lobby recovery.
- Production packaging/release manifests, rollback and retention of assets used by active matches. No transparent restoration of a live match after node loss is promised by the GDD.

## Assets, arena tooling and operations

- Host immutable resource-pack ZIPs over HTTPS, publish version manifests and enable required-pack admission; verify fresh/cached/declined/broken/slow downloads and lobby-to-backend transfer.
- Remaining camera/NPC art, ambient audio and visual polish; maintain editable sources and licensing. Existing original prop models and six sounds are implemented.
- Complete arena authoring/publication/version workflow and administrative validation/audit requirements from GDD section 10; current YAML manifests and practice commands are only the foundation.
- Per-NPC/objective/storage timings, actionable alerts, real load baselines and profiler evidence; validate dashboards against gameplay rather than synthetic samples alone.

## Release evidence

- Four real players/24 NPCs: measured p95 tick <45 ms and custom AI <3 ms on documented hardware.
- Measure warm allocation-to-ready p95 <5 seconds and cold boot separately.
- Fifty complete runs without accumulating world/entity/task state; exercise reward replay 100 times once rewards exist.
- Reproduce the demo from a fresh cluster with pinned artifacts; document failure scenarios, recovery and limitations.
- Produce gameplay screenshots and 90–120 second video, concise implementation deep dives, before/after performance report, incident write-up and playtest/collaboration evidence.

Optional expansion such as a second map should wait until first-map release acceptance. Local generated server files and build artifacts stay ignored; committed preparation scripts reproduce them. No EULA acceptance, credentials or local runtime files are included in Git.
