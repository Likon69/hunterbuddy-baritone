# hunterbuddy-baritone

A fork of dekrom's Baritone (`v1.3.0-1.21.11`, Mojang mappings) carrying the changes the
HunterBuddy Meteor addon needs for long-distance elytra flight in the nether on 2b2t. Everything
below is in the elytra code; nothing else in Baritone is touched.

Built with `./gradlew :fabric:build -Pmod_version=1.3.0-hbN-1.21.11`, output in
`dist/baritone-api-fabric-1.3.0-hbN-1.21.11.jar`. Current version: **hb31**.

To see anything the elytra code logs, both of `#elytraChatSpam true` and `#chatDebug true` are
needed - the verbose lines go through `logDebug`, which the second setting gates.

---

## 1. Stop landing short of the destination

`ElytraBehavior.pathNextSegment` resumes the search from the last node of the existing path, not
from the player. When that node falls into a pocket the native x4 pathfinder cannot leave, the
segment fails every tick, and the old code reinterpreted the failure as "arrived" as soon as the
player's own flight carried them within 16 blocks of the stuck node - landing however far short of
the real destination that happened to be.

- The "close enough to be genuinely finished" test is now distance to the **destination** (48
  blocks, the same radius the landing-spot search uses), not distance to the player.
- A failure further out sets `restartFromPlayer`, so the next tick searches from where the player
  actually is instead of retrying the same unreachable node.
- The failure is logged once per distinct failing node instead of once per tick (it used to be
  100+ lines a second), with diagnostics behind verbose logging: how long the native call took,
  and whether the resume point's chunk had arrived and whether the point is passable.

## 2. Take off from where the terrain leaves you

The takeoff had exactly one method - jump on the spot and light a rocket - and one response to
failure: `onLostControl()`, which ends the flight. Since the driving mod hands the same goal
straight back, that produced a loop of identical attempts, three rockets each, until the mod's own
retry cap ran out and the bot stood still with fireworks left in its inventory.

The takeoff is now a ladder of rungs, each a different answer to "how do we get into the air from
here", tried in order of cost. The ladder's state survives the process being torn down and handed
the goal again, so a restart resumes at the next rung rather than repeating the last one.

Driving all of it is `liftHeight(feet)`: the lowest height above the feet with room to stand, jump
and open the elytra, **and** a clear 16-block line towards the destination for the rocket to be
spent along (tested at three rises, so a steep line can clear the rim of a crevice and a flat one
still fits under the nether ceiling). `0` means here will do, `n` means the way out is `n` blocks
up, `-1` means a sealed pocket.

- **LAUNCH** - jump on the spot. Capped at three rockets **per spot**, counted across restarts.
- **CLIMB** (`PILLAR_UP`) - pillar or mine up to the measured height, on the ordinary calculation
  context so placing and breaking are both allowed. Bounded by `elytraTakeoffPillarMaxHeight`.
- **RELOCATE** (`WALK_TO_LAUNCH`) - walk to one of up to eight measured launch spots 8 to 16 blocks
  away, again on the ordinary context, so it can dig its way out of a closed pocket. With nothing
  in range it heads 48 blocks towards the goal on foot and asks again from there.
- **EXHAUSTED** - only now does it give up.

This replaces `pillarHeight`/`ringOpen`, which asked whether at least three of the four cardinal
neighbours were open. That answered wrongly in both directions: in a shaft no height ever satisfied
it, so the climb it existed to trigger never ran, and on open ground a single boulder two blocks
away read as walled in.

Two more things about the moment of takeoff:

- The look is held at the bearing to the destination for the whole jump, tilted up by the same angle
  as the runway that was measured clear - a fixed angle would be a different line from the one that
  was checked, steep enough to hit the roof of a flat tunnel that had just passed. An elytra keeps
  the direction it opened facing, so without any of this the glide started along whatever the last
  walking movement left the head pointing at.
- The elytra path's start node has to be somewhere the player can actually get to. It is picked
  from the node cubes above the feet, preferring one grid cell towards the destination, but every
  candidate must be in plain sight of where the player stands. Air on the far side of a wall is
  common in the nether, and choosing it put the start of the path - and the boost aimed at it -
  through that wall. The old fallback, when no cube qualified, was the player's own feet, whose
  4x4x4 node is solid by definition in that case: the native search then had no start node, threw,
  and the flight ended silently.

