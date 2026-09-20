# Match reconnection

The match owns connection state for every immutable roster slot. Once infiltration starts, a disconnect does not remove the participant and no replacement player can enter that slot.

On disconnect the server increments that participant's connection generation, cancels revive work, clears guard targeting and returns any carried bag to its authored marker exactly once. Health, ammunition, medkit use, combat contribution, objective progress, loadout and the match timer stay in the existing aggregate.

For the first 10 seconds an armor-stand avatar remains at the last trusted position. Guards include it in their normal server-side perception and combat targeting, so damage still changes the disconnected participant's authoritative health. The avatar then despawns. The roster slot remains reserved for 90 seconds from that disconnect. A reconnect must present the current in-memory generation and reclaim that same participant; old callbacks and prior generations cannot reopen the slot. The Paper adapter restores the temporary match inventory from the saved session and derives the medkit visual from combat state instead of granting a new loadout.

If one slot expires while another crew member remains, play continues with the smaller available crew. Only future reinforcement claims use the connected, non-downed crew count; existing guards are not deleted. If every slot is disconnected or expired and no participant returns before their applicable grace expires, the result is `LOST` with reason `crew_disconnected_timeout`. Storage, lease, guard-system and server failures remain `ABORTED`, keeping infrastructure failures out of gameplay loss statistics.

In network mode, a consumed reservation may route its original participant back to the assigned backend. The backend validates the still-live instance and participant slot before resuming it; it never consumes a second admission or creates a fresh match. If the assigned backend or its lease is unavailable, normal lobby recovery and explicit infrastructure-abort behavior applies.

Automated tests cover duplicate disconnect callbacks, stale generations, the vulnerable and reserved boundaries, individual and all-disconnected expiry, one-time loot release and gameplay-loss classification. Live verification should additionally disconnect a real client while carrying loot and while under guard fire, reconnect before and after 10 seconds, verify health/ammo/medkit/objectives, then attempt reconnect after 90 seconds and during backend evacuation.
