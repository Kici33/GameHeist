# Manual smoke test

Run this against a dedicated Paper 26.1.2 server and matching vanilla client. These checks are not claimed to have been performed by the initial automated build.

## Startup and artifact

- Install the bundled Paper jar, not the domain/runtime jars.
- Verify successful initialization and the practice-only/development-pack warning.
- Check `/heist arenas` lists `graybox:1`, `graybox:2`, and `graybox:3`.
- `GET /live` returns 200 after ticks begin; `/ready` returns 200 with free capacity.
- A malformed arena manifest should disable the plugin with a filename and useful error.

## Session lifecycle

Use `graybox:1` for the original admin-completion checks below.

- Create/join/start a graybox. Check adventure mode and location.
- Try completing loot before drill. Progress must remain unchanged.
- Complete security, drill, loot, escape. Verify one WON practice result.
- Confirm return location and original game mode, no inventory replacement, and eventual world-folder cleanup.
- During async deletion, the slot may remain CLOSING for up to the five-second retry interval.
- Create another session. No prior objective, alarm, or crew state should carry over.
- Abort a session. It must produce ABORTED rather than a gameplay loss.
- Disconnect a member. The current practice behavior aborts the entire session.

## Physical objective loop (`graybox:2`)

- Host creates and starts a crew; a non-operator must be able to join but must not create/start/stop/drain instances.
- Check the blue, orange, yellow, and green labels and the objective HUD. Right click physical blocks; no completion commands should be needed.
- Attempt loot before drilling and interact from behind an obstruction or beyond reach. No progress should be awarded.
- Disable security and install the drill. Verify 90 seconds of drill work, two jams, and no timer reset from repeated clicks.
- Repair a jam, move away, and return. Repair must restart; verify six-second normal and 4.5-second Technician repair timing.
- Have two players click the same gold bag. Exactly one should carry it; a carrier cannot take another.
- Check the 20% walk-speed reduction and restoration after deposit, abort, disconnect, and normal completion.
- Deposit three bags, optionally carry the remaining two, then vote. A four-player crew needs three unique votes. Repeat votes must not count twice or reset an active countdown.
- Let extraction expire without anyone within five blocks: no win, votes reset. Retry with someone present: one win with the correct secured-bag count.
- Verify titles/result text reach all crew members and boss bars disappear during cleanup.
- Verify `/heist complete` is rejected for a physical session.
- Test two physical instances concurrently. Bags, jams, votes, HUD, results, and cleanup must stay isolated.

## Guarded objective loop (`graybox:3`)

- No guards should appear during briefing. Starting spawns two named patrols, and native movement follows their routes around the props/cover.
- Approach in front, behind, and outside sight range. Suspicion should only increase for visible crew members inside the facing cone.
- Break sight behind a stone wall during ALERTING. The alarm attempt must cancel; the guard should investigate the last seen location without tracking the hidden player's new position.
- Compare standing, sneaking, sprinting, and Scout detection. Noise behind a guard should trigger investigation, never immediate alarm.
- Start the drill near a guard and test its running/jammed/completed noise behavior.
- Let detection and the alarm delay finish. Verify the alarm message/sound, LOUD HUD, preserved objectives, and pursuit/search behavior.
- Run two guarded instances: alarms, targets, actors, and diagnostics must remain isolated. Spectators and players in other worlds must not be detection candidates.
- Inspect `/heist guards <uuid>` as an operator; verify non-operators are denied.
- Deliberately obstruct a route in an isolated test: after bounded retries the stuck guard is retired and logged, with no teleport or respawn loop.
- Abort, complete, and stop the server. NPCs must stop/remove with their instance and never resume during asynchronous world deletion.
- Guards and players remain damage-protected in this slice. Confirm no vanilla melee attack, raid behavior, drops, or block griefing bypasses the custom controller.

## General isolation and cleanup

- Set maximum instances to two and restart. Use different crews.
- Verify distinct worlds, separate alarm/objective state, and rejection of joining both.
- Use drain: new create/join must fail, but an existing crew can finish.
- Verify non-operators cannot access management commands.
- Interfere with unload/return teleport using a controlled test plugin: capacity must remain occupied and an error must appear.
- Stop the server with a running session; inspect cleanup warnings. Do not claim crash recovery from graceful shutdown.

## Resource pack

- Host a real test pack, configure ID/hash/HTTPS URL, disable development bypass, restart.
- With a clean client cache, confirm admission is blocked until SUCCESSFULLY_LOADED.
- Try decline, broken download, failed pack reload, timeout, and cached success.
- Verify failure messaging and that no player joins a practice instance before successful application.

## Remaining live integrations

No claims about BungeeCord transfer, Kubernetes/Agones allocation, MongoDB persistence, Redis recovery, or InfluxDB/Grafana should be made until their adapters and live tests exist.
# Player storage follow-up

Run the [profile, settings, restart, and storage-outage checks](storage.md#client-acceptance-checks) in addition to the gameplay checks below. MongoDB integration tests use an isolated disposable database; the in-game profile workflow still needs client acceptance.