## 3. Fall back to fine nodes when the wide search fails

`ElytraBehavior.searchIn` retried a 4-block-node search with 2-block nodes when it came back a
stub, but not when it failed outright - and a search whose start sits in a node with any rock in it
returns nothing at all, which is the ordinary state of affairs for a takeoff out of a crevice or
off the top of a fungus. `elytraPathNodeAdaptive` now covers the failure case too.

## 4. Land somewhere sane, and not in a bastion

`isSafeBlock` accepted only netherrack, gravel and nether brick, so a bastion floor - polished
blackstone brick - was a candidate like any other, and plenty of safe ground was refused.

- `isSafeLandingBlock` judges on shape instead: accepted if the block has a full collision shape or
  a sturdy up face (obsidian, basalt, blackstone, soul soil), always refused for magma, any fluid,
  and bastion materials. Nether brick still follows `elytraAllowLandOnNetherFortress`, across the
  whole family rather than just the plain block. `elytraLandOnAnySolid` off restores the old list.
- `bastionRejectionReason` rejects a spot with bastion material in its footprint, or with a piglin
  brute within `elytraLandingBastionRadius` - brutes only exist in bastions, and one is visible
  before the blocks are. If the search exhausts itself with nothing else, the first spot rejected
  for a bastion is returned anyway: landing beside a bastion beats orbiting until the rockets run
  out.

## 5. Search inside a corridor pushed by another mod

`ElytraProcess.setPathCorridor(long[] chunkKeys, int halfWidthChunks)` and `clearPathCorridor()`,
callable by reflection from any thread, restrict the elytra path to a set of chunks - HunterBuddy
uses it to keep the flight on a known trail. The corridor lives on the process, not the behavior,
so it survives the behavior being rebuilt for a new destination.

The search runs in a second native context in which every chunk outside the corridor is masked
solid, within `elytraCorridorMaskRadius` chunks of the player - chunks the pathfinder was never
given count as air, so without that ring the search would just leave the corridor through the
unloaded fringe. When the corridor has no way through, the full map is consulted instead.

## 6. Swept collision box, so takeoff finds a pitch

The solver grew the player's hitbox with `inflate(motion)`, which grows symmetrically and therefore
reached backwards and downwards into the block behind and under the player. At takeoff that block
is the ground, so the solver saw a collision that was not on the flight path and reported "no pitch
solution". Now `expandTowards(motion)`, the vanilla swept volume, which grows only along the
direction of travel.

## 7. Believe the client about how hard and how long it boosts

The solver picks its pitch by simulating the boosted trajectory forward and raytracing it against the
terrain. It took two things from vanilla, and a client that boosts differently makes both of them lies:

- **Per-tick acceleration.** The simulation used a hardcoded `1.5`. A client with a firework speed
  multiplier flies a faster, flatter trajectory than the one that was raytraced, into blocks the
  raytrace never looked at - which is precisely what `hbonk` reports.
- **How many boosted ticks remain.** `FireworkBoost` derives them from the rocket entity's age against
  its lifetime. A client that cancels the rocket's removal keeps pushing after that runs out, and the
  solver has by then taken the `guaranteed == 0` branch: four simulated ticks with a single boosted one,
  and a flat pitch chosen for it, while the real flight is still accelerating.

Both are now settings - `elytraFireworkBoostMultiplier` and `elytraFireworkExtraBoostTicks` - for the
mod that changes them to set. At their defaults the arithmetic is byte for byte what it was.

Neither is java-only, deliberately: when a flight goes into terrain for no visible reason, these are the
first two things to read back with `#set`. The cost of that is that they persist, so a client killed
mid-flight leaves a value behind; the mod that sets them is expected to clear them on join.

## 8. Path line colour

`elytraPathColor` (default red) instead of the hardcoded colour.

## 9. Recognise a fall, and never stop while an option is left

`rememberTakeoffSpot` decided whether the ladder was still working the same spot, or had to start over at the
top, by horizontal distance alone. In the flight that changed it, the ladder gave up at y=74 - the ladder is
allowed to give up on a *spot*, once every rung has failed there - and the bot then fell 38 blocks straight
down into open ground. Every restart down there measured in three dimensions as the same spot it had already
exhausted, and aborted at once, a four-block climb away from a takeoff it never tried.

