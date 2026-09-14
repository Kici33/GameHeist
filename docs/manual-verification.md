# Manual smoke test

Run this against a dedicated Paper 26.1.2 server and matching vanilla client. These checks are not claimed to have been performed by the initial automated build.

## Startup and artifact

- Install the bundled Paper jar, not the domain/runtime jars.
- Verify successful initialization and the practice-only/development-pack warning.
- Check `/heist arenas` lists `graybox:1`, `graybox:2`, `graybox:3`, and `graybox:4`.
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

## Combat and recovery (`graybox:4`)

These real-client checks remain pending. Start with two players, then repeat solo and with two independent instances.

- Before joining, put distinct items in storage, armor, and offhand slots and note the selected hotbar slot. Create/start version 4. Verify the temporary carbine, unchanged native hearts, and combat HP/ammo action bar. Verify version 3 still has no combat or inventory replacement.
- Left click air, a guard at melee distance, and a guard at range. Each click should produce at most one accepted shot, and right-clicking objectives must never fire. Fast clicks must not exceed the 300ms interval. Empty the magazine, press F, and verify a two-second reload; repeated F must not restart it or refill instantly.
- Shoot a guard through a cover wall, beyond 24 blocks, and behind another crew member. No blocked target should lose HP; crew members must never take friendly-fire damage. An unobstructed guard needs three hits. Defeated guards must disappear without drops or vanilla melee/raid behavior.
- Fire a miss during stealth. The HUD should show LOUD with objectives preserved. After 15 seconds, verify one wave of two responders (one when solo), then no further waves. Inspect `/heist guards <uuid>` and cleanup after the wave.
- Let a loud guard aim. Verify a one-second text warning, 10 damage per hit (6 solo), and a 2.5-second cooldown before another aim. Step behind cover or beyond 16 blocks during the warning: no damage. Return and verify a new full warning period. Changing targets must also restart aim.
- Down a player while carrying a bag and while repairing a jam. The downed player must stop moving/shooting/interacting, the repair must cancel, and exactly one gold bag must reappear at its original marker. A teammate must be able to collect and deposit it once.
- Sneak/right-click a downed teammate. Verify four seconds for ordinary roles and three for Support, with 50 HP on revival. Move out of range, release sneak, break sight, take damage, shoot, or reload: progress must cancel and require another interaction. Two simultaneous helpers must produce only one credited revive.
- Down one player at the extraction marker and leave the active player outside it. The countdown must reset without a win. Retry with the active player present: extraction succeeds. Confirm votes use the active crew and downed players lose earlier votes.
- Down the entire crew. Verify one LOST result with `crew_incapacitated`, never an ABORTED outcome or vanilla death. Test solo defeat too.
- Finish, abort, disconnect, and gracefully stop separate sessions. Verify exact original inventory contents and held slot, location, game mode, walk speed, and unchanged native health. Cancel a return teleport with a test plugin, then allow cleanup retry; restoration must happen once without duplicating items.
- Shoot a guard: the action bar should retain its name and remaining HP for 20 server ticks (about one second at normal tick rate). A lethal hit displays `Defeated`. Misses and shots blocked by a teammate or cover must not generate a new hit confirmation. Existing confirmation may remain until its expiry. Check that it expires, is replaced by a newer hit, stays private to the shooter, and does not appear in a later match.
- Empty the carbine magazine and click again: one reload message/sound should play, followed by a full magazine after two seconds. Spam clicks during reload: the timer must not restart, and no shot should be queued. Click again after completion to fire. Check manual F with a partial magazine and the sound-off profile setting.
- While carrying loot, press Shift+F: the original gold marker and model return, the carried inventory icon clears, and movement speed recovers. Have another teammate collect and deposit the same bag; it must count once. Repeated Shift+F without a bag must not create loot or reload the weapon. Plain F still reloads. Check both model-enabled and vanilla fallback modes.
- During a revive, the downed player's action bar should name the rescuer and show remaining seconds. Interrupt by releasing sneak, moving out of range, blocking visibility, or damaging the rescuer: the helper receives one reason message and the recipient returns to the waiting message on the next HUD update. Restarting must require the full duration again. With two rescuers, the earliest finishing valid revive is displayed; the other helper is told when the target is already back up. On success, the normal personal combat HUD returns.
- Right click an injured crew member with the slot 2 medkit, within three blocks: only the recipient gains up to 40 HP, and only the helper loses the item/charge. Repeat with a full-health target, through cover, beyond three blocks, and against someone outside the match: no healing or charge consumption. Sneak-right-click a downed teammate while holding the medkit: the normal timed revive should begin without consuming it. Check that no self-heal is also triggered by the same entity click.
- In a two-to-four-player combat run, check the separate crew boss bar after damage, healing, and revives. Its fill must reflect teammates' average available HP. Downed teammates appear first as `DOWN` and turn it red; disconnected or unavailable teammates show `AWAY`. The viewer must not appear in their own crew summary. Solo runs should have no crew bar. Check readability at the normal client GUI scale with long names, cleanup after leaving/ending, and ensure another match's players never appear. Personal combat status stays on the action bar and objectives on the original boss bar.
- Select slot 2 and right click the medkit at full HP: it must remain available. After damage, use it to recover up to 40 logical HP (maximum 100): the item disappears and HUD says used. Repeated clicks must not heal again. A downed player cannot use it; a teammate revive preserves an unused charge. Check reload/revive interruption, right clicks on objective blocks do not also operate them, and original slot 2 contents return after leaving. Native hearts and recorded damage taken must remain unchanged by healing.
- Verify result feedback includes each player's actual damage and revives. In MongoDB mode inspect the sorted `combatStats` result field; a repeated save must not duplicate contributions. Run `/heist stats graybox 4 2` as each crew member after a two-player result saves: personal totals must reflect only that player's contribution. Repeat after reconnect/restart in MongoDB mode, alongside an older result without combat data, and check that old runs still count without adding combat totals. In memory mode totals cover only retained history.
- Run two version 4 sessions. Shots, guards, revives, waves, contributions, inventory ownership, and cleanup must remain isolated. Disable custom sound in the lobby and check the carbine and alarm are muted while text cues remain.

