# Brasslock Bank 1

`brasslock_bank:1` is the first complete authored arena. The generated world is deterministic and disposable; the YAML manifest owns gameplay anchors, patrol routes, cover, timing and the published version.

## Layout and routes

The crew briefs in a paved tutorial apron with visible reminders for firing, objective interaction and reviving. A 30-second server-clock countdown shows the required route and entry choices before the gameplay timer begins.

The public hall contains teller cover and a staff keycard. The main path crosses the hall into the security room. The alternate path enters the staff corridor from the side alley, climbs the exterior stair tower and crosses the roof before descending beside the vault approach. Four authored patrols cover the hall, staff loop, two-lane vault approach and roof. Stone cover anchors supplement the counters, crates and reinforced center divider.

The objective route is authoritative:

1. Collect the staff keycard in the public hall.
2. Use it at the security terminal. Until then, two mounted cameras detect continuous line of sight for two seconds and raise the global alarm; a one-second warning gives the player time to break sight.
3. Install the vault drill, then hold the red support panel for five seconds to restore power. Leaving the panel cancels the work. The 90-second drill and its seeded jams do not progress without power.
4. Carry the required bags to the extraction-yard deposit. Crew and difficulty scaling still determine the required count.
5. Reach the side-alley escape switch. A strict majority of active players must vote there; the deposit marker cannot start escape.
6. During the 20-second escape countdown, players outside the lit seven-block extraction boundary receive an explicit action-bar warning. At least one active player must be inside when the countdown expires, otherwise activation and votes reset.

## Validation

Automated manifest checks validate the 30-second briefing, two cameras, four patrols including the roof route, 21 cover blocks and combat configuration. Runtime acceptance completes the exact published route, checks that the drill pauses before power restoration, verifies camera state, rejects skipped prerequisites, and finishes the authored escape. The same acceptance is repeated for two- and four-player crews, including majority thresholds, scaled bag counts and outside-yard warnings.

For native-client acceptance, create the arena with `/heist create brasslock_bank 1 [NORMAL|HARD]`. With two players, take separate staff and roof routes and regroup at the vault. With four, split across both routes, trigger and avoid each camera, observe all four patrols, defend both vault lanes, then leave players inside and outside the yard during escape. Record blocked doors, unsafe spawns, navigation failures, unreadable markers and HUD overlap before changing the published manifest; gameplay changes require version 2.