- A takeoff spot is remembered by horizontal distance **and drop**: more than `TAKEOFF_SAME_SPOT_RADIUS` (8
  blocks) sideways or below counts as a new spot, and the ladder starts over at `LAUNCH`. A climb is still the
  same spot as the ground it climbed from - only measuring three-dimensional distance without excluding rises
  broke that, and cleared the one-climb rule with it.
- `walkGotNowhere()` used to end the ladder outright when a walk stalled or found no path. The measured spots
  are as good as spent by then, since measuring again from the same ground hands back the same answer - so the
  ladder now moves on to `RELOCATE` instead, which walks onward towards the destination. The user's rule: a bot
  that is stuck goes on to the next thing.
- Walking onward is capped at `MAX_ONWARD_LEGS` (3) legs in a row that get nowhere, not 3 legs total - a leg
  that actually arrives resets the count, so a bot making real progress in short hops is never cut off
  mid-journey. Digging happens only along these legs, to reach a spot the ladder can try from - never towards
  the destination itself.
- Every arrival - a measured spot reached, or a leg that got there - restarts the ladder at the top
  (`ladderStartsAt`) via the same path `rememberTakeoffSpot` already takes for a genuinely new spot. A spot
  within `TAKEOFF_SAME_SPOT_RADIUS` used to be read as the one already being worked and never launched from
  again once that spot's rungs had run out.

## 10. Keep a heading across a long, straight destination

The native pathfinder only steps along six axes, and its search is drawn towards the goal by straight-line
distance, which dominates the cost of a step enough to make the search close to greedy. Aimed straight at a
destination far away, along an axis at all, the path runs along that axis and only turns once the remaining
distance sits at 45 degrees to it, however far out that turn ends up being - a bot asked to fly at 22 degrees
off the +X axis, towards the middle of the nether highway grid, flew the axis itself for tens of thousands of
blocks before the last leg of the journey turned onto the real heading.

`elytraPathLegLength` (128 blocks) aims every search that is not already within that distance of the
destination at a point on the straight line towards it instead of at the destination itself - past the chunks
the client has loaded, with the same margin the old destination-only search got from flying only through
loaded terrain. The path still runs along an axis at the start of each leg and turns onto the diagonal by its
end, but only within that one leg: it strays at most about a fifth of a leg's length off the true line, and
the next leg starts over from wherever this one actually ended, correcting any drift before it can compound.
Shorter legs hold the heading more closely, at the price of one more search per leg. `0` aims every search at
the destination, the old behaviour.

## 11. Walk out of a lava puddle the elytra can never leave

An elytra opens in lava - vanilla only refuses it in water - and a rocket lit straight up is what gets a bot
out of a lake or a sea within a few seconds. It does not get a bot out of a puddle one block deep: the floor
shuts the elytra again two ticks after every opening, before a rocket's thrust has done anything. In the
flight that changed it, this ran for over two minutes and sixty-odd openings, every rocket lit into the wall
of the pool and ignored by the server, until the user stopped it by hand.