Acceptance target: two players trigger the alarm, use cover, fight the small wave, revive each other, complete the drill, secure three bags, and extract. Record balance observations and client/server versions; automated rule tests do not establish combat feel or native pathfinding acceptance.

## General isolation and cleanup

- Set maximum instances to two and restart. Use different crews.
- Verify distinct worlds, separate alarm/objective state, and rejection of joining both.
- Use drain: new create/join must fail, but an existing crew can finish.
- Verify non-operators cannot access management commands.
- Interfere with unload/return teleport using a controlled test plugin: capacity must remain occupied and an error must appear.
- Stop the server with a running session; inspect cleanup warnings. Do not claim crash recovery from graceful shutdown.

## Resource pack

Use the [Brasslock pack setup and visual checks](resource-pack.md) for model states, carried bags, inventory restoration, and local preview. The following checks cover required download/application:

- Host a real test pack, configure ID/hash/HTTPS URL, disable development bypass, restart.
- With a clean client cache, confirm admission is blocked until SUCCESSFULLY_LOADED.
- Try decline, broken download, failed pack reload, timeout, and cached success.
- Verify failure messaging and that no player joins a practice instance before successful application.

## Remaining live integrations

No claims about BungeeCord transfer, Kubernetes/Agones allocation, MongoDB persistence, Redis recovery, or InfluxDB/Grafana should be made until their adapters and live tests exist.
# Player storage follow-up

Run the [profile, settings, restart, and storage-outage checks](storage.md#client-acceptance-checks) in addition to the gameplay checks below. MongoDB integration tests use an isolated disposable database; the in-game profile workflow still needs client acceptance.
