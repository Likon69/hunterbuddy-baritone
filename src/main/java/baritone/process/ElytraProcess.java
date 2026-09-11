/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementFall;
import baritone.process.elytra.ElytraBehavior;
import baritone.process.elytra.FlightLog;
import baritone.process.elytra.NetherPathfinderContext;
import baritone.process.elytra.NullElytraProcess;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.PathingCommandContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public class ElytraProcess extends BaritoneProcessHelper implements IBaritoneProcess, IElytraProcess, AbstractGameEventListener {
    public State state;
    private boolean goingToLandingSpot;
    private BetterBlockPos landingSpot;
    private boolean reachedGoal; // this basically just prevents potential notification spam
    private Goal goal;
    private ElytraBehavior behavior;
    private boolean predictingTerrain;
    /**
     * The chunks the elytra path is allowed to use, as {@link ChunkPos#asLong} keys, already dilated. Empty
     * means no corridor. Lives here, not on the behavior, since {@link #pathTo0} rebuilds the behavior per
     * destination; replaced wholesale on every push, never mutated, so any thread reading it sees a consistent set.
     */
    private volatile LongSet corridor = LongSets.EMPTY_SET;
    /**
     * The shortest drop {@link WalkOffCalculationContext} offers, and therefore the least we have to ask to descend
     * for the walk off path to be forced to contain a {@link MovementFall} instead of a flight of stairs.
     */
    private static final int TAKEOFF_MIN_FALL_HEIGHT = 8;
    /**
     * How long to sit in a takeoff state with neither a path nor a calculation running before giving up. Both of
     * those are dead ends: nothing in this process starts another calculation, so we would stand still forever.
     */
    private static final int TAKEOFF_STALL_TICKS = 60;
    /**
     * How long a standing takeoff gets to leave the ground and open the elytra before it is written off as a dud
     * (a ceiling we didn't account for, knockback, a cobweb, ...).
     */
    private static final int STANDING_TAKEOFF_TIMEOUT_TICKS = 40;
    /**
     * How many standing takeoffs to attempt before giving up. Each one costs a firework, so if this many in a row
     * have put us straight back on the ground then something about this spot is wrong and retrying just burns
     * rockets.
     */
    private static final int MAX_STANDING_TAKEOFFS = 3;
    /**
     * How many ledges to walk towards before concluding that walking off one is never going to happen.
     */
    private static final int MAX_WALK_OFF_ATTEMPTS = 2;
    /**
     * How far above our feet to look for the first clear 4x4x4 cube when taking off from where we stand. A
     * duration-1 rocket lifts a stationary player around 25 blocks straight up before it dies, so a cube any
     * further up is out of reach of a vertical takeoff anyway.
     */
    private static final int TAKEOFF_MAX_EXIT_HEIGHT = 32;
    /**
     * How far above our feet {@link #liftHeight} will look for a stance a takeoff can actually leave from. A
     * duration-1 rocket lifts a stationary player around 25 blocks, and every one of these blocks has to be
     * climbed (placed or mined) before the takeoff even starts, so past this it is cheaper to walk somewhere else.
     */
    private static final int TAKEOFF_MAX_LIFT = 24;
    /**
     * How far the way out of a launch stance has to be clear, horizontally, for the takeoff to have anywhere to
     * go. Roughly the distance a takeoff rocket covers before it dies.
     */
    private static final int TAKEOFF_RUNWAY = 16;
    /**
     * The climbs tested over that runway, shallowest first: flat enough to fit under a nether ceiling, steep
     * enough to clear the rim of a crevice. A stance counts as clear when any one of them is.
     */
    private static final int[] TAKEOFF_RUNWAY_RISES = {2, 6, 10};
    /** How far around us {@link #walkToLaunch} looks for another spot to take off from. */
    private static final int TAKEOFF_RELOCATE_RADIUS = 16;
    /** The vertical band of that search, above and below our feet. */
    private static final int TAKEOFF_RELOCATE_HEIGHT = 12;
    /** How many spots that search offers the pathfinder at once, and how many columns it is allowed to test. */
    private static final int TAKEOFF_RELOCATE_SPOTS = 8;
    private static final int TAKEOFF_RELOCATE_COLUMN_BUDGET = 400;
    /**
     * How many times the ladder may walk to spots {@link #findLaunchSpots} measured, on one stretch of ground,
     * before it stops measuring there and walks on instead. A leg towards the goal ends on new ground and starts
     * this count over: see {@link #MAX_ONWARD_LEGS}.
     */
    private static final int MAX_RELOCATIONS = 2;
    /** How many times one takeoff may climb before admitting defeat. */
    private static final int MAX_CLIMBS = 2;
    /**
     * How many 48-block legs towards the goal in a row may get nowhere before the ladder admits defeat. A leg
     * that reaches its point starts the count over. Only a leg that stalls or finds no path counts against it -
     * one that gets there but has to tunnel through solid ground on the way is not a failure.
     */
    private static final int MAX_ONWARD_LEGS = 3;
    /** How far the relocation search looks on its second, wider pass, and what that pass may spend. */
    private static final int TAKEOFF_RELOCATE_RADIUS_WIDE = 32;
    private static final int TAKEOFF_RELOCATE_COLUMN_BUDGET_WIDE = 1200;
    /** How far to walk towards the goal when even the wide relocation search has nothing to offer. */
    private static final int TAKEOFF_WALK_ONWARDS = 48;
    /**
     * How long a rung that is walking or climbing may go without getting any closer to its goal before it is
     * abandoned. Progress, not activity: a stall counter that a mere re-plan resets can loop forever on a
     * segment that keeps failing and being retried without ever making the counter fire.
     */
    private static final int TAKEOFF_NO_PROGRESS_TICKS = 200;
    /**
     * How far, horizontally, we have to get from where we jumped before the takeoff counts as having worked and
     * the ladder's memory of the spot is dropped. Horizontal only, since a climb straight up a shaft would
     * otherwise satisfy it without going anywhere. Being airborne is not the same as having taken off - a glide
     * pinned against a wall or scraping a ceiling stays airborne without getting anywhere either.
     */
    private static final int TAKEOFF_SUCCESS_DISTANCE = 32;
    /**
     * How long in the air before wherever we come down counts as somewhere new for the ledge search, which is
     * all this still decides: see {@link #TAKEOFF_SUCCESS_DISTANCE}.
     */
    private static final int TAKEOFF_SUCCESS_TICKS = 60;
    /**
     * How long a standing launch keeps the ground around it - within {@link #TAKEOFF_SAME_SPOT_RADIUS} - out of
     * the spots {@link #findLaunchSpots} offers. Without this a relocation search just re-measures the same
     * ground and hands back the very spot a launch just failed from.
     */
    private static final int RECENT_LAUNCH_TICKS = 20 * 60;
    /**
     * What the collision counter below has to reach to count as pinned rather than as a bump - long enough
     * (three seconds of solid contact) that an ordinary flight's occasional terrain clip never reaches it.
     */
    private static final int TAKEOFF_PINNED_TICKS = 60;
    /** How steeply a pinned glide is pointed down to put itself on the floor. Shallow enough not to dive. */
    private static final float TAKEOFF_PINNED_SINK_PITCH = 40.0F;
    /** How far below a pinned glide has to be clear of lava before it is allowed to set itself down. */
    private static final int TAKEOFF_PINNED_SAFE_DROP = 12;
    /**
     * How far we have to move sideways, or drop, for the ladder to consider itself at a new spot and start over
     * from the top.
     */
    private static final int TAKEOFF_SAME_SPOT_RADIUS = 8;
    /** How long the ladder's memory of a spot survives with nothing happening. */
    private static final int TAKEOFF_MEMORY_TICKS = 20 * 180;
    /** The pitch a takeoff holds while it jumps, so the glide starts pointing up and down the goal line. */
    private static final float TAKEOFF_PITCH = -30.0F;
    private int takeoffStallTicks;
    private int standingTakeoffs;
    private int walkOffAttempts;
    private int takeoffAirborneTicks;
    private boolean walkOffImpossible;
    private boolean takeoffBoostPending;
    /**
     * Ticks since a takeoff opened the elytra, or {@code -1} outside a takeoff. Only the server can shut an
     * elytra again, so one that is shut while we are still in the air this soon after opening it is a takeoff
     * the server refused, which is worth telling the user apart from one that merely didn't get anywhere.
     */
    private int takeoffOpenedTicksAgo = -1;
    /**
     * How long after the elytra opens the takeoff path is left alone. The obstacle pass recomputes from the
     * player's feet and would otherwise immediately replace the takeoff's own carefully-chosen start node. Long
     * enough to clear the launch site, short enough that a real obstacle a moment later is still routed around.
     */
    private static final int TAKEOFF_PATH_GRACE_TICKS = 20;
    private boolean lavaPathRequested;
    /** How far from our feet the walk out of the lava looks for somewhere to stand. */
    private static final int LAVA_EXIT_RADIUS = 5;
    /** How long the walk may head for one place without getting closer to it before it tries another. */
    private static final int LAVA_EXIT_STALL_TICKS = 60;
    /** How long we may be out of the lava, bobbing at its surface, without that ending the stay in it. */
    private static final int LAVA_OUT_GRACE_TICKS = 20;
    /** When the current stay in lava began and when we were last in it, in player ticks; -1 when there is none. */
    private int lavaSinceTick = -1;
    private int lavaLastTick = -1;
    /** Where the walk out of the lava is heading, and how close to it the walk has got. */
    private BetterBlockPos lavaExit;
    private double lavaExitClosest;
    private int lavaExitProgressTick;
    /** Places the walk out of the lava gave up on during this stay. */
    private final Set<BetterBlockPos> lavaExitsTried = new HashSet<>();
    private boolean lavaNoExitNoted;
    /** When the walk out of the lava last looked for somewhere to go. */
    private int lavaExitSearchTick;
    /** Whether this takeoff sequence has already climbed once. One climb per spot: a stance that still fails after it is walled in some other way height can't fix. */
    private boolean pillared;
    /** The feet-Y the current pillar is climbing to. */
    private int pillarTargetY;
    /**
     * The rungs of the takeoff ladder, in the order they are tried. Each one is a different answer to "how do
     * we get into the air from here", not a retry of the last one: jump on the spot, climb to where jumping
     * works, walk to where it works, and only then give up.
     */
    private enum Stage {LAUNCH, CLIMB, RELOCATE, EXHAUSTED}

    /**
     * Where the ladder below is being run, and how far down it has got. Deliberately <i>not</i> cleared by
     * {@link #onLostControl}: the mod driving us hands the same goal straight back after every failure, so state
     * living on the behavior would restart the ladder at the top every time. {@link #forgetTakeoffSpot} clears
     * it once the takeoff has actually worked, moved on, or gone stale.
     */
    private BetterBlockPos takeoffSpot;
    private Stage takeoffStage = Stage.LAUNCH;
    private int takeoffSpotTick;
    /**
     * Whether the flight that just ended was a launch off {@link #takeoffSpot} at the {@code LAUNCH} rung, set in
     * {@link #flightStarted()} and read once by the next {@link #rememberTakeoffSpot} to tell a launch that fell
     * straight back down from an actual new spot.
     */
    private boolean launchedFromSpot;
    private int relocations;
    private int climbs;
    /** Consecutive ticks of gliding, to tell a flight from a takeoff that got off the ground and no further. */
    private int flyingTicks;
    /** Airborne ticks spent in contact with something and not getting past it. See {@link #TAKEOFF_PINNED_TICKS}. */
    private int pinnedTicks;
    /** The path node the flight was nearest to last tick, to tell a pin from a squeeze that is still moving. */
    private int lastNear;
    /**
     * The takeoff journal: records what a launch was measured and asked to do, so a takeoff that flies into
     * terrain anyway can be told apart as either a bad clearance measurement or the path being silently
     * replaced, on the first airborne tick, by one recomputed from the feet.
     */
    private int takeoffLogTicks;
    private BetterBlockPos takeoffLogFrom;
    private BetterBlockPos takeoffLogStart;
    private boolean takeoffLogStartWasCube;
    private int takeoffLogLift;
    private int takeoffLogRise;
    private String takeoffLogNode = "";

    /** The best (least) goal heuristic reached since the current rung started, and when it was reached. */
    private double takeoffProgressBest;
    private int takeoffProgressTick;

    /** One tick's worth of {@link #liftHeight}, which is walked several times per tick while stuck. */
    private BetterBlockPos liftCacheAt;
    private int liftCacheTick = -1;
    private int liftCacheValue = -1;

    /**
     * Standing launches made recently, as {x, z, tick}, oldest first: see {@link #RECENT_LAUNCH_TICKS}. Like the
     * rest of the ladder's memory this is about the ground, not the behavior, so it survives onLostControl.
     */
    private final ArrayDeque<int[]> recentLaunches = new ArrayDeque<>();
    /** How many columns the searches of the current {@link #walkToLaunch} passed over next to a recent launch. */
    private int lastSpotsSkipped;

    /** Legs walked towards the goal since the last takeoff that worked; see {@link #MAX_ONWARD_LEGS}. */
    private int onwardLegs;
    /** Whether the walk in progress is a leg towards the goal, rather than a walk to measured spots. */
    private boolean walkingOnwards;
    /**
     * Every rocket a behavior has lit, whichever behavior it was: the process outlives them, and one is rebuilt
     * mid flight to go to a landing spot.
     */
    private int rocketsLit;

    /** Below this speed, in blocks per tick, a tick of flight counts as slow in the flight record. */
    private static final double SLOW_FLIGHT_SPEED = 0.5;
    /**
     * Every position the server has forced on us, whichever behavior was running: setbacks, mostly, but also a
     * teleport the server sends again because it was not acknowledged in time, so lag inflates it.
     */
    private int serverCorrections;
    /**
     * Wide paths that wandered enough for the search to try again with fine nodes, and how many of those the fine
     * path then replaced. Counted on the pathfinder's own thread, hence atomic.
     */
    private final AtomicInteger detourRetries = new AtomicInteger();
    private final AtomicInteger detourSwaps = new AtomicInteger();
    /**
     * When the last flight ended, by the wall clock, so the next one can say how long the bot was on the ground
     * in between. The tick counter would be wrong across a reconnect, which starts it over.
     */
    private long lastFlightEndMs = -1;

    /** The flight record's account of the flight in progress: where it started, what it spent and what it hit. */
    private Vec3 flightFrom;
    private Vec3 flightLastPos;
    private BetterBlockPos flightDestination;
    private int flightLastTick;
    private boolean flightPinDecisionNoted;
    private int flightRocketsAtStart;
    private int flightCorrectionsAtStart;
    private int flightDetourRetriesAtStart;
    private int flightDetourSwapsAtStart;
    private double flightTravelled;
    private double flightTopSpeed;
    private int flightSlowTicks;
    private int flightWallHits;
    private int flightCeilingHits;
    private double flightFarthest;
    private boolean flightPinNoted;
    /** Where a fall that is not a flight picked up speed, or {@code NaN} while we are not falling. */
    private double fallFromY = Double.NaN;
    private String fallState = "";
    /** The last ladder line written, so that one decision taken over and over is written once. */
    private String lastLadderLine = "";

    @Override
    public void onLostControl() {
        this.state = State.START_FLYING; // TODO: null state?
        this.goingToLandingSpot = false;
        this.landingSpot = null;
        this.reachedGoal = false;
        this.goal = null;
        this.takeoffStallTicks = 0;
        // standingTakeoffs and pillared are deliberately left alone: they belong to the spot the takeoff
        // ladder is working on, not to the behavior, and the whole point of that ladder is that it survives
        // this teardown. rememberTakeoffSpot clears them when we are somewhere else.
        this.walkOffAttempts = 0;
        this.takeoffAirborneTicks = 0;
        this.walkOffImpossible = false;
        this.takeoffBoostPending = false;
        this.takeoffOpenedTicksAgo = -1;
        this.lavaPathRequested = false;
        // the flight this described is no longer ours once control is lost mid-air; whatever happens to it next isn't a takeoff outcome.
        this.launchedFromSpot = false;
        forgetLava();
        this.pillarTargetY = 0;
        destroyBehaviorAsync();
    }

    private ElytraProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public static IElytraProcess create(final Baritone baritone) {
        return NetherPathfinderContext.isSupported()
                ? new ElytraProcess(baritone)
                : new NullElytraProcess(baritone);
    }

    @Override
    public boolean isActive() {
        return this.behavior != null;
    }

    @Override
    public void resetState() {
        BlockPos destination = this.currentDestination();
        this.onLostControl();
        if (destination != null) {
            this.pathTo(destination);
            this.repackChunks();
        }
    }

    private static final String TAKEOFF_ADVICE_MSG = "Consider starting from a higher location, near an overhang. Or, you can disable elytraAutoJump and just manually begin gliding.";
    private static final String AUTO_JUMP_FAILURE_MSG = "Failed to compute a walking path to a spot to jump off from. " + TAKEOFF_ADVICE_MSG;

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        final long seedSetting = Baritone.settings().elytraNetherSeed.value;
        if (seedSetting != this.behavior.context.getSeed()) {
            logDirect("Nether seed changed, recalculating path");
            this.resetState();
        }
        if (predictingTerrain != Baritone.settings().elytraPredictTerrain.value) {
            logDirect("elytraPredictTerrain setting changed, recalculating path");
            predictingTerrain = Baritone.settings().elytraPredictTerrain.value;
            this.resetState();
        }

        this.behavior.onTick();

        if (!ctx.player().isFallFlying()) {
            // Before anything below can return: a flight that ends in lava goes straight to the lava takeoff,
            // and its record has to be written before that.
            if (this.flyingTicks > 0) {
                flightEnded();
                this.flyingTicks = 0;
            }
            trackFall();
        }

        trackLava();
        if (!ctx.player().isFallFlying() && ctx.player().isInLava()) {
            return lavaTakeoff();
        }

        if (calcFailed) {
            FlightLog.log("ladder: the walking path for " + this.state + " could not be computed");
            if (this.state == State.PILLAR_UP) {
                // Nothing to climb to that a path can reach; the next rung looks somewhere else instead of
                // asking for the same climb again.
                this.takeoffStage = Stage.RELOCATE;
            } else if (this.state == State.WALK_TO_LAUNCH) {
                walkGotNowhere();
            }
            if (this.state == State.LOCATE_JUMP || this.state == State.GET_TO_JUMP
                    || this.state == State.PILLAR_UP || this.state == State.WALK_TO_LAUNCH) {
                // All four hand back to the ladder, which decides what to try next based on how far down it
                // already is rather than repeating whatever just failed.
                return standingTakeoff();
            }
            onLostControl();
            logDirect(AUTO_JUMP_FAILURE_MSG);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // No landing business while in lava: the solver is busy climbing out, and a landing search from inside
        // the pool starts there and fails at once.
        final boolean inLava = ctx.player().isInLava();
        boolean safetyLanding = false;
        if (ctx.player().isFallFlying() && !inLava && shouldLandForSafety()) {
            if (Baritone.settings().elytraAllowEmergencyLand.value) {
                logDirect("Emergency landing - almost out of elytra durability or fireworks");
                safetyLanding = true;
            } else {
                logDirect("almost out of elytra durability or fireworks, but I'm going to continue since elytraAllowEmergencyLand is false");
            }
        }
        if (ctx.player().isFallFlying() && !inLava && this.state != State.LANDING && (this.behavior.pathManager.isComplete() || safetyLanding)) {
            final BetterBlockPos last = this.behavior.pathManager.path.getLast();
            if (last != null && (ctx.player().position().distanceToSqr(last.getCenter()) < (48 * 48) || safetyLanding) && (!goingToLandingSpot || (safetyLanding && this.landingSpot == null))) {
                logDirect("Path complete, picking a nearby safe landing spot...");
                BetterBlockPos landingSpot = findSafeLandingSpot(ctx.playerFeet());
                // if this fails we will just keep orbiting the last node until we run out of rockets or the user intervenes
                if (landingSpot != null) {
                    this.pathTo0(landingSpot, true);
                    this.landingSpot = landingSpot;
                }
                this.goingToLandingSpot = true;
            }

            if (last != null && ctx.player().position().distanceToSqr(last.getCenter()) < 1) {
                if (Baritone.settings().notificationOnPathComplete.value && !reachedGoal) {
                    logNotification("Pathing complete", false);
                }
                if (Baritone.settings().disconnectOnArrival.value && !reachedGoal) {
                    // don't be active when the user logs back in
                    this.onLostControl();
                    if (ctx.world() instanceof ClientLevel clientLevel) {
                        clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
                    }
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                reachedGoal = true;

                // we are goingToLandingSpot and we are in the last node of the path
                if (this.goingToLandingSpot) {
                    this.state = State.LANDING;
                    logDirect("Above the landing spot, landing...");
                }
            }
        }

        if (this.state == State.LANDING) {
            final BetterBlockPos endPos = this.landingSpot != null ? this.landingSpot : behavior.pathManager.path.getLast();
            if (ctx.player().isFallFlying() && endPos != null) {
                Vec3 from = ctx.player().position();
                Vec3 to = new Vec3(((double) endPos.x) + 0.5, from.y, ((double) endPos.z) + 0.5);
                Rotation rotation = RotationUtils.calcRotationFromVec3d(from, to, ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(new Rotation(rotation.getYaw(), 0), false); // this will be overwritten, probably, by behavior tick

                if (ctx.player().position().y < endPos.y - LANDING_COLUMN_HEIGHT) {
                    logDirect("bad landing spot, trying again...");
                    landingSpotIsBad(endPos);
                }
            }
        }

        if (ctx.player().isFallFlying()) {
            // Airborne, but not necessarily away: only a flight that gets us clear of where we jumped from
            // answers what the ladder was working through. See TAKEOFF_SUCCESS_DISTANCE for why lasting no
            // longer does.
            if (this.flyingTicks++ == 0) {
                flightStarted();
            }
            final boolean clear = this.takeoffSpot == null
                    || horizontalDistSq(this.takeoffSpot, ctx.playerFeet()) > TAKEOFF_SUCCESS_DISTANCE * TAKEOFF_SUCCESS_DISTANCE;
            if (clear) {
                forgetTakeoffSpot();
            } else if (this.flyingTicks > TAKEOFF_SUCCESS_TICKS) {
                // Up long enough that wherever this comes down is a different question from where it left, so
                // the ledge search runs there again, as it always did at this point. The ladder keeps its count:
                // this is still a takeoff that has not got anywhere.
                this.walkOffImpossible = false;
            }
            flightTick();
            if (this.state == State.TAKEOFF_JUMP) {
                // the elytra opened without us having asked for it, pick up from wherever that leaves us
                this.state = State.START_FLYING;
                this.takeoffStallTicks = 0;
                this.takeoffBoostPending = true;
                this.takeoffOpenedTicksAgo = 0;
            }
            if (this.takeoffOpenedTicksAgo >= 0) {
                this.takeoffOpenedTicksAgo++;
            }
            writeTakeoffJournal();
            behavior.landingMode = this.state == State.LANDING;
            this.goal = null;
            baritone.getInputOverrideHandler().clearAllKeys();

            // Pinned means both halves: flying into something, and not getting past it. Collision alone would
            // also describe a bot squeezing along a tight corridor, which is unpleasant but is working.
            // Decays rather than resets, since a pin is not literally every tick; the decay rate still empties
            // the counter in under a second of ordinary flight, so a normal terrain clip never reaches it.
            final int near = behavior.pathManager.getNear();
            final boolean stuck = ctx.player().horizontalCollision && near <= this.lastNear;
            this.lastNear = near;
            this.pinnedTicks = stuck ? this.pinnedTicks + 1 : Math.max(0, this.pinnedTicks - 4);
            notePinned(stuck);
            if (this.pinnedTicks > TAKEOFF_PINNED_TICKS && groundBelowIsSafe()) {
                // Every pitch collides, so the solver reports no pitch solution - the one branch of tick() that
                // returns before it can light a firework - and left alone the bot just scrapes along the wall.
                // Cut the thrust and land instead, handing control back to the takeoff ladder to mine a way out.
                if (this.pinnedTicks == TAKEOFF_PINNED_TICKS + 1) {
                    logDirect("Flying into the same wall every tick and getting nowhere - setting down to try again from the ground.");
                }
                baritone.getLookBehavior().updateTarget(
                        new Rotation(ctx.playerRotations().getYaw(), TAKEOFF_PINNED_SINK_PITCH), false);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }

            behavior.tick();
            if (this.takeoffBoostPending) {
                // a takeoff boost that couldn't be used the moment the elytra opened. after behavior.tick() so
                // that it doesn't fight the boost bookkeeping if the solver already decided to use one
                this.takeoffBoostPending = false;
                this.behavior.useFireworkForTakeoff();
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        } else if (this.state == State.LANDING) {
            if (ctx.playerMotion().multiply(1, 0, 1).length() > 0.001) {
                logDirect("Landed, but still moving, waiting for velocity to die down... ");
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            logDirect("Done :)");
            baritone.getInputOverrideHandler().clearAllKeys();
            this.onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        this.flyingTicks = 0;
        this.pinnedTicks = 0;
        this.lastNear = 0;

        if (this.takeoffOpenedTicksAgo >= 0) {
            // we opened the elytra and it is shut again: on the ground that is a takeoff that didn't get
            // anywhere, in the air it is the server refusing the glide, and the takeoff rocket with it
            if (!ctx.player().onGround()) {
                logDirect("The server closed the elytra " + this.takeoffOpenedTicksAgo + " ticks after takeoff.");
            }
            this.takeoffOpenedTicksAgo = -1;
        }

        if (this.state == State.FLYING || this.state == State.START_FLYING) {
            if (ctx.player().onGround() && Baritone.settings().elytraAutoJump.value) {
                this.state = State.LOCATE_JUMP;
                this.takeoffStallTicks = 0;
            } else {
                this.state = State.START_FLYING;
            }
        }

        if (this.state == State.LOCATE_JUMP) {
            if (shouldLandForSafety()) {
                logDirect("Not taking off, because elytra durability or fireworks are so low that I would immediately emergency land anyway.");
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (this.walkOffImpossible) {
                // there is nothing to walk off of around here, and we already spent a couple of seconds of
                // pathfinding finding that out once
                return standingTakeoff();
            }
            if (this.goal == null) {
                this.goal = takeoffGoal();
            }
            IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor != null && executor.getPath().getGoal() != this.goal) {
                // a segment left over from whatever we were doing before takeoff. it can never finish while we're
                // pausing pathing, and while it exists secretInternalSetGoalAndPath refuses to start the walk off
                // calculation, so neither side of this ever moves unless we drop it here
                baritone.getPathingBehavior().secretInternalSegmentCancel();
                executor = null;
            }
            if (executor != null) {
                this.takeoffStallTicks = 0;
                final IMovement fall = executor.getPath().movements().stream()
                        .filter(movement -> movement instanceof MovementFall)
                        .findFirst().orElse(null);

                if (fall != null && !fallIsFlyable(fall)) {
                    // The ledge drops somewhere there is no flying out of. GoalDescendTo only asks to get
                    // lower, so a shaft satisfies it as readily as a cliff: the glide would open into the wall
                    // with no pitch solution, the one state where tick() returns before lighting a firework.
                    // Better to spend the rung than the flight.
                    logDirect("The only way down from here drops into somewhere I could not fly out of.");
                    FlightLog.log("ledge: refused the drop from " + fall.getSrc() + " to " + fall.getDest()
                            + ", nothing to take off into down there");
                    return standingTakeoff();
                }

                if (fall != null) {
                    final BetterBlockPos from = new BetterBlockPos(
                            (fall.getSrc().x + fall.getDest().x) / 2,
                            (fall.getSrc().y + fall.getDest().y) / 2,
                            (fall.getSrc().z + fall.getDest().z) / 2
                    );
                    final ElytraBehavior owner = this.behavior;
                    FlightLog.log("ledge: stepping off " + fall.getSrc() + ", dropping to " + fall.getDest()
                            + ", flight path asked to start at " + from);
                    behavior.pathManager.pathToDestination(from).whenComplete((result, ex) -> {
                        if (this.behavior != owner) {
                            // torn down and rebuilt while we were computing (a cancel, a new destination, a
                            // seed change). Writing state now would drive the new behavior off the old path.
                            return;
                        }
                        if (ex == null) {
                            this.state = State.GET_TO_JUMP;
                            return;
                        }
                        // No flight path out of that ledge. That says something about the ledge, not about the
                        // flight: hand it back to the ladder instead of ending the whole thing here.
                        takeoffPathFailed();
                    });
                    this.state = State.PAUSE;
                } else {
                    return standingTakeoff();
                }
            } else if (baritone.getPathingBehavior().getInProgress().isPresent()) {
                this.takeoffStallTicks = 0;
            } else if (++this.takeoffStallTicks > TAKEOFF_STALL_TICKS) {
                // no path and nothing being calculated: SET_GOAL_AND_PAUSE has already decided it has nothing to
                // do, so the executor we're waiting on is never going to appear
                return standingTakeoff();
            }
            return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PAUSE, new WalkOffCalculationContext(baritone));
        }

        // yucky
        if (this.state == State.PAUSE) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.TAKEOFF_JUMP) {
            if (++this.takeoffStallTicks > STANDING_TAKEOFF_TIMEOUT_TICKS) {
                return abortTakeoff(ctx.player().onGround()
                        ? "Couldn't get off the ground. "
                        : "Got into the air but the elytra wouldn't open. ");
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            // Face the goal, tilted up, for the whole jump. The elytra keeps whatever direction it opened
            // facing, and the takeoff rocket is spent along it, so without this a takeoff leaves along
            // whatever the last walking movement happened to leave the head pointing at - into the wall we
            // just walked up to, as often as not. The solver takes the look back the moment it is flying.
            final Rotation aim = takeoffAim();
            if (aim != null) {
                baritone.getLookBehavior().updateTarget(aim, false);
            }
            if (ctx.player().onGround()) {
                // an ordinary jump. vanilla physics, and the movement packets it produces are what the server
                // checks its own idea of onGround against before it will accept the request below
                this.takeoffAirborneTicks = 0;
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            } else if (this.takeoffAirborneTicks++ > 0) {
                // the second press of the double tap. openElytra() is what actually opens it, but the client
                // reports every change of the key to the server (ServerboundPlayerInputPacket), so the inputs it
                // sees are the ones a player who opens an elytra sends: press, release, press
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                if (openElytra()) {
                    this.state = State.START_FLYING;
                }
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (this.state == State.GET_TO_JUMP) {
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            final boolean canStartFlying = ctx.player().getDeltaMovement().y < -0.377
                    && !isSafeToCancel
                    && executor != null
                    && executor.getPath().movements().get(executor.getPosition()) instanceof MovementFall;

            if (canStartFlying) {
                this.takeoffStallTicks = 0;
                this.state = State.START_FLYING;
            } else {
                if (executor != null || baritone.getPathingBehavior().getInProgress().isPresent()) {
                    this.takeoffStallTicks = 0;
                } else if (++this.takeoffStallTicks > TAKEOFF_STALL_TICKS) {
                    // the walk off path ran out without us ever falling off it, either because we reached the goal
                    // by stepping down or because the segment got cancelled. PathingBehavior won't re-path once
                    // it considers the goal met, so go pick a new ledge instead of standing here
                    if (++this.walkOffAttempts >= MAX_WALK_OFF_ATTEMPTS) {
                        return standingTakeoff();
                    }
                    this.takeoffStallTicks = 0;
                    this.goal = null;
                    this.state = State.LOCATE_JUMP;
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        if (this.state == State.PILLAR_UP) {
            if (ctx.player().onGround() && ctx.playerFeet().y >= this.pillarTargetY) {
                // Climbed high enough: try the launch again from up here. this.pillared stays set, so if it
                // still doesn't work the ladder moves on to walking somewhere else instead of climbing twice.
                baritone.getPathingBehavior().secretInternalSegmentCancel();
                this.goal = null;
                this.takeoffStage = Stage.LAUNCH;
                this.standingTakeoffs = 0;
                // Through LOCATE_JUMP rather than straight into standingTakeoff, the same way the walk does:
                // we are on top of the rim now, and a ledge to step off is still the cheapest takeoff there is
                // and the one most likely to work. standingTakeoff sets walkOffImpossible itself, so calling it
                // here would skip that search for good.
                this.walkOffImpossible = false;
                this.walkOffAttempts = 0;
                this.takeoffStallTicks = 0;
                this.state = State.LOCATE_JUMP;
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }

            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor != null || baritone.getPathingBehavior().getInProgress().isPresent()) {
                this.takeoffStallTicks = 0;
            } else if (++this.takeoffStallTicks > TAKEOFF_STALL_TICKS) {
                // Stalled climbing (ran out of blocks mid-pillar, obstruction) -- the height we wanted isn't
                // reachable from here, so stop asking for it and go look somewhere else.
                this.goal = null;
                this.takeoffStage = Stage.RELOCATE;
                return standingTakeoff();
            }
            if (takeoffNoProgress()) {
                logDirect("The climb is going nowhere, looking for somewhere else to take off from.");
                this.goal = null;
                this.takeoffStage = Stage.RELOCATE;
                return standingTakeoff();
            }
            return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PATH, new NoBreakCalculationContext(baritone));
        }

        if (this.state == State.WALK_TO_LAUNCH) {
            final BetterBlockPos feet = ctx.playerFeet();
            if (this.goal != null && ctx.player().onGround() && this.goal.isInGoal(feet.x, feet.y, feet.z)) {
                // Arrived: a new spot, so the ladder starts over here rather than being left to
                // rememberTakeoffSpot, which would read this as within radius of the last spot and never
                // launch from where the walk actually got to.
                if (this.walkingOnwards) {
                    // a leg that got there: see MAX_ONWARD_LEGS, only legs that get nowhere count
                    this.onwardLegs = 0;
                }
                FlightLog.log(String.format(Locale.ROOT,
                        "spot: %s, reached by %s, is a new spot; the ladder starts at the top, with %d relocations, %d climbs and %d legs already spent",
                        feet, this.walkingOnwards ? "a leg towards the goal" : "a walk to a measured spot",
                        this.relocations, this.climbs, this.onwardLegs));
                ladderStartsAt(feet, ctx.player().tickCount);
                baritone.getPathingBehavior().secretInternalSegmentCancel();
                this.goal = null;
                this.walkOffImpossible = false;
                this.walkOffAttempts = 0;
                this.takeoffStallTicks = 0;
                this.state = State.LOCATE_JUMP;
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }

            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor != null || baritone.getPathingBehavior().getInProgress().isPresent()) {
                this.takeoffStallTicks = 0;
            } else if (++this.takeoffStallTicks > TAKEOFF_STALL_TICKS) {
                this.goal = null;
                walkGotNowhere();
                return standingTakeoff();
            }
            if (takeoffNoProgress()) {
                logDirect("Not getting any closer to a spot to take off from, giving up on the walk.");
                this.goal = null;
                walkGotNowhere();
                return standingTakeoff();
            }
            return new PathingCommand(this.goal, PathingCommandType.SET_GOAL_AND_PATH);
        }

        if (this.state == State.START_FLYING) {
            if (!isSafeToCancel) {
                // owned
                baritone.getPathingBehavior().secretInternalSegmentCancel();
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            if (ctx.player().getDeltaMovement().y < -0.377) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * Gets into the air from wherever the ground left us, when no ledge is there to walk off. {@link #liftHeight}
     * measures what the terrain allows, and the ladder below tries answers in order of cost - a rocket, a
     * pillar, a walk - so a spot that defeats one is answered by the next. The stage survives the process being
     * torn down and handed the goal again, so a restart resumes where the ladder left off.
     */
    private PathingCommand standingTakeoff() {
        this.walkOffImpossible = true;
        this.goal = null;
        this.takeoffStallTicks = 0;
        this.takeoffAirborneTicks = 0;
        baritone.getPathingBehavior().secretInternalSegmentCancel();

        if (!Baritone.settings().elytraStandingTakeoff.value) {
            return abortTakeoff("There is no spot to jump off from, and elytraStandingTakeoff is off. ");
        }

        final BetterBlockPos feet = ctx.playerFeet();
        rememberTakeoffSpot(feet);

        // Fireworks first: every rung below ends in a jump that needs one, so a pillar or a walk is wasted
        // effort if there is nothing to light at the end of it.
        if (!this.behavior.selectFirework()) {
            return abortTakeoff("There is no spot to jump off from, and no fireworks in my hotbar to take off from here with. ");
        }

        // How far above our feet a takeoff would have somewhere to go. 0 means right here; a positive number
        // means we are in a crevice or a hole whose rim is that far up; -1 means nothing within reach of a
        // climb, which is a pocket or a cave and only a walk can answer.
        int lift = liftHeight(feet);
        String liftWhy = lift > 0 ? "no room to take off here" : lift < 0 ? "no room within reach above" : "room to take off here";
        if (lift == 0 && takeoffExit(feet) == null) {
            // launchRise is happy - there is room to jump and a clear line towards the goal - but there is no
            // node cube for the flight path to start in, and the cube is what the solver aims the first boost
            // at. The two measure different things and they disagree here by as much as 24 blocks. Prefer
            // climbing to where they agree.
            final int climb = liftToNodeCube(feet);
            if (climb > 0) {
                lift = climb;
                liftWhy = "room here, but the path's first 4x4x4 cube is " + climb + " blocks up";
            } else {
                // Nowhere above satisfies both: launch from here, with the path starting at the top of the air
                // over our head. A takeoff never breaks a block for room, so this can be the only option; one
                // that fails is still bounded by the normal three-launch cap before the ladder relocates.
                liftWhy = "room here, no 4x4x4 cube within reach: launching with the path starting overhead";
            }
        }

        noteLadder(feet, lift, liftWhy);
        if (this.takeoffStage == Stage.LAUNCH) {
            if (lift == 0 && this.standingTakeoffs < MAX_STANDING_TAKEOFFS) {
                return launchFromHere(feet);
            }
            this.takeoffStage = Stage.CLIMB;
        }
        if (this.takeoffStage == Stage.CLIMB) {
            final PathingCommand climb = climbToLaunch(feet, lift);
            if (climb != null) {
                return climb;
            }
            this.takeoffStage = Stage.RELOCATE;
        }
        if (this.takeoffStage == Stage.RELOCATE) {
            final PathingCommand walk = walkToLaunch(feet);
            if (walk != null) {
                return walk;
            }
            this.takeoffStage = Stage.EXHAUSTED;
        }
        // Nothing left to try. A rocket into a bad spot still beats standing still, so spend the ones the cap
        // allows before saying so.
        if (lift == 0 && this.standingTakeoffs < MAX_STANDING_TAKEOFFS) {
            return launchFromHere(feet);
        }
        return abortTakeoff("Tried taking off from here, climbing out of here, and walking somewhere better, and none of it worked. ");
    }

    /**
     * Jump on the spot, open the elytra on the way up, and light a firework the moment it opens, since a glide
     * that starts with no speed a block and a half off the floor only ends one way.
     */
    private PathingCommand launchFromHere(BetterBlockPos feet) {
        if (this.standingTakeoffs++ == 0) {
            logDirect("No spot to jump off from, taking off from here instead.");
        }
        // Where the elytra path starts matters more than anything else here: the solver aims at its first node
        // and the takeoff rocket is spent along that aim, so a path that starts inside the hole we are trying
        // to leave points the boost straight back into its wall.
        final BetterBlockPos start = launchPathStart(feet);
        this.recentLaunches.addLast(new int[]{feet.x, feet.z, ctx.player().tickCount});
        if (this.recentLaunches.size() > 16) {
            this.recentLaunches.removeFirst();
        }
        final BetterBlockPos exit = takeoffExit(feet);
        final Rotation aim = takeoffAim();
        FlightLog.log(String.format(Locale.ROOT,
                "launch: attempt %d from %d %d %d, lift %d, rise %d, path asked to start at %d %d %d (%s), aim %s",
                this.standingTakeoffs, feet.x, feet.y, feet.z, liftHeight(feet), launchRise(feet),
                start.x, start.y, start.z,
                exit != null ? "node cube, " + (FlightLog.enabled() ? describeExitCube(exit) : "")
                        : "column fallback, " + ctx.world().getBlockState(start).getBlock() + " there",
                aim == null ? "none" : String.format(Locale.ROOT, "yaw %.0f pitch %.0f", aim.getYaw(), aim.getPitch())));
        if (Baritone.settings().elytraTakeoffJournal.value) {
            final BetterBlockPos cube = takeoffExit(feet);
            this.takeoffLogFrom = feet;
            this.takeoffLogStart = start;
            this.takeoffLogStartWasCube = cube != null;
            this.takeoffLogLift = liftHeight(feet);
            this.takeoffLogRise = launchRise(feet);
            this.takeoffLogNode = "";
            logDirect(String.format(Locale.ROOT,
                    "takeoff: from %d %d %d, lift=%d rise=%d, path starts at %d %d %d (%s)",
                    feet.x, feet.y, feet.z, this.takeoffLogLift, this.takeoffLogRise,
                    start.x, start.y, start.z, this.takeoffLogStartWasCube ? "node cube" : "column fallback"));
        }
        // the elytra path has to exist before we leave the ground: the behavior does nothing without one, and the
        // handful of ticks between opening the elytra and hitting the ground is no time to compute one
        this.state = State.PAUSE;
        final ElytraBehavior owner = this.behavior;
        this.behavior.pathManager.pathToDestination(start).whenComplete((result, ex) -> {
            if (this.behavior != owner) {
                return;
            }
            if (ex == null) {
                this.state = State.TAKEOFF_JUMP;
                return;
            }
            takeoffPathFailed();
        });
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /**
     * What the client and the pathfinder each make of the 4x4x4 cube a launch asks its flight path to start in:
     * how many of its blocks are cave air, and how many the pathfinder's cache holds as solid. The launch only
     * picks a cube that is all air to the client, so a solid count is the two disagreeing, which is the one
     * thing that makes the native search start the path somewhere else.
     */
    private String describeExitCube(BetterBlockPos centre) {
        final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int caveAir = 0;
        int nativeSolid = 0;
        for (int x = centre.x - 2; x < centre.x + 2; x++) {
            for (int y = centre.y - 2; y < centre.y + 2; y++) {
                for (int z = centre.z - 2; z < centre.z + 2; z++) {
                    if (ctx.world().getBlockState(mut.set(x, y, z)).getBlock() == Blocks.CAVE_AIR) {
                        caveAir++;
                    }
                    if (this.behavior.nativeSolid(x, y, z)) {
                        nativeSolid++;
                    }
                }
            }
        }
        return caveAir + " of its 64 blocks cave air, " + nativeSolid + " solid to the pathfinder";
    }

    /** Squared distance in the horizontal plane. */
    private static int horizontalDistSq(BetterBlockPos a, BetterBlockPos b) {
        final int dx = a.x - b.x;
        final int dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    /** Whether the column is within {@link #TAKEOFF_SAME_SPOT_RADIUS} of a launch made in the last minute. */
    private boolean nearRecentLaunch(int x, int z) {
        final int now = ctx.player().tickCount;
        for (int[] launch : this.recentLaunches) {
            final int age = now - launch[2];
            final int dx = x - launch[0];
            final int dz = z - launch[1];
            // a negative age is a tick counter that restarted (a respawn, a reconnect): too old to trust
            if (age >= 0 && age < RECENT_LAUNCH_TICKS
                    && dx * dx + dz * dz <= TAKEOFF_SAME_SPOT_RADIUS * TAKEOFF_SAME_SPOT_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Climbs to the stance {@link #liftHeight} found above us by pillaring through open air: it goes out on
     * {@link NoBreakCalculationContext}, so the pathfinder may place its way up but never break a block, and a
     * climb that could only be made by breaking fails to compute and moves the ladder on. {@code null} when there
     * is nothing above worth climbing to, or no way to climb, so the caller can move on to the next rung.
     */
    private PathingCommand climbToLaunch(BetterBlockPos feet, int lift) {
        if (lift <= 0 || this.pillared || !Baritone.settings().elytraTakeoffPillar.value) {
            return null;
        }
        if (this.climbs >= MAX_CLIMBS) {
            // A climb moves us far enough that the ladder starts over at the top, so without a cap of its own
            // a spot that measures as climbable again from every height it reaches would pillar to the roof.
            return null;
        }
        if (lift > Baritone.settings().elytraTakeoffPillarMaxHeight.value) {
            // Deeper than a crevice. Climbing out of a shaft this tall costs a jump a block and gets us
            // nowhere the walk below couldn't reach more cheaply.
            return null;
        }
        final boolean canPlace = Baritone.settings().allowPlace.value
                && baritone.getInventoryBehavior().hasGenericThrowaway();
        if (!canPlace) {
            // a climb may not break anything, so without blocks to place there is no climb at all
            logDebug("walled in, but there's nothing to pillar with");
            return null;
        }
        final int spare = baritone.getInventoryBehavior().spendableThrowawayCount();
        if (lift > spare) {
            // the obsidian under obsidianReserve is kept for the regear's box, which has to be built whole: a pillar
            // only gets the other blocks and the obsidian over it, and one it could not finish is not started
            FlightLog.log(String.format(Locale.ROOT, "climb: refused %d blocks up from %s, only %d blocks to spare over the %d obsidian kept for the regear box",
                    lift, feet, spare, Baritone.settings().obsidianReserve.value));
            return null;
        }
        this.pillared = true;
        this.climbs++;
        this.pillarTargetY = feet.y + lift;
        this.standingTakeoffs = 0;
        this.goal = new GoalBlock(feet.x, this.pillarTargetY, feet.z);
        this.state = State.PILLAR_UP;
        this.takeoffStallTicks = 0;
        takeoffProgressReset();
        logDirect("Nothing to take off into from here, climbing " + lift + " blocks to where there is.");
        FlightLog.log(String.format(Locale.ROOT, "climb: %d blocks up from %s to y=%d, climb %d of %d, breaking nothing",
                lift, feet, this.pillarTargetY, this.climbs, MAX_CLIMBS));
        return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PATH, new NoBreakCalculationContext(baritone));
    }

    /**
     * Walks to the nearest spots around us that a takeoff would actually work from, on the ordinary calculation
     * context so the pathfinder may dig and bridge its way there. This is the rung that leaves a sealed pocket
     * or the top of a fungus canopy, neither of which any amount of jumping or climbing on the spot can fix.
     * {@code null} when there is nowhere better within range, or we have already moved twice.
     */
    private PathingCommand walkToLaunch(BetterBlockPos feet) {
        // Two walks to spots this search measured as launchable, and no more: a spot walked to twice without a
        // takeoff working from it just gets re-offered by measuring again. Past that, the only relocation left
        // is one that goes somewhere new - on towards the goal, digging if it has to - which starts this count
        // over on the new, unmeasured ground.
        final boolean spotsSpent = this.relocations >= MAX_RELOCATIONS;
        List<Goal> spots = Collections.emptyList();
        this.lastSpotsSkipped = 0;
        if (!spotsSpent) {
            // Near first, and only then wide: the wide sweep costs a few tens of thousands of block reads, which
            // is worth paying once we are otherwise out of ideas and not before.
            spots = findLaunchSpots(feet, TAKEOFF_RELOCATE_RADIUS, TAKEOFF_RELOCATE_COLUMN_BUDGET);
            if (spots.isEmpty()) {
                spots = findLaunchSpots(feet, TAKEOFF_RELOCATE_RADIUS_WIDE, TAKEOFF_RELOCATE_COLUMN_BUDGET_WIDE);
            }
        }
        final String what;
        if (!spots.isEmpty()) {
            this.goal = new GoalComposite(spots.toArray(new Goal[0]));
            what = "walking to one of " + spots.size() + " spots that will work";
            this.relocations++;
            this.walkingOnwards = false;
        } else {
            // Nothing measured as launchable anywhere in range, which in a cave system is perfectly possible:
            // set off towards the destination on foot and ask again from there. The goal is a compass direction
            // rather than a place, so the pathfinder digs and bridges however it must - crude, but it keeps the
            // ladder from getting stuck where a tidier rung would give up.
            if (this.onwardLegs >= MAX_ONWARD_LEGS) {
                // see MAX_ONWARD_LEGS: that many legs in a row got nowhere, and the next one would go the same way
                return null;
            }
            final Goal onwards = walkOnwardsGoal(feet);
            if (onwards == null) {
                return null;
            }
            this.goal = onwards;
            this.onwardLegs++;
            this.walkingOnwards = true;
            // it ends on ground nobody has measured yet, which gets its own two walks to measured spots
            this.relocations = 0;
            what = (spotsSpent
                    ? "the spots around here have been tried twice already, walking on towards the goal instead"
                    : "no spot around here works either, walking on towards the goal to look further")
                    + " (leg " + this.onwardLegs + " of " + MAX_ONWARD_LEGS + ")";
        }
        this.state = State.WALK_TO_LAUNCH;
        this.takeoffStallTicks = 0;
        takeoffProgressReset();
        logDirect("Nowhere to take off from here, " + what + ".");
        FlightLog.log(String.format(Locale.ROOT, "relocate: from %s (measured walks %d/%d, legs %d/%d), %s%s: %s",
                feet, this.relocations, MAX_RELOCATIONS, this.onwardLegs, MAX_ONWARD_LEGS, what,
                this.lastSpotsSkipped > 0
                        ? " (" + this.lastSpotsSkipped + " columns passed over next to a launch made in the last minute)"
                        : "",
                this.goal));
        return new PathingCommand(this.goal, PathingCommandType.SET_GOAL_AND_PATH);
    }

    /**
     * The walk in progress got nowhere: it stalled, or no path to it could be found. The measured spots are as
     * good as spent, so the next rung walks on towards the goal instead. A leg that got nowhere keeps its place
     * in the {@link #MAX_ONWARD_LEGS} count, and past that the ladder ends.
     */
    private void walkGotNowhere() {
        this.relocations = MAX_RELOCATIONS;
        this.takeoffStage = Stage.RELOCATE;
    }

    /** A point {@link #TAKEOFF_WALK_ONWARDS} blocks towards the destination, or {@code null} without one. */
    private Goal walkOnwardsGoal(BetterBlockPos feet) {
        final BetterBlockPos dest = this.behavior != null ? this.behavior.destination : null;
        if (dest == null) {
            return null;
        }
        final double dx = dest.x - feet.x;
        final double dz = dest.z - feet.z;
        final double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) {
            return null;
        }
        final double reach = Math.min(len, TAKEOFF_WALK_ONWARDS);
        return new GoalXZ(
                feet.x + (int) Math.round(dx / len * reach),
                feet.z + (int) Math.round(dz / len * reach)
        );
    }

    /**
     * A takeoff path computation failed. Called from the pathfinder's own thread, so it only sets fields, the
     * same way the success side of those handlers already does. Advances the ladder past whatever rung asked
     * for that path, rather than ending the flight outright.
     */
    private void takeoffPathFailed() {
        FlightLog.log("ladder: no usable flight path for the " + this.takeoffStage + " rung, moving on to the next one");
        if (this.takeoffStage == Stage.LAUNCH) {
            this.takeoffStage = Stage.CLIMB;
        } else if (this.takeoffStage == Stage.CLIMB) {
            this.takeoffStage = Stage.RELOCATE;
        }
        this.goal = null;
        this.walkOffImpossible = true;
        this.takeoffStallTicks = 0;
        this.state = State.LOCATE_JUMP;
    }

    /**
     * Starts the ladder over when {@code feet} is somewhere other than where it was last run, or when its memory
     * of the spot has gone stale. Horizontal distance and a drop, not a rise: a climb should not itself read as a
     * new spot. The clock is stamped only when the spot actually changes, not on every call, since this runs
     * every tick from the state that loops.
     */
    private void rememberTakeoffSpot(BetterBlockPos feet) {
        final int now = ctx.player().tickCount;
        final int dx = this.takeoffSpot == null ? 0 : this.takeoffSpot.x - feet.x;
        final int dz = this.takeoffSpot == null ? 0 : this.takeoffSpot.z - feet.z;
        final int drop = this.takeoffSpot == null ? 0 : this.takeoffSpot.y - feet.y;
        final boolean freshMemory = this.takeoffSpot != null && now - this.takeoffSpotTick < TAKEOFF_MEMORY_TICKS;
        final boolean sameSpot = freshMemory
                && dx * dx + dz * dz <= TAKEOFF_SAME_SPOT_RADIUS * TAKEOFF_SAME_SPOT_RADIUS
                && drop <= TAKEOFF_SAME_SPOT_RADIUS;
        // A launch that never got anywhere lands wherever the fall happened to end, which can be past sameSpot's
        // 8-block/8-drop window - it is not a new spot, just the same failed launch landing badly. The drop cap
        // is tighter than the horizontal one so a real fall to new, different ground still starts the ladder over.
        final boolean cameStraightBackDown = !sameSpot && this.launchedFromSpot && freshMemory
                && dx * dx + dz * dz <= TAKEOFF_SUCCESS_DISTANCE * TAKEOFF_SUCCESS_DISTANCE
                && drop <= 2 * TAKEOFF_SAME_SPOT_RADIUS;
        this.launchedFromSpot = false;
        if (sameSpot) {
            return;
        }
        if (cameStraightBackDown) {
            FlightLog.log(String.format(Locale.ROOT,
                    "spot: the launch from %s came straight back down (%d horizontally, %d below), still the same spot, launches %d/%d, spot now follows the feet",
                    this.takeoffSpot, (int) Math.round(Math.sqrt(dx * dx + dz * dz)), drop,
                    this.standingTakeoffs, MAX_STANDING_TAKEOFFS));
            // This method runs every tick on the ground, so leaving takeoffSpot where it was would put next
            // tick's distance back outside sameSpot's window and undo this. Re-anchor on the feet instead -
            // ladderStartsAt minus the two lines that would actually reset the ladder.
            this.takeoffSpot = feet;
            this.takeoffSpotTick = now;
            this.takeoffStage = Stage.LAUNCH;
            return;
        }
        // Relocations, climbs and legs are not reset here even when the memory has expired: only a working
        // takeoff resets them. Expiry alone resetting them would hand a full ladder back to every restart after
        // a regear or a pause.
        final boolean expired = this.takeoffSpot != null && now - this.takeoffSpotTick >= TAKEOFF_MEMORY_TICKS;
        FlightLog.log(this.takeoffSpot == null
                ? "spot: taking off from " + feet + ", the ladder starts at the top"
                : String.format(Locale.ROOT,
                        "spot: %s is a new spot, %.0f blocks from the last one%s; the ladder starts at the top, with %d relocations, %d climbs and %d legs already spent",
                        feet, Math.sqrt(dx * dx + dz * dz),
                        drop > TAKEOFF_SAME_SPOT_RADIUS ? " and " + drop + " blocks below it"
                                : expired ? ", whose memory had expired" : "",
                        this.relocations, this.climbs, this.onwardLegs));
        ladderStartsAt(feet, now);
    }

    /** The ladder starts over from the top at {@code feet}: its rungs, and the launches and climb of one spot. */
    private void ladderStartsAt(BetterBlockPos feet, int now) {
        this.takeoffStage = Stage.LAUNCH;
        this.standingTakeoffs = 0;
        this.pillared = false;
        this.takeoffSpot = feet;
        this.takeoffSpotTick = now;
    }

    /**
     * One line per flight tick for the first few ticks after the elytra opens, while the journal is armed. The
     * line that matters is the path's first node, what the rocket is aimed at: staying the launch's own cube
     * means the path survived, changing to the player's feet means it was silently recomputed instead.
     */
    private void writeTakeoffJournal() {
        if (this.takeoffLogTicks <= 0) {
            return;
        }
        this.takeoffLogTicks--;
        final java.util.List<BetterBlockPos> path = this.behavior.pathManager.getPath();
        final String node = path.isEmpty() ? "none" : path.get(0).x + " " + path.get(0).y + " " + path.get(0).z;
        final boolean changed = !node.equals(this.takeoffLogNode);
        this.takeoffLogNode = node;
        final Vec3 pos = ctx.player().position();
        final Vec3 motion = ctx.player().getDeltaMovement();
        logDirect(String.format(Locale.ROOT,
                "takeoff+%d: at %.1f %.1f %.1f, speed %.2f, node0 %s%s (%d nodes)%s%s",
                Baritone.settings().elytraTakeoffJournalTicks.value - this.takeoffLogTicks,
                pos.x, pos.y, pos.z, motion.length(), node, changed ? " CHANGED" : "", path.size(),
                ctx.player().horizontalCollision ? " hbonk" : "",
                ctx.player().verticalCollision ? " vbonk" : ""));
    }

    /**
     * The flight record's opening line for a flight, and the start of its account.
     */
    private void flightStarted() {
        this.flightFrom = ctx.player().position();
        this.flightDestination = this.behavior.destination;
        this.flightLastPos = this.flightFrom;
        this.flightLastTick = ctx.player().tickCount;
        this.flightRocketsAtStart = this.rocketsLit;
        this.flightCorrectionsAtStart = this.serverCorrections;
        this.flightDetourRetriesAtStart = this.detourRetries.get();
        this.flightDetourSwapsAtStart = this.detourSwaps.get();
        this.flightWallHits = 0;
        this.flightCeilingHits = 0;
        this.flightFarthest = 0;
        this.flightTravelled = 0;
        this.flightTopSpeed = 0;
        this.flightSlowTicks = 0;
        this.flightPinNoted = false;
        this.flightPinDecisionNoted = false;
        this.fallFromY = Double.NaN;
        this.behavior.takeNoPitchTicks();
        this.launchedFromSpot = this.takeoffSpot != null && this.takeoffStage == Stage.LAUNCH;
        FlightLog.log(String.format(Locale.ROOT, "flight: started at %.1f %.1f %.1f, state %s, %s%s",
                this.flightFrom.x, this.flightFrom.y, this.flightFrom.z, this.state,
                this.takeoffSpot == null
                        ? "no takeoff spot being worked"
                        : "taking off from " + this.takeoffSpot + " at the " + this.takeoffStage + " rung",
                this.lastFlightEndMs < 0 ? ""
                        : String.format(Locale.ROOT, ", %.1f s after the last flight ended",
                        (System.currentTimeMillis() - this.lastFlightEndMs) / 1000.0)));
    }

    /** Called by the behavior for every rocket it lights, for the flight record. */
    public void countRocket() {
        this.rocketsLit++;
    }

    /** Called by the behavior, on the game thread, for every position the server forces on us. */
    public void countSetback() {
        this.serverCorrections++;
    }

    /**
     * Called by the behavior, from the pathfinder's thread, for every wide path it searched again with fine nodes
     * because it wandered, {@code swapped} when the fine path was the one kept.
     */
    public void countDetourRetry(boolean swapped) {
        (swapped ? this.detourSwaps : this.detourRetries).incrementAndGet();
    }

    /** Keeps the account of the flight in progress. */
    private void flightTick() {
        if (this.flightFrom == null) {
            return;
        }
        if (ctx.player().horizontalCollision) {
            this.flightWallHits++;
        }
        if (ctx.player().verticalCollision) {
            this.flightCeilingHits++;
        }
        this.flightLastPos = ctx.player().position();
        this.flightLastTick = ctx.player().tickCount;
        this.flightFarthest = Math.max(this.flightFarthest, this.flightLastPos.distanceTo(this.flightFrom));
        // from the velocity rather than from the change of position, which would count a setback as flown
        final double speed = ctx.player().getDeltaMovement().length();
        this.flightTravelled += speed;
        this.flightTopSpeed = Math.max(this.flightTopSpeed, speed);
        if (speed < SLOW_FLIGHT_SPEED) {
            this.flightSlowTicks++;
        }
    }

    /**
     * The flight record's closing line for a flight: how long it lasted, where and how it ended, how far it got,
     * what it spent and what it hit. This is the line that tells a landing from a glide that just gave out.
     */
    private void flightEnded() {
        if (this.flightFrom == null) {
            return;
        }
        final int now = ctx.player().tickCount;
        // Written on the first tick we are back in control and not flying. When the process was not in control
        // as the flight ended - stopped, or handed a new goal later on the ground - that tick came later and
        // somewhere else, so the line says so and gives the last place it was seen flying instead.
        final boolean late = now < this.flightLastTick || now - this.flightLastTick > 2;
        // by the wall clock, back-dated to the tick the flight was last seen when the record is written late
        this.lastFlightEndMs = System.currentTimeMillis() - (late ? 50L * Math.max(0, now - this.flightLastTick) : 0L);
        final Vec3 pos = late ? this.flightLastPos : ctx.player().position();
        final String where = late ? "while the process was not in control, last seen flying"
                : ctx.player().isInLava() ? "in lava"
                : ctx.player().onGround() ? "on the ground" : "in the air with the elytra shut";
        final int noPitch = this.behavior.takeNoPitchTicks();
        // The heading the flight held, as a Minecraft yaw (the one F3 shows), against the yaw of the destination
        // from where the flight started: how closely the path kept to the heading. Left out of a flight too short
        // to have one.
        final double courseX = pos.x - this.flightFrom.x;
        final double courseZ = pos.z - this.flightFrom.z;
        final int leg = Baritone.settings().elytraPathLegLength.value;
        final String course = courseX * courseX + courseZ * courseZ < 100 * 100 ? ""
                : String.format(Locale.ROOT, ", course yaw %.1f with the destination at yaw %.1f (%s)",
                Math.toDegrees(Math.atan2(-courseX, courseZ)),
                Math.toDegrees(Math.atan2(-(this.flightDestination.x + 0.5 - this.flightFrom.x),
                        this.flightDestination.z + 0.5 - this.flightFrom.z)),
                leg > 0 ? "legs of " + leg + " blocks" : "aimed at the destination");
        FlightLog.log(String.format(Locale.ROOT,
                "flight: ended after %d ticks %s at %.1f %.1f %.1f, %.0f blocks from where it started (%.0f at the farthest)%s, %.0f blocks travelled at %.2f b/t on average (%.1f blocks/s, top %.2f b/t, %d ticks under %.1f b/t)%s, rockets %d, server corrections %d, solver boost x%.2f +%d ticks, relight under %.2f b/t, wall hits %d, ceiling or floor hits %d, detour retries %d (fine path kept %d)%s%s, state %s",
                this.flyingTicks, where, pos.x, pos.y, pos.z, pos.distanceTo(this.flightFrom), this.flightFarthest,
                this.takeoffSpot == null ? ""
                        : String.format(Locale.ROOT, ", %.0f horizontally from the takeoff spot, which stays remembered",
                        Math.sqrt(horizontalDistSq(this.takeoffSpot, new BetterBlockPos(pos.x, pos.y, pos.z)))),
                this.flightTravelled, this.flightTravelled / this.flyingTicks, 20 * this.flightTravelled / this.flyingTicks,
                this.flightTopSpeed, this.flightSlowTicks, SLOW_FLIGHT_SPEED,
                course,
                Math.max(0, this.rocketsLit - this.flightRocketsAtStart),
                Math.max(0, this.serverCorrections - this.flightCorrectionsAtStart),
                // the boost model the solver planned with, which a boost module may or may not have set: the
                // flight record says which, so flights can be compared by the module that flew them
                Baritone.settings().elytraFireworkBoostMultiplier.value,
                Baritone.settings().elytraFireworkExtraBoostTicks.value,
                Baritone.settings().elytraFireworkSpeed.value,
                this.flightWallHits, this.flightCeilingHits,
                Math.max(0, this.detourRetries.get() - this.flightDetourRetriesAtStart),
                Math.max(0, this.detourSwaps.get() - this.flightDetourSwapsAtStart),
                this.pinnedTicks > TAKEOFF_PINNED_TICKS ? ", pinned at the end" : "",
                noPitch > 0 ? ", the last " + noPitch + " ticks without a pitch solution" : "",
                this.state));
        this.flightFrom = null;
    }

    /**
     * The flight record's account of falls that are not flights: a line when one picks up speed and one when it
     * ends, with how far it went and whether it ended on the ground or in lava.
     */
    private void trackFall() {
        final Vec3 pos = ctx.player().position();
        if (Double.isNaN(this.fallFromY)) {
            if (!ctx.player().onGround() && !ctx.player().isInLava() && ctx.player().getDeltaMovement().y < -0.5) {
                this.fallFromY = pos.y;
                this.fallState = String.valueOf(this.state);
                FlightLog.log(String.format(Locale.ROOT, "fall: falling from %.1f %.1f %.1f, state %s",
                        pos.x, pos.y, pos.z, this.fallState));
            }
            return;
        }
        if (ctx.player().onGround() || ctx.player().isInLava()) {
            FlightLog.log(String.format(Locale.ROOT, "fall: ended %s at %.1f %.1f %.1f, %.1f blocks below where it picked up speed (state %s then)",
                    ctx.player().isInLava() ? "in lava" : "on the ground", pos.x, pos.y, pos.z,
                    this.fallFromY - pos.y, this.fallState));
            this.fallFromY = Double.NaN;
        }
    }

    /**
     * The flight record's side of the pinned detector: a line when a pin is a third of the way there, and one
     * when it is reached, saying whether it sets us down or, with no safe floor in reach, leaves the glide
     * scraping.
     */
    private void notePinned(boolean stuck) {
        if (!stuck) {
            return;
        }
        if (this.pinnedTicks == TAKEOFF_PINNED_TICKS / 3 && !this.flightPinNoted) {
            this.flightPinNoted = true;
            final Vec3 pos = ctx.player().position();
            FlightLog.log(String.format(Locale.ROOT, "pinned: %d ticks against a wall without getting past it, at %.1f %.1f %.1f, speed %.2f",
                    this.pinnedTicks, pos.x, pos.y, pos.z, ctx.player().getDeltaMovement().length()));
        }
        if (this.pinnedTicks == TAKEOFF_PINNED_TICKS + 1 && !this.flightPinDecisionNoted) {
            this.flightPinDecisionNoted = true;
            FlightLog.log(groundBelowIsSafe()
                    ? "pinned: " + this.pinnedTicks + " ticks, setting down on the floor below to try again from the ground"
                    : "pinned: " + this.pinnedTicks + " ticks, but not setting down - no floor within "
                    + TAKEOFF_PINNED_SAFE_DROP + " blocks below, or lava on the way - so the glide keeps scraping");
        }
    }

    /** One line per distinct decision of the takeoff ladder, with everything it was decided on. */
    private void noteLadder(BetterBlockPos feet, int lift, String liftWhy) {
        final String line = String.format(Locale.ROOT,
                "ladder: at %d %d %d, %s rung, lift %d (%s), launches %d/%d, climbs %d/%d, relocations %d/%d, legs %d/%d, climbed here %b",
                feet.x, feet.y, feet.z, this.takeoffStage, lift, liftWhy, this.standingTakeoffs, MAX_STANDING_TAKEOFFS,
                this.climbs, MAX_CLIMBS, this.relocations, MAX_RELOCATIONS, this.onwardLegs, MAX_ONWARD_LEGS, this.pillared);
        if (!line.equals(this.lastLadderLine)) {
            this.lastLadderLine = line;
            FlightLog.log(line);
        }
    }

    /** Drops the ladder's memory of a spot entirely - we are flying, so whatever it was working on is moot. */
    private void forgetTakeoffSpot() {
        if (this.takeoffSpot != null) {
            FlightLog.log(String.format(Locale.ROOT,
                    "spot: the takeoff from %s worked, %d blocks clear after %d ticks in the air; the ladder is forgotten (it was at the %s rung, launches %d, climbs %d, relocations %d)",
                    this.takeoffSpot, TAKEOFF_SUCCESS_DISTANCE, this.flyingTicks, this.takeoffStage,
                    this.standingTakeoffs, this.climbs, this.relocations));
        }
        this.takeoffSpot = null;
        this.takeoffStage = Stage.LAUNCH;
        this.launchedFromSpot = false;
        // the journal belongs to the takeoff, not to the flight; it stops itself after its own ticks
        this.relocations = 0;
        this.climbs = 0;
        this.onwardLegs = 0;
        // ground a launch has just worked from is not ground to keep away from
        this.recentLaunches.clear();
        this.standingTakeoffs = 0;
        this.pillared = false;
        // "No ledge to walk off" was a fact about the spot we took off from, not about wherever we land
        // next. Left set, it made every landing after a standing takeoff skip the ledge search outright and
        // go straight down the ladder - to a walk, or to the crude last rung - when a ledge was there to be
        // found in a few dozen milliseconds the moment something did reset it.
        this.walkOffImpossible = false;
    }

    /**
     * Whether the rung in progress has stopped getting closer to its goal for long enough to give up on it.
     * <p>
     * Distance to the goal rather than any sign of activity: a path being re-planned over and over is
     * activity, and it is exactly the shape of failure this exists to catch. {@link Goal#heuristic} is the
     * one measure every goal here can be asked for, whether it is a block above us or a composite of eight
     * landing spots.
     */
    private boolean takeoffNoProgress() {
        if (this.goal == null) {
            return false;
        }
        final BetterBlockPos feet = ctx.playerFeet();
        final double now = this.goal.heuristic(feet.x, feet.y, feet.z);
        if (now < this.takeoffProgressBest - 0.01) {
            this.takeoffProgressBest = now;
            this.takeoffProgressTick = ctx.player().tickCount;
            return false;
        }
        return ctx.player().tickCount - this.takeoffProgressTick > TAKEOFF_NO_PROGRESS_TICKS;
    }

    /** Restarts the progress clock, for a rung that has just been given a goal. */
    private void takeoffProgressReset() {
        this.takeoffProgressBest = Double.POSITIVE_INFINITY;
        this.takeoffProgressTick = ctx.player().tickCount;
    }

    /**
     * The lowest number of blocks above {@code feet} at which a takeoff has somewhere to go: room to stand,
     * jump and open the elytra, and a clear line out of there towards the destination for the rocket to be
     * spent along. {@code 0} when here will do, {@code -1} when nothing up to {@link #TAKEOFF_MAX_LIFT} will.
     * <p>
     * A lateral "are my neighbours open" test answers a different, easier question, and gets both directions
     * wrong: no height satisfies it in a shaft, and open ground with one boulder nearby reads as walled in.
     */
    private int liftHeight(BetterBlockPos feet) {
        if (this.liftCacheTick == ctx.player().tickCount && feet.equals(this.liftCacheAt)) {
            // standingTakeoff runs every tick while we are stuck, and this walks a few thousand blocks
            return this.liftCacheValue;
        }
        // Testing h=0 is never clamped to the pathfinder's ceiling: clamping it there misreads a good launch
        // spot near the ceiling as a sealed pocket.
        final int max = Math.max(0, Math.min(TAKEOFF_MAX_LIFT, 126 - feet.y));
        int found = -1;
        for (int h = 0; h <= max; h++) {
            if (canLaunchFrom(feet.above(h))) {
                found = h;
                break;
            }
        }
        this.liftCacheTick = ctx.player().tickCount;
        this.liftCacheAt = feet;
        this.liftCacheValue = found;
        return found;
    }

    /**
     * How far up the column the first position sits from which {@link #takeoffExit} can find a node cube, or
     * {@code 0} if there is none within {@link #TAKEOFF_MAX_LIFT}.
     */
    private int liftToNodeCube(BetterBlockPos feet) {
        final int max = Math.max(0, Math.min(TAKEOFF_MAX_LIFT, 126 - feet.y));
        for (int h = 1; h <= max; h++) {
            if (!MovementHelper.fullyPassable(ctx, feet.above(h))) {
                return 0;
            }
            if (canLaunchFrom(feet.above(h)) && takeoffExit(feet.above(h)) != null) {
                return h;
            }
        }
        return 0;
    }

    /** Room to stand, jump and open the elytra at {@code at}, and a way out of it. */
    private boolean canLaunchFrom(BlockPos at) {
        return launchRise(at) >= 0;
    }

    /**
     * Whether there is somewhere to come down onto below us, and no lava in the way of getting there.
     * <p>
     * The pinned glide above gives up its altitude on purpose, so it has to know what it is giving it up
     * over. Anything but a floor within {@link #TAKEOFF_PINNED_SAFE_DROP} - a lava pool, or open air all the
     * way down - is a worse place to be than scraping a wall, and scraping does at least end on its own.
     */
    private boolean groundBelowIsSafe() {
        final BetterBlockPos feet = ctx.playerFeet();
        final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy <= TAKEOFF_PINNED_SAFE_DROP; dy++) {
            if (!isInBounds(mut.set(feet.x, feet.y - dy, feet.z))) {
                return false;
            }
            final BlockState state = ctx.world().getBlockState(mut);
            if (!state.getFluidState().isEmpty()) {
                return false;
            }
            if (!MovementHelper.fullyPassable(ctx, mut)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether stepping off this ledge lands somewhere a glide can leave from. {@link #takeoffGoal} asks
     * {@link GoalDescendTo} for height and nothing else, so a shaft satisfies it as well as a cliff - the
     * landing point needs the same launch-clear test a standing spot gets. A long drop past
     * {@link #TAKEOFF_MAX_LIFT} is exempt, since the glide already has more height to leave in than the
     * measurement covers.
     */
    private boolean fallIsFlyable(IMovement fall) {
        final BetterBlockPos dest = fall.getDest();
        if (fall.getSrc().y - dest.y >= TAKEOFF_MAX_LIFT) {
            return true;
        }
        return canLaunchFrom(dest);
    }

    /**
     * The shallowest of {@link #TAKEOFF_RUNWAY_RISES} at which a rocket lit at {@code at} has
     * {@link #TAKEOFF_RUNWAY} blocks of nothing in front of it along the bearing to the destination, or
     * {@code -1} if there is no room to stand and jump here or every rise is blocked.
     * <p>
     * Several rises because the two things that block a takeoff want opposite answers: a crevice needs a
     * steep line to clear its rim, and the nether ceiling refuses one. Which one passed is the caller's
     * business too - it is the angle the jump then has to be aimed at, and approving a flat tunnel and
     * launching steeply into its roof is not a takeoff.
     */
    private int launchRise(BlockPos at) {
        for (int dy = 0; dy <= 3; dy++) {
            if (!MovementHelper.fullyPassable(ctx, at.above(dy))) {
                return -1;
            }
        }
        final Vec3 from = Vec3.atCenterOf(at).add(0, 1.0, 0);
        double dx = 0;
        double dz = 0;
        final BetterBlockPos dest = this.behavior != null ? this.behavior.destination : null;
        if (dest != null) {
            dx = (dest.x + 0.5) - from.x;
            dz = (dest.z + 0.5) - from.z;
        }
        final double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) {
            // no bearing to speak of (no destination, or we are standing on it): straight up is the only
            // direction left that means anything
            dx = 0;
            dz = 0;
        } else {
            dx = dx / len * TAKEOFF_RUNWAY;
            dz = dz / len * TAKEOFF_RUNWAY;
        }
        for (int rise : TAKEOFF_RUNWAY_RISES) {
            if (rayPassable(from, from.add(dx, rise, dz))) {
                return rise;
            }
        }
        return -1;
    }

    /** Whether every block the segment passes through is passable, sampled twice a block. */
    private boolean rayPassable(Vec3 from, Vec3 to) {
        final Vec3 step = to.subtract(from);
        final int samples = Math.max(1, (int) Math.ceil(step.length() * 2));
        final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= samples; i++) {
            final Vec3 p = from.add(step.scale((double) i / samples));
            mut.set((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
            if (!isInBounds(mut) || !MovementHelper.fullyPassable(ctx, mut)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Up to {@link #TAKEOFF_RELOCATE_SPOTS} goals, nearest first, on standable ground within
     * {@code radius} that {@link #canLaunchFrom} accepts. One surface per column - the
     * highest one, which out of a hole is its rim - so the scan stays a few thousand block reads.
     */
    private List<Goal> findLaunchSpots(BetterBlockPos feet, int radius, int columnBudget) {
        final List<int[]> columns = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                final int d = dx * dx + dz * dz;
                // Anything closer than the ladder's own idea of "the same spot" would be walked to and then
                // treated as the place that just failed, so the walk would buy nothing.
                if (d > radius * radius
                        || d <= TAKEOFF_SAME_SPOT_RADIUS * TAKEOFF_SAME_SPOT_RADIUS) {
                    continue;
                }
                columns.add(new int[]{d, dx, dz});
            }
        }
        columns.sort(Comparator.comparingInt(c -> c[0]));

        final List<Goal> spots = new ArrayList<>();
        final List<Goal> lower = new ArrayList<>();
        int examined = 0;
        for (int[] column : columns) {
            if (spots.size() >= TAKEOFF_RELOCATE_SPOTS || examined >= columnBudget) {
                break;
            }
            if (nearRecentLaunch(feet.x + column[1], feet.z + column[2])) {
                // ground a launch was made from in the last minute, and here we are relocating again
                this.lastSpotsSkipped++;
                continue;
            }
            examined++;
            final BetterBlockPos surface = highestFloor(feet.x + column[1], feet.z + column[2], feet.y);
            if (surface == null || !canLaunchFrom(surface)) {
                continue;
            }
            if (surface.y >= feet.y) {
                spots.add(new GoalBlock(surface.x, surface.y, surface.z));
            } else {
                lower.add(new GoalBlock(surface.x, surface.y, surface.z));
            }
        }
        // Downhill only when there is nothing else at all, and never alongside something better: these go into
        // a GoalComposite, which has no order, and the pathfinder simply satisfies whichever goal is cheapest
        // to reach - which downhill nearly always is.
        if (spots.isEmpty()) {
            return lower;
        }
        return spots;
    }

    /**
     * The highest position in the column with solid ground under it, searching from
     * {@link #TAKEOFF_RELOCATE_HEIGHT} above {@code aroundY} down to the same below it, or {@code null} if the
     * column has no floor in that band.
     */
    private BetterBlockPos highestFloor(int x, int z, int aroundY) {
        final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int y = aroundY + TAKEOFF_RELOCATE_HEIGHT; y >= aroundY - TAKEOFF_RELOCATE_HEIGHT; y--) {
            if (!isInBounds(mut.set(x, y, z))) {
                continue;
            }
            if (MovementHelper.fullyPassable(ctx, mut) && !MovementHelper.fullyPassable(ctx, mut.set(x, y - 1, z))) {
                return new BetterBlockPos(x, y, z);
            }
        }
        return null;
    }

    /**
     * Where the look is held through a takeoff jump: the bearing to the destination, tilted up along the
     * runway that was actually measured clear. A fixed angle would be a different line from the one
     * {@link #launchRise} approved - steeper than a flat tunnel it just passed, shallower than the rim of a
     * crevice it just cleared - and the rocket is spent along the line we point at, not the one we checked.
     */
    private Rotation takeoffAim() {
        final BetterBlockPos dest = this.behavior != null ? this.behavior.destination : null;
        if (dest == null) {
            return null;
        }
        final Vec3 from = ctx.player().position();
        final Vec3 to = new Vec3(((double) dest.x) + 0.5, from.y, ((double) dest.z) + 0.5);
        if (from.distanceToSqr(to) < 1) {
            return null;
        }
        final int rise = launchRise(ctx.playerFeet());
        final float pitch = rise < 0
                ? TAKEOFF_PITCH
                : (float) -Math.toDegrees(Math.atan2(rise, TAKEOFF_RUNWAY));
        final Rotation bearing = RotationUtils.calcRotationFromVec3d(from, to, ctx.playerRotations());
        return new Rotation(bearing.getYaw(), pitch);
    }

    /**
     * Where to start the elytra path for a takeoff from {@code feet}: the first clear node cube straight above
     * us, and failing that the highest air over our own head rather than our feet - with 4-block nodes, the
     * cube our own feet sit in is solid by definition, which would leave the native search no start node at all.
     */
    private BetterBlockPos launchPathStart(BetterBlockPos feet) {
        final BetterBlockPos exit = takeoffExit(feet);
        if (exit != null) {
            return exit;
        }
        int top = feet.y;
        while (top - feet.y < TAKEOFF_MAX_EXIT_HEIGHT
                && isInBounds(new BlockPos(feet.x, top + 1, feet.z))
                && MovementHelper.fullyPassable(ctx, new BlockPos(feet.x, top + 1, feet.z))) {
            top++;
        }
        return new BetterBlockPos(feet.x, top, feet.z);
    }

    /**
     * We are in lava and the elytra is not open. Vanilla opens an elytra in lava (only water refuses it); once
     * open, a rocket - at a fraction of its usual push, since fluid rules govern movement here regardless - is
     * aimed straight up rather than at the pool's wall it would otherwise waste itself on. A pool too shallow to
     * glide in shuts the elytra again before that can work: past {@link #lavaWalkOutTicks} this walks out
     * instead, see {@link #walkOutOfLava}.
     */
    private PathingCommand lavaTakeoff() {
        baritone.getPathingBehavior().secretInternalSegmentCancel();
        baritone.getInputOverrideHandler().clearAllKeys();
        // swimming up: slow, but it also lifts us off the pool floor, without which the elytra can't open
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        if (this.behavior.pathManager.getPath().isEmpty() && !this.lavaPathRequested) {
            // nothing to fly along once we're out; the takeoff states would have computed this
            this.lavaPathRequested = true;
            this.behavior.pathManager.pathToDestination(launchPathStart(ctx.playerFeet()));
        }
        if (ctx.player().tickCount - this.lavaSinceTick >= lavaWalkOutTicks()) {
            final PathingCommand out = walkOutOfLava();
            if (out != null) {
                return out;
            }
            // nowhere to walk to: the elytra is all there is
        }
        if (!ctx.player().onGround() && openElytra()) {
            this.state = State.START_FLYING;
            this.takeoffStallTicks = 0;
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * How long a stay in lava lasts before the lava takeoff stops opening the elytra and walks out instead, from
     * {@link baritone.api.Settings#elytraLavaWalkOutSeconds}. A pool only a block deep can never fly out on its
     * own: the floor shuts the elytra again two ticks after every opening, wasting every rocket on the wall. In
     * ticks, and in a long, so a setting large enough to mean "never" does not overflow into "at once".
     */
    private static long lavaWalkOutTicks() {
        return 20L * Math.max(0, Baritone.settings().elytraLavaWalkOutSeconds.value);
    }

    /**
     * Keeps the account of the current stay in lava for {@link #lavaTakeoff}. A stay begins in lava with the
     * elytra shut, so a flight that only skims a lake never counts as stuck, and it keeps running for as long as
     * we are in lava at all - gliding or not - so a moment bobbing at the surface does not reset it either.
     */
    private void trackLava() {
        final int now = ctx.player().tickCount;
        if (ctx.player().isInLava()) {
            if (this.lavaSinceTick < 0 || now < this.lavaLastTick || now - this.lavaLastTick > LAVA_OUT_GRACE_TICKS) {
                forgetLava();
                if (ctx.player().isFallFlying()) {
                    return;
                }
                this.lavaSinceTick = now;
            }
            this.lavaLastTick = now;
        } else if (this.lavaExit != null && now - this.lavaLastTick > LAVA_OUT_GRACE_TICKS) {
            FlightLog.log(String.format(Locale.ROOT, "lava: out on foot at %s, %.1f s after getting in",
                    ctx.playerFeet(), (this.lavaLastTick - this.lavaSinceTick) / 20.0));
            this.lavaExit = null;
        }
    }

    private void forgetLava() {
        this.lavaSinceTick = -1;
        this.lavaLastTick = -1;
        this.lavaExit = null;
        this.lavaExitsTried.clear();
        this.lavaNoExitNoted = false;
    }

    /**
     * Walks out of the lava to the nearest place to stand, once {@link #lavaWalkOutTicks} in it have shown that
     * the elytra will not get us out: facing it, forward, with the jump {@link #lavaTakeoff} already holds.
     * Vanilla lifts a walk into a ledge from inside a fluid onto it when there is room above, so the rim of the
     * pool one block up is as good as flat ground. No elytra or rocket while walking out - both are wasted on
     * lava this shallow.
     *
     * @return {@code null} when there is nowhere within {@link #LAVA_EXIT_RADIUS} blocks to walk to
     */
    private PathingCommand walkOutOfLava() {
        final int now = ctx.player().tickCount;
        final Vec3 pos = ctx.player().position();
        if (this.lavaExit != null) {
            final double left = Math.hypot(this.lavaExit.x + 0.5 - pos.x, this.lavaExit.z + 0.5 - pos.z);
            if (left < this.lavaExitClosest - 0.5) {
                this.lavaExitClosest = left;
                this.lavaExitProgressTick = now;
            } else if (now - this.lavaExitProgressTick > LAVA_EXIT_STALL_TICKS) {
                FlightLog.log("lava: no closer to " + this.lavaExit + " in " + LAVA_EXIT_STALL_TICKS / 20 + " s, trying somewhere else");
                this.lavaExitsTried.add(this.lavaExit);
                this.lavaExit = null;
            }
        }
        if (this.lavaExit == null) {
            if (this.lavaNoExitNoted && now - this.lavaExitSearchTick < LAVA_EXIT_STALL_TICKS) {
                // the last look found nothing: not a whole search every tick meanwhile, only every few seconds
                return null;
            }
            this.lavaExitSearchTick = now;
            final BetterBlockPos feet = ctx.playerFeet();
            this.lavaExit = findLavaExit(feet);
            if (this.lavaExit == null) {
                if (!this.lavaNoExitNoted) {
                    this.lavaNoExitNoted = true;
                    FlightLog.log("lava: nowhere to stand within " + LAVA_EXIT_RADIUS + " blocks of " + feet + ", back to the elytra");
                }
                return null;
            }
            this.lavaExitClosest = Math.hypot(this.lavaExit.x + 0.5 - pos.x, this.lavaExit.z + 0.5 - pos.z);
            this.lavaExitProgressTick = now;
            FlightLog.log(String.format(Locale.ROOT, "lava: %.1f s in lava at %s without getting out, walking to %s (%s, %.1f blocks)",
                    (now - this.lavaSinceTick) / 20.0, feet, this.lavaExit,
                    this.lavaExit.y > feet.y ? "one block up" : "level", this.lavaExitClosest));
        }
        final Rotation towards = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(this.lavaExit), ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(new Rotation(towards.getYaw(), 0), false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * The nearest place to stand out of the lava within {@link #LAVA_EXIT_RADIUS} blocks of {@code feet}, level with
     * them or one block up, that a straight walk through the pool gets to; of the nearest ones, the one most towards
     * the destination. {@code null} if there is none.
     */
    private BetterBlockPos findLavaExit(final BetterBlockPos feet) {
        final BlockStateInterface bsi = new BlockStateInterface(ctx);
        final BetterBlockPos dest = this.behavior != null ? this.behavior.destination : null;
        final double toDestX = dest == null ? 0 : dest.x - feet.x;
        final double toDestZ = dest == null ? 0 : dest.z - feet.z;
        final double toDest = Math.max(1, Math.hypot(toDestX, toDestZ));
        BetterBlockPos best = null;
        double bestScore = 0;
        for (int r = 1; r <= LAVA_EXIT_RADIUS && best == null; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int dy = 0; dy <= 1; dy++) {
                        final BetterBlockPos spot = new BetterBlockPos(feet.x + dx, feet.y + dy, feet.z + dz);
                        if (this.lavaExitsTried.contains(spot) || !standsOutOfLava(bsi, spot) || !wadesTo(feet, spot)) {
                            continue;
                        }
                        // the cosine of the angle to the destination: towards it first, and without one, any
                        final double score = (dx * toDestX + dz * toDestZ) / (Math.hypot(dx, dz) * toDest);
                        if (best == null || score > bestScore) {
                            best = spot;
                            bestScore = score;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Room to stand at {@code spot}, out of the lava, on something that holds us. */
    private boolean standsOutOfLava(final BlockStateInterface bsi, final BetterBlockPos spot) {
        // canWalkOn takes lava for a floor when assumeWalkOnLava is on
        return MovementHelper.fullyPassable(ctx, spot)
                && MovementHelper.fullyPassable(ctx, spot.above())
                && !MovementHelper.isLava(bsi.get0(spot.x, spot.y - 1, spot.z))
                && MovementHelper.canWalkOn(bsi, spot.x, spot.y - 1, spot.z, bsi.get0(spot.x, spot.y - 1, spot.z));
    }

    /**
     * Whether nothing solid stands between {@code feet} and the column of {@code spot} at the height of our feet and
     * of our head, across the width of our body: only lava, which we wade through, or air. The width is what stops a
     * diagonal step between two blocks that touch at a corner, which the line alone slips through.
     */
    private boolean wadesTo(final BetterBlockPos feet, final BetterBlockPos spot) {
        final double dx = spot.x - feet.x;
        final double dz = spot.z - feet.z;
        final double length = Math.hypot(dx, dz);
        // a third of a block either side of the line, the half-width of the player and then some
        final double sideX = -dz / length * 0.3;
        final double sideZ = dx / length * 0.3;
        final int steps = (int) Math.ceil(length * 4);
        for (int i = 1; i < steps; i++) {
            for (int side = -1; side <= 1; side++) {
                final int x = (int) Math.floor(feet.x + 0.5 + dx * i / steps + sideX * side);
                final int z = (int) Math.floor(feet.z + 0.5 + dz * i / steps + sideZ * side);
                if (x == spot.x && z == spot.z || x == feet.x && z == feet.z) {
                    continue;
                }
                // our feet and our head, and for a spot one block up the height the head rises to on the way onto it
                for (int y = feet.y; y <= spot.y + 1; y++) {
                    final BlockPos at = new BlockPos(x, y, z);
                    if (!MovementHelper.fullyPassable(ctx, at) && !MovementHelper.isLava(ctx.world().getBlockState(at))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The centre of the nearest 4x4x4 cube of air we can actually get to from {@code feet}, aligned the way the
     * elytra pathfinder aligns its nodes, or {@code null} if none is within {@link #TAKEOFF_MAX_EXIT_HEIGHT}.
     * Every candidate must be in plain sight of where we stand - "is that cube air" is not the same question as
     * "can we get into it", and a cube behind a wall answers the first while failing the second.
     */
    private BetterBlockPos takeoffExit(BetterBlockPos feet) {
        // the pathfinder is nether only and its world stops at the roof
        final int limit = Math.min(feet.y + TAKEOFF_MAX_EXIT_HEIGHT, 128 - 4);
        final int ox = feet.x & ~3;
        final int oz = feet.z & ~3;
        int gx = 0;
        int gz = 0;
        final BetterBlockPos dest = this.behavior != null ? this.behavior.destination : null;
        if (dest != null) {
            if (Math.abs(dest.x - feet.x) >= Math.abs(dest.z - feet.z)) {
                gx = Integer.signum(dest.x - feet.x) * 4;
            } else {
                gz = Integer.signum(dest.z - feet.z) * 4;
            }
        }
        final Vec3 eye = Vec3.atCenterOf(feet).add(0, 1.0, 0);
        for (int oy = feet.y & ~3; oy <= limit; oy += 4) {
            if (gx != 0 || gz != 0) {
                final BetterBlockPos goalward = new BetterBlockPos(ox + gx + 2, oy + 2, oz + gz + 2);
                if (cubeIsAir(ox + gx, oy, oz + gz) && rayPassable(eye, Vec3.atCenterOf(goalward))) {
                    return goalward;
                }
            }
            final BetterBlockPos above = new BetterBlockPos(ox + 2, oy + 2, oz + 2);
            if (cubeIsAir(ox, oy, oz) && rayPassable(eye, Vec3.atCenterOf(above))) {
                return above;
            }
        }
        return null;
    }

    /** True if the whole 4x4x4 node cube at the given corner is air. */
    private boolean cubeIsAir(int ox, int oy, int oz) {
        final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = ox; x < ox + 4; x++) {
            for (int y = oy; y < oy + 4; y++) {
                for (int z = oz; z < oz + 4; z++) {
                    if (!ctx.world().getBlockState(mut.set(x, y, z)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Opens the elytra mid jump, exactly the way {@link net.minecraft.client.player.LocalPlayer} does it when a
     * player double taps jump: set the flag locally, then tell the server. Doing it here instead of by toggling
     * the jump key means it doesn't hang on the key going up and back down on precisely the right ticks, and it
     * is the same thing on the wire either way - the server has already been sent the movement packets from an
     * ordinary jump, so it agrees we are off the ground by the time the request reaches it.
     *
     * @return {@code true} if the elytra is now open
     */
    private boolean openElytra() {
        if (!ctx.player().tryToStartFallFlying()) {
            return false;
        }
        ctx.player().connection.send(new ServerboundPlayerCommandPacket(ctx.player(), ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        // The rocket goes next tick, once the solver has aimed. A vanilla client cannot use an item in the tick
        // it starts gliding (its use packets go out before its glide packet, while the elytra is still shut),
        // and the use packet carries a look that has to match that tick's movement packet, which the solver
        // has not decided yet. The tick costs nothing: the rocket takes a round trip to show up regardless.
        this.takeoffBoostPending = true;
        this.takeoffOpenedTicksAgo = 0;
        if (Baritone.settings().elytraTakeoffJournal.value) {
            this.takeoffLogTicks = Baritone.settings().elytraTakeoffJournalTicks.value;
        }
        return true;
    }

    private PathingCommand abortTakeoff(String reason) {
        FlightLog.log(String.format(Locale.ROOT, "abort: %sat %s, %s rung, launches %d, climbs %d, relocations %d",
                reason, ctx.playerFeet(), this.takeoffStage, this.standingTakeoffs, this.climbs, this.relocations));
        onLostControl();
        logDirect(reason + TAKEOFF_ADVICE_MSG);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * Where to walk to in order to take off. Descending at least {@link #TAKEOFF_MIN_FALL_HEIGHT} forces
     * {@link WalkOffCalculationContext} to route through a {@link MovementFall}, and aiming deeper makes the
     * biggest reachable drop the cheapest route. In the nether, y=31 (the lava ocean) is as deep as it is worth
     * aiming. The level is always strictly below our feet, since an already-satisfied goal is never pathed to.
     */
    private Goal takeoffGoal() {
        final int feetY = ctx.playerFeet().y;
        final int minY = ctx.world().dimensionType().minY();
        final int deepest = ctx.world().dimension() == Level.NETHER ? minY + 31 : minY;
        final int level = Math.min(feetY - 1, Math.max(minY, Math.min(deepest, feetY - TAKEOFF_MIN_FALL_HEIGHT)));
        return new GoalDescendTo(level);
    }

    /**
     * {@link GoalYLevel} is an exact-level goal: it is satisfied only by standing at the level, and asks to climb
     * back up when below it. For a takeoff all that matters is getting down, so anything at or below the level
     * will do.
     */
    private static final class GoalDescendTo implements Goal {

        private final int level;

        private GoalDescendTo(int level) {
            this.level = level;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return y <= this.level;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return y > this.level ? GoalYLevel.calculate(this.level, y) : 0;
        }

        @Override
        public String toString() {
            return "GoalDescendTo{y<=" + this.level + "}";
        }
    }

    public void landingSpotIsBad(BetterBlockPos endPos) {
        badLandingSpots.add(endPos);
        goingToLandingSpot = false;
        this.landingSpot = null;
        this.state = State.FLYING;
    }

    private void destroyBehaviorAsync() {
        ElytraBehavior behavior = this.behavior;
        if (behavior != null) {
            this.behavior = null;
            Baritone.getExecutor().execute(behavior::destroy);
        }
    }

    @Override
    public double priority() {
        return 0; // higher priority than CustomGoalProcess
    }

    @Override
    public String displayName0() {
        return "Elytra - " + this.state.description;
    }

    @Override
    public void repackChunks() {
        if (this.behavior != null) {
            this.behavior.repackChunks();
        }
    }

    @Override
    public BlockPos currentDestination() {
        return this.behavior != null ? this.behavior.destination : null;
    }

    @Override
    public void pathTo(BlockPos destination) {
        this.pathTo0(destination, false);
    }

    private void pathTo0(BlockPos destination, boolean appendDestination) {
        if (ctx.player() == null || ctx.player().level().dimension() != Level.NETHER) {
            return;
        }
        this.onLostControl();
        this.predictingTerrain = Baritone.settings().elytraPredictTerrain.value;
        this.behavior = new ElytraBehavior(this.baritone, this, destination, appendDestination);
        if (ctx.world() != null) {
            this.behavior.repackChunks();
        }
        this.behavior.pathTo();
    }

    @Override
    public void pathTo(Goal iGoal) {
        final int x;
        final int y;
        final int z;
        if (iGoal instanceof GoalXZ) {
            GoalXZ goal = (GoalXZ) iGoal;
            x = goal.getX();
            y = 64;
            z = goal.getZ();
        } else if (iGoal instanceof GoalBlock) {
            GoalBlock goal = (GoalBlock) iGoal;
            x = goal.x;
            y = goal.y;
            z = goal.z;
        } else {
            throw new IllegalArgumentException("The goal must be a GoalXZ or GoalBlock");
        }
        if (y <= 0 || y >= 128) {
            throw new IllegalArgumentException("The y of the goal is not between 0 and 128");
        }
        this.pathTo(new BlockPos(x, y, z));
    }

    /**
     * Restricts the elytra path search to a corridor of chunks. Meant to be called by another mod (by
     * reflection, since it is not part of {@link IElytraProcess}), from any thread: the work hops to the game
     * thread itself, because the refresh it triggers reads loaded chunks.
     *
     * @param chunkKeys       The chunks the path may use, as {@link ChunkPos#asLong} keys
     * @param halfWidthChunks How far to widen the corridor on every side of those chunks, in chunks (Chebyshev)
     */
    public void setPathCorridor(long[] chunkKeys, int halfWidthChunks) {
        if (!ctx.minecraft().isSameThread()) {
            ctx.minecraft().execute(() -> this.setPathCorridor(chunkKeys, halfWidthChunks));
            return;
        }
        final int width = 2 * halfWidthChunks + 1;
        final LongOpenHashSet next = new LongOpenHashSet(chunkKeys.length * width * width);
        for (long key : chunkKeys) {
            final int x = ChunkPos.getX(key);
            final int z = ChunkPos.getZ(key);
            for (int dx = -halfWidthChunks; dx <= halfWidthChunks; dx++) {
                for (int dz = -halfWidthChunks; dz <= halfWidthChunks; dz++) {
                    next.add(ChunkPos.asLong(x + dx, z + dz));
                }
            }
        }
        // Only the chunks whose membership changed need touching in the corridor context, and the sets are
        // mostly the same from one push to the next, so the symmetric difference is what the refresh gets.
        final LongSet previous = this.corridor;
        final LongOpenHashSet flipped = new LongOpenHashSet();
        for (long key : next) {
            if (!previous.contains(key)) {
                flipped.add(key);
            }
        }
        for (long key : previous) {
            if (!next.contains(key)) {
                flipped.add(key);
            }
        }
        this.corridor = next;
        if (this.behavior != null && Baritone.settings().elytraCorridor.value) {
            this.behavior.corridorRefresh(flipped);
        }
    }

    /**
     * Removes the corridor, so the next searches use the whole map again. Same calling rules as
     * {@link #setPathCorridor}.
     */
    public void clearPathCorridor() {
        this.setPathCorridor(new long[0], 0);
    }

    /**
     * @return Whether a corridor is currently set. Public because the behavior that consumes it lives in another
     *         package; a volatile read, safe from any thread.
     */
    public boolean hasCorridor() {
        return !this.corridor.isEmpty();
    }

    /**
     * @param key A {@link ChunkPos#asLong} chunk key
     * @return Whether that chunk is inside the (already dilated) corridor
     */
    public boolean corridorContains(long key) {
        return this.corridor.contains(key);
    }

    private boolean shouldLandForSafety() {
        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA || chest.getMaxDamage() - chest.getDamageValue() < Baritone.settings().elytraMinimumDurability.value) {
            // elytrabehavior replaces when durability <= minimumDurability, so if durability < minimumDurability then we can reasonably assume that the elytra will soon be broken without replacement
            return true;
        }

        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int qty = 0;
        for (int i = 0; i < 36; i++) {
            if (ElytraBehavior.isFireworks(inv.get(i))) {
                qty += inv.get(i).getCount();
            }
        }
        if (qty <= Baritone.settings().elytraMinFireworksBeforeLanding.value) {
            return true;
        }
        return false;
    }

    /**
     * Whether a takeoff opened its elytra recently enough that its flight path should not be recomputed yet.
     * Read by {@link ElytraBehavior}'s obstacle pass, which otherwise throws that path away immediately.
     */
    public boolean inTakeoffGrace() {
        return this.takeoffOpenedTicksAgo >= 0 && this.takeoffOpenedTicksAgo < TAKEOFF_PATH_GRACE_TICKS;
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public boolean isSafeToCancel() {
        return !this.isActive() || !(this.state == State.FLYING || this.state == State.START_FLYING);
    }

    public enum State {
        LOCATE_JUMP("Finding spot to jump off"),
        PAUSE("Waiting for elytra path"),
        GET_TO_JUMP("Walking to takeoff"),
        PILLAR_UP("Pillaring up to take off"),
        WALK_TO_LAUNCH("Walking to a spot to take off from"),
        TAKEOFF_JUMP("Taking off"),
        START_FLYING("Begin flying"),
        FLYING("Flying"),
        LANDING("Landing");

        public final String description;

        State(String desc) {
            this.description = desc;
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (this.behavior != null) this.behavior.onRenderPass(event);
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getWorld() != null && event.getState() == EventState.POST) {
            // Exiting the world, just destroy
            destroyBehaviorAsync();
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (this.behavior != null) this.behavior.onChunkEvent(event);
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (this.behavior != null) this.behavior.onBlockChange(event);
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (this.behavior != null) this.behavior.onReceivePacket(event);
    }

    @Override
    public void onPostTick(TickEvent event) {
        IBaritoneProcess procThisTick = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
        if (this.behavior != null && procThisTick == this) this.behavior.onPostTick(event);
    }

    /**
     * The ordinary calculation context, except that nothing may be broken: the takeoff climbs with it, so a
     * pillar goes up through open air or not at all, and a climb only makeable by breaking fails to compute and
     * sends the ladder on to relocation instead - a takeoff never breaks a block for room.
     */
    public static final class NoBreakCalculationContext extends CalculationContext {

        public NoBreakCalculationContext(IBaritone baritone) {
            super(baritone, true);
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }
    }

    /**
     * Custom calculation context which makes the player fall into lava
     */
    public static final class WalkOffCalculationContext extends CalculationContext {

        public WalkOffCalculationContext(IBaritone baritone) {
            super(baritone, true);
            this.allowFallIntoLava = true;
            this.minFallHeight = TAKEOFF_MIN_FALL_HEIGHT;
            this.maxFallHeightNoWater = 10000;
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double placeBucketCost() {
            return COST_INF;
        }
    }

    private static boolean isInBounds(BlockPos pos) {
        return pos.getY() >= 0 && pos.getY() < 128;
    }

    /**
     * Bastion-specific materials. None of them appear anywhere else generated, so a single one in a
     * candidate's footprint is as good a bastion signal as a piglin brute — see
     * {@link #bastionRejectionReason}, which checks both.
     */
    private static final Set<Block> BASTION_BLOCKS = Set.of(
        Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS,
        Blocks.CHISELED_POLISHED_BLACKSTONE, Blocks.GILDED_BLACKSTONE,
        Blocks.POLISHED_BLACKSTONE_BRICK_SLAB, Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS,
        Blocks.POLISHED_BLACKSTONE_BRICK_WALL, Blocks.IRON_CHAIN
    );

    /** Every nether brick variant, not just the plain block the old {@code isSafeBlock} checked. */
    private static final Set<Block> NETHER_BRICK_FAMILY = Set.of(
        Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS,
        Blocks.NETHER_BRICK_SLAB, Blocks.NETHER_BRICK_WALL, Blocks.RED_NETHER_BRICKS,
        Blocks.RED_NETHER_BRICK_STAIRS, Blocks.RED_NETHER_BRICK_SLAB, Blocks.RED_NETHER_BRICK_WALL,
        Blocks.CHISELED_NETHER_BRICKS, Blocks.CRACKED_NETHER_BRICKS
    );

    /**
     * Whether the block at {@code pos} can be landed on. Magma and fluids are never safe underfoot;
     * bastion materials are never safe at all, whatever their shape, since a spot inside one is
     * rejected regardless by {@link #bastionRejectionReason} anyway and there is no reason to stand
     * on one even when that check is bypassed by a low radius. Nether brick follows
     * {@code Settings#elytraAllowLandOnNetherFortress} as before, just across the whole family
     * instead of the plain block. Everything else is judged on shape: {@code elytraLandOnAnySolid}
     * on trades the old fixed list (netherrack, gravel) for "solid top face", which is what actually
     * matters for standing on it.
     */
    private boolean isSafeLandingBlock(BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        Block block = state.getBlock();

        if (block == Blocks.MAGMA_BLOCK) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (BASTION_BLOCKS.contains(block)) return false;
        if (NETHER_BRICK_FAMILY.contains(block) && !Baritone.settings().elytraAllowLandOnNetherFortress.value) return false;

        if (!Baritone.settings().elytraLandOnAnySolid.value) {
            return block == Blocks.NETHERRACK || block == Blocks.GRAVEL;
        }

        return state.isCollisionShapeFullBlock(ctx.world(), pos) || state.isFaceSturdy(ctx.world(), pos, Direction.UP);
    }

    private boolean isAtEdge(BlockPos pos) {
        return !isSafeLandingBlock(pos.north())
                || !isSafeLandingBlock(pos.south())
                || !isSafeLandingBlock(pos.east())
                || !isSafeLandingBlock(pos.west())
                // corners
                || !isSafeLandingBlock(pos.north().west())
                || !isSafeLandingBlock(pos.north().east())
                || !isSafeLandingBlock(pos.south().west())
                || !isSafeLandingBlock(pos.south().east());
    }

    /**
     * {@code "block"} if a bastion material sits in the spot's own footprint (checked ±8 in x/z,
     * -2..+6 in y — a bastion's floor and bridges are polished blackstone brick, and its rooms are a
     * few blocks tall), {@code "brute"} if a piglin brute is within
     * {@link Settings#elytraLandingBastionRadius} — brutes exist only in bastions, so one nearby is
     * the surer signal when no block has generated in render distance yet — or {@code null} if
     * neither.
     */
    private String bastionRejectionReason(BetterBlockPos spot) {
        final int radius = 8;
        for (int dy = -2; dy <= 6; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (BASTION_BLOCKS.contains(ctx.world().getBlockState(spot.offset(dx, dy, dz)).getBlock())) {
                        return "block";
                    }
                }
            }
        }

        final double bruteRadiusSq = (double) Baritone.settings().elytraLandingBastionRadius.value
            * Baritone.settings().elytraLandingBastionRadius.value;
        boolean bruteNearby = ctx.entitiesStream()
            .anyMatch(e -> e instanceof PiglinBrute && e.position().distanceToSqr(spot.getCenter()) < bruteRadiusSq);
        return bruteNearby ? "brute" : null;
    }

    private boolean isColumnAir(BlockPos landingSpot, int minHeight) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(landingSpot.getX(), landingSpot.getY(), landingSpot.getZ());
        final int maxY = mut.getY() + minHeight;
        for (int y = mut.getY() + 1; y <= maxY; y++) {
            mut.set(mut.getX(), y, mut.getZ());
            if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAirBubble(BlockPos pos) {
        final int radius = 4; // Half of the full width, rounded down, as we're counting blocks in each direction from the center
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    mut.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private BetterBlockPos checkLandingSpot(BlockPos pos, LongOpenHashSet checkedSpots) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
        while (mut.getY() >= 0) {
            if (checkedSpots.contains(mut.asLong())) {
                return null;
            }
            checkedSpots.add(mut.asLong());
            Block block = ctx.world().getBlockState(mut).getBlock();

            if (isSafeLandingBlock(mut)) {
                if (!isAtEdge(mut)) {
                    return new BetterBlockPos(mut);
                }
                return null;
            } else if (!(block instanceof AirBlock)) {
                return null;
            }
            mut.set(mut.getX(), mut.getY() - 1, mut.getZ());
        }
        return null; // void
    }

    private static final int LANDING_COLUMN_HEIGHT = 15;
    private Set<BetterBlockPos> badLandingSpots = new HashSet<>();

    private BetterBlockPos findSafeLandingSpot(BetterBlockPos start) {
        Queue<BetterBlockPos> queue = new PriorityQueue<>(Comparator.<BetterBlockPos>comparingInt(pos -> (pos.x - start.x) * (pos.x - start.x) + (pos.z - start.z) * (pos.z - start.z)).thenComparingInt(pos -> -pos.y));
        Set<BetterBlockPos> visited = new HashSet<>();
        LongOpenHashSet checkedPositions = new LongOpenHashSet();
        queue.add(start);

        // The first otherwise-good spot a bastion rejected, kept in case nothing else turns up: an
        // empty result here means orbiting until the rockets run out (see the caller), and that is
        // worse than landing next to a bastion instead of in the middle of it.
        BetterBlockPos bastionFallback = null;

        while (!queue.isEmpty()) {
            BetterBlockPos pos = queue.poll();
            // Any of the three airs, like the column and bubble checks below. Now that the pathfinder counts cave
            // air as air a path can end in a carved cave, and a search that only spread through plain air would
            // stop at its very first block there and leave us circling until the rockets ran out.
            if (ctx.world().isLoaded(pos) && isInBounds(pos) && ctx.world().getBlockState(pos).getBlock() instanceof AirBlock) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, checkedPositions);
                if (actualLandingSpot != null && isColumnAir(actualLandingSpot, LANDING_COLUMN_HEIGHT) && hasAirBubble(actualLandingSpot.above(LANDING_COLUMN_HEIGHT)) && !badLandingSpots.contains(actualLandingSpot.above(LANDING_COLUMN_HEIGHT))) {
                    String rejection = bastionRejectionReason(actualLandingSpot);
                    if (rejection == null) {
                        return actualLandingSpot.above(LANDING_COLUMN_HEIGHT);
                    }
                    if (bastionFallback == null) {
                        bastionFallback = actualLandingSpot.above(LANDING_COLUMN_HEIGHT);
                        logDirect("Landing spot near a bastion skipped (" + rejection + ")");
                    }
                }
                if (visited.add(pos.north())) queue.add(pos.north());
                if (visited.add(pos.east())) queue.add(pos.east());
                if (visited.add(pos.south())) queue.add(pos.south());
                if (visited.add(pos.west())) queue.add(pos.west());
                if (visited.add(pos.above())) queue.add(pos.above());
                if (visited.add(pos.below())) queue.add(pos.below());
            }
        }

        if (bastionFallback != null) {
            logDirect("No other spot, landing near the bastion anyway");
            return bastionFallback;
        }
        return null;
    }
}
