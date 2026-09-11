# hunterbuddy-baritone

A fork of dekrom's Baritone (`v1.3.0-1.21.11`, Mojang mappings) carrying the changes the
HunterBuddy Meteor addon needs for long-distance elytra flight in the nether on 2b2t. Everything
below is in the elytra code; nothing else in Baritone is touched.

Built with `./gradlew :fabric:build -Pmod_version=1.3.0-hbN-1.21.11`, output in
`dist/baritone-api-fabric-1.3.0-hbN-1.21.11.jar`. Current version: **hb27**.

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

What is not yet true: the ladder's rescue still only ever adjusts pitch, never yaw, so a spot that needs a
turn rather than a climb or a dive can still be clipped before the main solver finds a real path again -
measured as the near-totality of this fork's wall and ceiling hits, concentrated in exactly the flights that
hit a lost-pitch moment at all. Not fixed yet.

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