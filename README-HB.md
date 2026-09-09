# HunterBuddy fork of dekrom/baritone

A fork of dekrom's Baritone (`v1.3.0-1.21.11`, Mojang mappings) carrying the changes the
HunterBuddy Meteor addon needs for long-distance elytra flight in the nether on 2b2t. Everything
below is in the elytra code; nothing else in Baritone is touched.

Built with `./gradlew :fabric:build -Pmod_version=1.3.0-hbN-1.21.11`, output in
`dist/baritone-api-fabric-1.3.0-hbN-1.21.11.jar`. Current version: **hb9**.

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

- The look is held at the bearing to the destination, tilted up, for the whole jump. An elytra
  keeps the direction it opened facing, so without this the glide started along whatever the last
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

## 7. Path line colour

`elytraPathColor` (default red) instead of the hardcoded colour.

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

## Status

None of this has been tested against a full nether crossing end to end. The takeoff ladder has been
seen to dig its way out of a closed hole and carry on, which is what it was written for.
