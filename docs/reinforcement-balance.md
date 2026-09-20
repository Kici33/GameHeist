# Reinforcement director and provisional balance v1

Create a practice match with `/heist create graybox 4 NORMAL` or
`/heist create graybox 4 HARD`. Normal remains the default. All matches still grant
no progression rewards. These are implemented tuning hypotheses, not playtest
acceptance or a claim of a measured target match duration.

## Pressure rules

After the first loud frame, the first wave is due in 15 seconds on Normal or 12
seconds on Hard. At most four waves are issued. Later cooldowns are selected from
the current phase at the preceding claim:

| Difficulty | Infiltration | Vault | Extraction |
| --- | ---: | ---: | ---: |
| Normal | 40 s | 35 s | 30 s |
| Hard | 30 s | 25 s | 20 s |

Later phase changes do not shorten an already advertised deadline. A delayed tick
issues at most one wave and schedules the next cooldown from the actual claim
time, so a server stall cannot produce catch-up bursts.

| Active crew | Normal live cap | Hard live cap | Normal base wave | Hard base wave |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 4 | 6 | 1 | 2 |
| 2 | 6 | 8 | 1 | 2 |
| 3 | 8 | 10 | 2 | 3 |
| 4 | 10 | 12 | 2 | 3 |

Extraction adds one responder, up to four per wave. Actual size is clipped to the
remaining live capacity and to **24 registered guards for the whole match**,
including dead/retired actors. Registrations and resources therefore remain
bounded. Authored initial patrols are preserved; if they exceed the director's
soft live cap, no reinforcements arrive until population drops below it.

Saturation cancels the pending deadline. Once room becomes available a fresh full
delay is scheduled; there is no immediate surprise spawn. Downed players reduce
future-wave pressure, while existing guards stay alive. During disconnect grace,
only connected non-downed crew members count for a future wave. Missing or
retired Paper actors are marked retired in combat without awarding damage credit.

Each wave has its own five-second warning; optional result/title motion settings
still apply. Crew chat warnings remain available. Spawn positions are the existing
authored patrol starts. Their visibility and distance from players require map
playtesting; this change does not claim new authored hidden spawn entrances.

## Difficulty and objective pressure

Normal preserves existing guard perception. Hard adds two blocks of sight range
(maximum 32), ten degrees of field of view (maximum 180) and reduces detection time
to 80% of authored duration (minimum one second). Geometry and line-of-sight
checks still apply. Both difficulties require an uninterrupted one-second aim;
Normal attack cooldown is 2.5 seconds, Hard 2.2 seconds. Damage is 6/8 for solo
Normal/Hard and 10/12 for larger crews. Player HP, guard HP, weapon damage and
revive rules are unchanged.

In combat arenas, required loot is the authored minimum, minus one for solo or
plus one for 3–4 players, plus one on Hard, clipped to 1…available bags. This uses
the original roster, so becoming downed does not lower the objective. Graybox:4
authors three required/five available bags, yielding:

| Crew | Normal bags | Hard bags |
| ---: | ---: | ---: |
| 1 | 2 | 3 |
| 2 | 3 | 4 |
| 3–4 | 4 | 5 |

Non-combat physical maps keep their authored loot requirement. The gameplay HUD
shows the actual requirement. Drill/repair durations remain authored values.

`/heist guards <id>` reports difficulty, waves issued, live/registered guard counts,
next-wave deadline, AI state, path failures and update microseconds. Existing
staggered perception, one-path-request-per-second limit and eight-second stuck
retirement remain. No new navigation/cover heuristic was added without profiling.

## Verification and remaining acceptance

Automated cases cover both difficulties × crews 1–4 × three gameplay phases,
four-wave and lifetime caps, long clock jumps, saturation/resumption, reduced
active crew, terminal/stealth suppression, per-wave warnings, solo damage and
continued aim/visibility validation. Existing squad cleanup/partial-spawn tests
continue to run. This is deterministic rule verification, not measured combat feel.

For each of the eight real-client difficulty/crew combinations, record build,
arena version, seed, player roles, duration/outcome, detection time, damage,
repairs, bags, wave timings/sizes, maximum live NPCs and `/heist guards` diagnostics.
Verify no through-wall attacks, warning readability, spawn-entry safety, cover
navigation and cleanup on success/abort. Profile before increasing NPC pressure.
Record concrete findings and revise this table only from actual playtests.

Issue #6 stays open for those playtests, large-crew performance evidence and any
navigation/cover changes justified by the measurements. No real-client results
have been recorded for this revision.
