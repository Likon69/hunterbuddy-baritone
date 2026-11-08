# hunterbuddy-baritone

Fork of Baritone for **Minecraft 1.21.11 only** (`v1.3.0-1.21.11`, Mojang mappings), with the elytra changes the
[HunterBuddy](https://github.com/Likon69/HunterBuddy) Meteor addon needs for long nether flights on 2b2t.
From hb52 it also carries upstream Baritone's elytra flight in the Overworld and the End, merged on nether-pathfinder 1.6, with the upstream movement, rendering and settings changes that came with that
merge. The fork's own changes are in the elytra code, plus four small hooks outside it (items 12, 16, 30 and 32).

- Build: `./gradlew :fabric:build -Pmod_version=1.3.0-hbN-1.21.11`, output in
  `dist/baritone-api-fabric-1.3.0-hbN-1.21.11.jar`. Current version: **hb52**.
- Logs: `#elytraChatSpam true` and `#chatDebug true`.
- Patched native pathfinder: [Likon69/nether-pathfinder](https://github.com/Likon69/nether-pathfinder).

## Differences from upstream

1. Arrival is judged by distance to the destination, not to the player, and a failing segment restarts from the player instead of landing short.
2. Takeoff is a ladder (launch, climb, relocate, give up) sized by `liftHeight` and kept across restarts, instead of one jump-and-rocket that looped.
3. A 4-block node search that fails outright also retries with 2-block nodes (`elytraPathNodeAdaptive`).
4. Landing spots are judged by block shape, never magma, fluid or bastion blocks, and away from piglin brutes.
5. The path can be confined to a corridor of chunks pushed by another mod (`setPathCorridor`).
6. The solver's hitbox grows only along the direction of travel, so takeoff finds a pitch.
7. Firework acceleration and extra boost ticks are settings, so the simulation matches a client that boosts differently.
8. The path colour is a setting (`elytraPathColor`).
9. A fall to new ground restarts the takeoff ladder, and a walk that gets nowhere moves on instead of giving up.
10. Long destinations are flown in 128-block legs along the line to them instead of along one axis (`elytraPathLegLength`).
11. After 10 s in a lava puddle the elytra cannot leave, the bot walks out on foot (`elytraLavaWalkOutSeconds`).
12. 25 obsidian are never spent by pillars or bridges, kept for the regear box (`obsidianReserve`).
13. When no pitch clears the horizon, the rescue also tries turning 30, 60 or 90 degrees either way.
14. A launch that falls straight back down counts against the same spot, so it is not repeated in a loop.
15. The nether-pathfinder chunk cache is locked on the read path as well, and the Windows DLL is built with the static MSVC runtime.
16. `LookBehavior.hunterbuddyNextRotation()` exposes the next tick's aim to the addon, kept through ProGuard.
17. Every wall, ceiling and floor hit is logged with its side, speed and state.
18. The rescue holds the heading it picked for up to 20 ticks instead of picking again every tick.
19. The lava escape holds its heading the same way.
20. Every heading jump over 60 degrees in one tick is logged with what decided it and the distance to the aimed point.
21. The lava escape never pitches down, since the simulation sees lava as air.
22. When the aimed point is under 4 blocks away, the last heading is held for up to 10 ticks, without rockets, if it stays clear for 5 ticks.
23. A flight that circles one spot while path searches keep failing sets down and walks on towards the goal (`elytraCirclingSeconds`).
24. Rockets are held, and the walk stops off the ground, while the addon eats at emergency health (`elytraHoldFireworks`).
25. Every collision log says how much of the trajectory the pathfinder cache already knew.
26. The lava walk-out looks for a shore up to 24 blocks away, across lava, as long as there is a floor the whole way.
27. A failing segment re-paths from the player on the ground as well as in the air, at most once every 40 ticks, instead of retrying the same buried resume point for ever.
28. The takeoff spends at most one rung every two seconds, and runs the whole ladder three times before giving up.
29. The stay in lava survives a lost control, so the walk-out after `elytraLavaWalkOutSeconds` is reachable instead of restarting from zero every two ticks.
30. A pillar that would jump into a solid block three up breaks it first, instead of jumping into it and failing over and over.
31. When launch, climb and relocate have all failed, the ladder tries digging three blocks straight up before giving up on the takeoff entirely.
32. A movement that runs past its expected time logs what it was standing in, what was above it, whether it was on the ground, and what was in hand.

Items 3, 5 and 10 act in the search that runs on the plain native context: the Nether by default, and the Overworld and
End only with upstream's `elytraAllowAboveBuildLimit` off. With it on (its default), Overworld and End searches go
through upstream's `BuildLimitPathFinder`, which calls the context directly, so they get no corridor, no legs and no
2-block retry.

## Settings added by this fork

| Setting | Default | What it does |
| --- | --- | --- |
| `elytraTakeoffPillar` | `true` | Allow the takeoff to climb to a launchable height |
| `elytraTakeoffPillarMaxHeight` | `12` | How far it will climb before walking somewhere else instead |
| `elytraPathNodeSize` | `4` | Minimum node size for the nether pathfinder, `4` or `2`; `2` is used whenever upstream's `elytraAllowTightSpaces` is on |
| `elytraPathNodeAdaptive` | `true` | Retry with 2-block nodes when the 4-block search stubs or fails |
| `elytraCorridor` | `true` | Honour a corridor pushed by another mod |
| `elytraCorridorMaskRadius` | `12` | Chunk radius in which non-corridor chunks are masked solid |
| `elytraPathColor` | red | Colour of the rendered flight path |
| `elytraLandOnAnySolid` | `true` | Land on any solid top face, not just netherrack and gravel |
| `elytraLandingBastionRadius` | `48` | Piglin brute radius that rejects a landing spot |
| `elytraFireworkBoostMultiplier` | `1.5` | Firework acceleration the flight simulation assumes |
| `elytraFireworkExtraBoostTicks` | `0` | Ticks a boost lasts beyond the rocket's own lifetime |
| `elytraPathLegLength` | `128` | How far ahead a search aims on a long, straight destination, in blocks |
| `elytraLavaWalkOutSeconds` | `10` | Seconds in lava before the takeoff gives up on the elytra and walks out |
| `obsidianReserve` | `25` | Obsidian never spent by a pillar, a bridge or anything else Baritone places |
| `elytraCirclingSeconds` | `5` | Seconds circling one spot with failing searches before setting down and walking on |
| `elytraPathDetourRatio` | `1.6` | How much a 4-block path may wander before a 2-block search is tried against it |
| `elytraHoldFireworks` | `false` | Steer without lighting a rocket, and stop off the ground, while the addon eats |
| `elytraFlightLog` | `true` | Keep a `[flight]` record of every flight in `latest.log`, never in chat |
| `elytraTakeoffJournal` | `false` | Log where the first path node sits during the first ticks of a launch |
| `elytraTakeoffJournalTicks` | `40` | How many flight ticks that journal covers |

Upstream settings now available and not added by this fork: `elytraCustomAllocator`, `elytraAllowTightSpaces`,
`elytraAllowAboveRoof`, `elytraUseCache`, `elytraAllowAboveBuildLimit`, `elytraLongDistanceThreshold`,
`allowWalkOnMagmaBlocks` and `farmUsingSelection`.

## Credits

Leijurv and babbaj for nether-pathfinder.