`elytraLavaWalkOutSeconds` (10, the user's figure) tracks how long the current stay in lava has lasted -
started when a stay in lava begins with the elytra shut, so a flight that only skims a lake is never charged
for it, and kept running for as long as the bot is in lava at all, gliding or not, so an opening that bobs it
to the surface for a moment does not reset the clock either. Past that many seconds, the elytra stops being
opened and the bot walks out on foot instead, to the nearest place to stand within `LAVA_EXIT_RADIUS` blocks -
vanilla lifts a player walking into a ledge from inside a fluid onto it when there is room above, so the rim of
the pool one block up is as good as flat ground to walk onto. `0` walks out at once; a very large value keeps
to the elytra always.

## 12. Keep obsidian back for the regear box

HunterBuddy's regear lands, builds a box out of 24 obsidian to hold what it restocks, and has to build that box
whole or not at all. Nothing in Baritone itself knew that: a pillar, a bridge, or the elytra takeoff's own climb
would spend obsidian down to nothing if that was the only throwaway block left, leaving the regear with less
than a box's worth the next time it needed one.

`obsidianReserve` (25) is obsidian Baritone will not touch for any of that: once no more than this much is
left, obsidian is never chosen as a block to place, by a pillar, a bridge or anything else, and the other
`acceptableThrowawayItems` are used instead, or nothing is placed at all. The elytra takeoff's climb rung
(`CLIMB`, §2) refuses a pillar it could only finish by going into the reserve, and moves on to its next rung
rather than spend it. `0` keeps nothing back, the old behaviour.

---

## 13. Turn away from a hazard the rescue can't out-climb or out-dive

`ElytraBehavior.solveSurvival`'s last-resort pitch sweep only ever changed pitch: when the current heading could
not clear the whole simulated horizon (`elytraSimulationTicks`), the best it could do was climb or dive into
whatever was there, because a hazard that spans the flight path front-on has no pitch that gets past it, only a
heading that goes around it. Measured on two two-hour hb27 flights: 367-423 wall and ceiling hits each, almost
all of them this exact shape - the "not yet true" this fork's own status section used to name.

The sweep now keeps the current heading first: a straight pitch scan that survives the whole horizon behaves
exactly as before, nothing else runs. Only when it doesn't does the rescue also try yaw offsets of ±30, ±60 and
±90 degrees, in that order, stopping at the first one whose own pitch sweep survives the full horizon. A turned
heading is only adopted over whatever is currently best if it survives at least 2 ticks longer, so two options
that are near enough equal don't make the yaw flap between them from one tick to the next. Whichever heading
wins carries into the forced-firework branch too, so a rocket lit to escape is not lit back into the same wall
the turn just went around. Logged once per turn adopted (`solver: turning N degrees off the heading...`), not
once for every tick it stays adopted.

Not yet flown for real, so whether it actually cuts down the wall-hit count above is still an open question, not
a measured result.

## 14. Count a launch that falls straight back down against the same spot

Measured across the flights behind §2: a standing launch that opens the elytra, meets whatever boxes the pocket
in, and falls back down lands 13-18 blocks out and 10-12 blocks below where it jumped from - past
`rememberTakeoffSpot`'s existing 8-block/8-drop window for "same spot", so it read as new ground every time. The
ladder restarted at the top with launches 0/3 and repeated the identical failed launch a second time: two
minutes lost across two such loops in the flights this was measured on.

`rememberTakeoffSpot` now also treats a landing as the same spot when the flight that just ended was a launch
made at the `LAUNCH` rung (`launchedFromSpot`, set in `flightStarted` and read back once), the memory of that
spot is still fresh, the landing is within `TAKEOFF_SUCCESS_DISTANCE` (32 blocks) horizontally, and the drop is
at most twice `TAKEOFF_SAME_SPOT_RADIUS` (16 blocks). The drop is capped tighter than the horizontal distance on
purpose: a real fall to new ground - the 38-block drop `rememberTakeoffSpot`'s own comment already tells of -
still starts the ladder over, as it should. The launch count needed no new bookkeeping: `launchFromHere` already
increments `standingTakeoffs` at the moment of the attempt, not on a successful restart, so simply not resetting
the ladder is the whole fix. A third failed launch from the same spot still escalates to the `CLIMB` rung exactly
as a third failure always did - this changes what counts as the same spot, not the three-launch cap itself.

## 15. Lock the nether-pathfinder chunk cache against a native crash

Two `EXCEPTION_ACCESS_VIOLATION` crashes in `nether_pathfinder.dll` (the bundled `dev.babbaj:nether-pathfinder`
v1.4.1, jar-in-jar) traced back to `Context::cacheMutex` not being held on every access to the native chunk hash
map: `getOrCreateChunk` (the JNI entry point) takes it, but `getChunkOrAir` (read on every A* node inside
`findPathSegment`) and the `findAir` search used to locate a start/goal air cell (through `getChunkNoMutex`,
named for exactly this) did not, while chunk generation running on the parallel executor threads inserts into
the same map under the lock. A reader running unlocked while a writer holds the lock on another thread corrupts
the hash table.

Both unlocked paths now take `cacheMutex` for the duration of the lookup (`PathFinder.cpp`, in `getChunkOrAir`
and in the `findAir` loop body). `cullFarChunks`'s `erase_if` was already locked in the 1.4.1 source, so it
needed no change. Neither call site holds the lock while calling anything that also locks it, so there is no
deadlock.

The DLL is rebuilt from the upstream `v1.4.1` tag with only that lock added, plus build-only accommodations to
get the source through MSVC/CMake instead of the zig-cc/clang toolchain upstream actually ships with (no clang
toolchain was available here to build with instead): a `noinline` macro that avoids GCC's `__attribute__`
syntax, a missing `<algorithm>` include, and making `Path` a move-only type instead of a copy-attempting
aggregate so MSVC's STL stops trying to instantiate a deleted `unique_ptr` copy constructor. None of these
change behavior or the JNI ABI. The DLL is also linked against the static MSVC runtime
(`CMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded`, i.e. `/MT`) instead of the default `/MD`: the upstream zig-cc build
carries its own runtime, but an `/MD` rebuild would depend on MSVCP140.dll/VCRUNTIME140.dll, which is not
guaranteed to be present on a machine without the VC++ redistributable installed. `dumpbin /DEPENDENTS` on the
result now lists only `KERNEL32.dll`. The patched DLL replaces only the Windows x64 binary inside the jar's
`natives.zip.xz`; nothing else in that archive or in the jar's classes changed. `gradle.properties` and the
root `build.gradle` now resolve `dev.babbaj:nether-pathfinder` to that patched jar
(`libs/nether-pathfinder-1.4.1-hbpatched.jar`, via a `flatDir` repository) instead of the upstream release; the
`include` in `fabric/build.gradle` that jars it into the mod is untouched.

Exercised outside the mod with the tag's own `main.cpp` test harness (`-DTESTING=ON`), against the patched
source built the same way: the default run (seed `146008555100680`, mostly fake-air chunks) and a second run
forcing real per-seed chunk generation across the parallel executor threads both complete cleanly and
repeatably, no deadlock or crash, a path found each time.

Built into hb29. Not yet flown in the mod itself - this closes the two crashes diagnosed from hs_err logs and
source, and the lock has now run standalone, but nothing here has run inside a live Baritone session yet.

## 16. Anchor the route line so a lateral drift corrects itself

§10's legs keep every search from riding one axis for the whole journey, but they aim along the line from the
current position to the destination, and that line is not fixed - it swings every time the heading drifts off
by even a fraction of a degree over a leg or two. On a long enough flight, a swept-heading path ends up some
distance to the side of where it started out heading, and at that point a further destination that is still far
off barely changes the aim: the remaining lateral offset, however large, is a vanishingly small angle against
the remaining distance, so nothing left in the search ever steers back onto the original line. The drift itself,
whatever holds the heading a fraction of a degree off course over any one leg, is not something this section
touches; it is the aim that never resorbs it that changes here.

`elytraRouteAnchorX` and `elytraRouteAnchorZ` (both `Long.MIN_VALUE`, meaning off, by default) fix a point that,
together with the destination, defines a route line that does not move with the player. With both set, the leg
target is no longer read off the line from the current position to the destination, but off the orthogonal
projection of the current position onto the anchor-to-destination line instead - clamped to stay between the
anchor and the destination, so standing behind the anchor or past the destination on that axis does not throw
the projection outside the flight. The leg is then measured the same distance ahead as before (`elytraPathLegLength`,
or further out past the loaded chunks, unchanged), just walked along the anchor line rather than along the line
to the player. A lateral offset now bends the aim back towards the line instead of leaving it alone, and by an
amount that grows with the offset: the further off the line the flight has drifted, the harder the next leg
aims back towards it, converging rather than snapping back in one turn. With either setting left at its default,
every leg target is computed exactly as it was before this section - the anchor changes nothing until both are
set. The destination-only case (already inside a leg's length, legs turned off, corridor search active) is
unaffected either way. The end-of-flight log line records the anchor being in effect (`... on the anchored line`)
alongside the existing leg-length figure.

Built into hb31. Not yet flown in the mod itself.

## Settings added by this fork

| Setting | Default | What it does |
| --- | --- | --- |
| `elytraTakeoffPillar` | `true` | Allow the takeoff to climb to a launchable height |
| `elytraTakeoffPillarMaxHeight` | `12` | How far it will climb before walking somewhere else instead |
| `elytraPathNodeSize` | `4` | Minimum node size for the nether pathfinder, `4` or `2` |
| `elytraPathNodeAdaptive` | `true` | Retry with 2-block nodes when the 4-block search stubs or fails |
| `elytraCorridor` | `true` | Honour a corridor pushed by another mod |
| `elytraCorridorMaskRadius` | `12` | Chunk radius in which non-corridor chunks are masked solid |
| `elytraPathColor` | red | Colour of the rendered flight path |
| `elytraLandOnAnySolid` | `true` | Land on any solid top face, not just netherrack and gravel |
| `elytraLandingBastionRadius` | `48` | Piglin brute radius that rejects a landing spot |
| `elytraFireworkBoostMultiplier` | `1.5` | Firework acceleration the flight simulation assumes |
| `elytraFireworkExtraBoostTicks` | `0` | Ticks a boost lasts beyond the rocket's own lifetime |
| `elytraPathLegLength` | `128` | How far ahead a search aims on a long, straight destination, in blocks |
| `elytraRouteAnchorX` | `Long.MIN_VALUE` | X of a fixed point that, with the destination, pins the leg-aim line (off until Z is also set) |
| `elytraRouteAnchorZ` | `Long.MIN_VALUE` | Z of that fixed point (off until X is also set) |
| `elytraLavaWalkOutSeconds` | `10` | Seconds in lava before the takeoff gives up on the elytra and walks out |
| `obsidianReserve` | `25` | Obsidian never spent by a pillar, a bridge or anything else Baritone places |

Note that `elytraFireworkSpeed` is an older, unrelated setting: the minimum speed below which a firework
is deployed, not an acceleration.

## Status

Flown for real, at length, in HunterBuddy's own long-distance runs - not just the incidents each section
above was written against. The longest single test so far: just over two hours unattended, 213,915 blocks,
42 takeoffs, zero abandoned. The one flight that struggled hardest in that run lost a valid pitch solution
for 18 ticks against tight terrain and recovered on its own inside one second, which is the solver's own
fireworked-recovery path (§7's simulation feeding it a trajectory to raytrace) doing what it is for, not a
gap in the takeoff ladder.

What is not yet true: the ladder's rescue used to only ever adjust pitch, never yaw, so a spot that needed a
turn rather than a climb or a dive was clipped before the main solver could find a real path again - measured
as the near-totality of this fork's wall and ceiling hits, concentrated in exactly the flights that hit a
lost-pitch moment at all. hb28 has the rescue try turning as well (§13), but that has not flown yet, so whether
it actually brings the wall-hit count down is still unmeasured, not established. And the turn search itself is
six discrete headings, ±30/±60/±90 degrees off the current one, not a continuous scan: a hazard that only
clears at some other angle, or that would need more than a 90-degree turn to get around, still gets nothing
better than the old pitch-only answer.

Also unmeasured in flight: hb28's fix for a standing launch that falls straight back down being read as a new
spot and restarting the ladder (§14). The mechanism is a straightforward relaxation of an existing same-spot
check, but "does it actually stop the double-loop measured in hb27" is a question for the next long run, the
same as §13.

`nether_pathfinder.dll` (the native chunk pathfinder this fork inherits, not written here) has crashed the
game once, reading an address of `-1` out of `getOrCreateChunk`, in the middle of a burst of path
recalculations. Its chunk cache has a mutex, but three of the functions that touch it -
`getChunkOrAir`, `getRealChunkOrDefault`, and the `erase_if` in `findPathFull` - never take it, while the
chunk generator runs on its own three-thread pool; a generator thread inserting or erasing a chunk while one
of those three reads it is a real data race, and a corrupted hash table reading `-1` back as a pointer is
exactly the crash that produced. One occurrence in many hours of flight, so left alone for now - the fix,
when it is worth doing, is taking that same lock in those three functions, in the native source, not
switching to HackerRouter's fork of it, which locks the insert side only and brings an unrelated Overworld/End
rewrite along with it.

The takeoff ladder was reviewed twice after it was written, and what those reviews found has been fixed:
the lift search skipping its own height near the world ceiling, the runway approved at one angle and
launched at another, a spot-memory timer that could never elapse, a climb that cleared the one-climb
rule, the ledge search skipped after a climb, both path callbacks able to write state into a behavior
that had already been torn down, and the lift search re-walking a few thousand blocks every tick.


## Credits and provenance

  -Leijurv
  -Dekrom