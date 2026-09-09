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
import baritone.process.elytra.NetherPathfinderContext;
import baritone.process.elytra.NullElytraProcess;
import baritone.utils.BaritoneProcessHelper;
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
     * The chunks the elytra path is allowed to use, as {@link ChunkPos#asLong} keys, already dilated by the
     * half width the caller asked for. Empty means no corridor. It lives here and not on the behavior because
     * {@link #pathTo0} throws the behavior away and builds a new one for every destination, and the corridor
     * has to survive that so the new behavior can start with it. Replaced wholesale on every push, never
     * mutated, so a reader on any thread sees a consistent set.
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
    /** How many times one takeoff may give up on a spot and walk to another one before admitting defeat. */
    private static final int MAX_RELOCATIONS = 2;
    /** How many times it may climb, likewise. */
    private static final int MAX_CLIMBS = 2;
    /** How far the relocation search looks on its second, wider pass, and what that pass may spend. */
    private static final int TAKEOFF_RELOCATE_RADIUS_WIDE = 32;
    private static final int TAKEOFF_RELOCATE_COLUMN_BUDGET_WIDE = 1200;
    /** How far to walk towards the goal when even the wide relocation search has nothing to offer. */
    private static final int TAKEOFF_WALK_ONWARDS = 48;
    /**
     * How long a rung that is walking or climbing may go without getting any closer to its goal before it is
     * abandoned.
     * <p>
     * The other stall test asks whether anything is happening. That is the wrong question, and a flight was
     * lost to it: a two-block pillar that could not be built had Baritone re-planning it successfully every
     * six seconds, cancelling on its own movement timeout, and re-planning again - eighty-five seconds of
     * loud, busy, motionless failure, with an executor present on every tick, so the stall counter was reset
     * on every tick and never came near firing. Progress is the question.
     */
    private static final int TAKEOFF_NO_PROGRESS_TICKS = 200;
    /**
     * How long we have to stay in the air, or how far we have to get from where we jumped, before the takeoff
     * counts as having worked and the ladder's memory of the spot is dropped.
     * <p>
     * Being airborne is not the same as having taken off. A jump that opens the elytra, scrapes the ceiling at
     * two centimetres a tick and drops back in the hole is gliding by every test the game offers - and it used
     * to wipe the attempt counters on its way past. The ladder could therefore never reach the rung that mines
     * its way out, because every attempt erased the count of attempts. Ten identical launches from one block,
     * twelve seconds apart, measured.
     */
    private static final int TAKEOFF_SUCCESS_TICKS = 60;
    private static final int TAKEOFF_SUCCESS_DISTANCE = 32;
    /** How far we have to move for the ladder to consider itself at a new spot and start over from the top. */
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
     * How long after the elytra opens the takeoff path is left alone. The path a takeoff computes starts from
     * a cube chosen so the first boost leaves along something clear; the obstacle pass, which recomputes from
     * the player's feet, replaced it on the very first airborne tick of all five takeoffs in the flight this
     * was measured on, three of them landing on the same cached node. Long enough to be clear of the launch
     * site, short enough that a real obstacle a second later is still routed around.
     */
    private static final int TAKEOFF_PATH_GRACE_TICKS = 20;
    private boolean lavaPathRequested;
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
     * {@link #onLostControl}: every failure in here tears the behavior down, and the mod driving us hands the
     * same goal straight back, so a ladder that lived on the behavior would restart at the top every few
     * seconds and burn the same three rockets on the same bad spot forever - which is exactly what it used to
     * do. {@link #forgetTakeoffSpot} is what clears it, when we have actually flown, moved, or waited long
     * enough that the spot is no longer the one that failed.
     */
    private BetterBlockPos takeoffSpot;
    private Stage takeoffStage = Stage.LAUNCH;
    private int takeoffSpotTick;
    private int relocations;
    private int climbs;
    /** Consecutive ticks of gliding, to tell a flight from a takeoff that got off the ground and no further. */
    private int flyingTicks;
    /**
     * The takeoff journal: how many flight ticks are still to be written, and what the launch was measured
     * and asked to be, so those ticks can be read against it.
     * <p>
     * It exists to separate two explanations of the same event, which no amount of reading the code has
     * separated: a takeoff from a spot {@link #launchRise} measured as clear that flies into terrain anyway.
     * Either the measurement is wrong - it casts one line and the player is a body 0.6 wide - or the elytra
     * path computed from the exit cube is replaced, on the first airborne tick, by one recomputed from the
     * player's feet, which is what the solver then aims the rocket down. The first shows up as a takeoff that
     * follows its own first node into rock; the second as that first node changing between the tick the
     * elytra opens and the one after.
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

        if (!ctx.player().isFallFlying() && ctx.player().isInLava()) {
            return lavaTakeoff();
        }

        if (calcFailed) {
            if (this.state == State.PILLAR_UP) {
                // Nothing to climb to that a path can reach; the next rung looks somewhere else instead of
                // asking for the same climb again.
                this.takeoffStage = Stage.RELOCATE;
            } else if (this.state == State.WALK_TO_LAUNCH) {
                this.takeoffStage = Stage.EXHAUSTED;
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

        // No landing business while in lava. The solver is busy climbing out, and every path the landing search
        // asks for from in here starts inside the pool and fails at once, which used to re-issue one every tick.
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
            // Airborne, but not necessarily away: only a flight that lasts, or that gets us clear of where we
            // jumped from, answers what the ladder was working through.
            this.flyingTicks++;
            final boolean clear = this.takeoffSpot == null
                    || this.takeoffSpot.distanceSq(ctx.playerFeet()) > TAKEOFF_SUCCESS_DISTANCE * TAKEOFF_SUCCESS_DISTANCE;
            if (this.flyingTicks > TAKEOFF_SUCCESS_TICKS || clear) {
                forgetTakeoffSpot();
            }
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

                if (fall != null) {
                    final BetterBlockPos from = new BetterBlockPos(
                            (fall.getSrc().x + fall.getDest().x) / 2,
                            (fall.getSrc().y + fall.getDest().y) / 2,
                            (fall.getSrc().z + fall.getDest().z) / 2
                    );
                    final ElytraBehavior owner = this.behavior;
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
            return new PathingCommand(this.goal, PathingCommandType.SET_GOAL_AND_PATH);
        }

        if (this.state == State.WALK_TO_LAUNCH) {
            final BetterBlockPos feet = ctx.playerFeet();
            if (this.goal != null && ctx.player().onGround() && this.goal.isInGoal(feet.x, feet.y, feet.z)) {
                // Arrived somewhere that measured as launchable. It is a different spot, so the ladder starts
                // over from the top there, ledge search included - that is still the cheapest takeoff there is.
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
                this.takeoffStage = Stage.EXHAUSTED;
                return standingTakeoff();
            }
            if (takeoffNoProgress()) {
                logDirect("Not getting any closer to a spot to take off from, giving up on the walk.");
                this.goal = null;
                this.takeoffStage = Stage.EXHAUSTED;
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
     * Gets into the air from wherever the ground left us, when {@link WalkOffCalculationContext} found no ledge
     * to walk off.
     * <p>
     * There is no one way to do that, which is the whole point: a takeoff needs a stance with room to jump and
     * an unobstructed line to leave along, and the terrain either hands us one, hides one a few blocks above,
     * hides one a few blocks away, or has none at all. {@link #liftHeight} measures which of those it is, and
     * the ladder below tries them in the order of what they cost - a rocket, a pillar, a walk - so a spot that
     * defeats one is answered by the next instead of by the same rocket three more times. The stage survives
     * the process being torn down and handed the goal again, so the ladder makes progress across those
     * restarts rather than starting over from the top every five seconds.
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
        if (lift == 0 && takeoffExit(feet) == null) {
            // launchRise is happy - there is room to jump and a clear line towards the goal - but there is no
            // node cube for the flight path to start in, and the cube is what the solver aims the first boost
            // at. The two measure different things and they disagree here by as much as 24 blocks. Prefer
            // climbing to where they agree.
            final int climb = liftToNodeCube(feet);
            if (climb > 0) {
                lift = climb;
            } else {
                // No height above us satisfies both, which is what a shaft looks like: launchRise threads a
                // single ray out through the opening at the steepest rise it has, no 4x4x4 cube ever fits in
                // the width, and every height fails the same 16-block line. Launching anyway is what the bot
                // did ten times in a row from y=32 at 6235180 2680864 - rise=10, column fallback, back on the
                // ground four seconds later - because a ray is not a body and the rocket is spent along the
                // path, which starts 29 blocks straight up. Climb the shaft instead: the ordinary context is
                // allowed to pillar and mine, and the rim is where the ray finally means something.
                final int out = climbableColumn(feet);
                if (out > 0) {
                    lift = out;
                }
            }
        }

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
     * Climbs to the stance {@link #liftHeight} found above us, by pillaring or mining - this goes out on the
     * ordinary calculation context, unlike the ledge search, precisely so that the pathfinder is allowed to
     * place and break its way up. {@code null} when there is nothing above worth climbing to, or no way to
     * climb, so the caller can move on to the next rung.
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
        final boolean canBreak = Baritone.settings().allowBreak.value;
        if (!canPlace && !canBreak) {
            logDebug("walled in, but there's nothing to pillar with and allowBreak is off");
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
        return new PathingCommand(this.goal, PathingCommandType.SET_GOAL_AND_PATH);
    }

    /**
     * Walks to the nearest spots around us that a takeoff would actually work from, on the ordinary calculation
     * context so the pathfinder may dig and bridge its way there. This is the rung that leaves a sealed pocket
     * or the top of a fungus canopy, neither of which any amount of jumping or climbing on the spot can fix.
     * {@code null} when there is nowhere better within range, or we have already moved twice.
     */
    private PathingCommand walkToLaunch(BetterBlockPos feet) {
        if (this.relocations >= MAX_RELOCATIONS) {
            return null;
        }
        // Near first, and only then wide: the wide sweep costs a few tens of thousands of block reads, which
        // is worth paying once we are otherwise out of ideas and not before.
        List<Goal> spots = findLaunchSpots(feet, TAKEOFF_RELOCATE_RADIUS, TAKEOFF_RELOCATE_COLUMN_BUDGET);
        if (spots.isEmpty()) {
            spots = findLaunchSpots(feet, TAKEOFF_RELOCATE_RADIUS_WIDE, TAKEOFF_RELOCATE_COLUMN_BUDGET_WIDE);
        }
        final String what;
        if (!spots.isEmpty()) {
            this.goal = new GoalComposite(spots.toArray(new Goal[0]));
            what = "walking to one of " + spots.size() + " spots that will work";
        } else {
            // Nothing measured as launchable anywhere in range, which in a cave system is perfectly possible.
            // Set off towards the destination on foot and ask again from there.
            //
            // This is the crude rung and it looks it: the goal is a compass direction rather than a place, so
            // the pathfinder satisfies it however it can, digging and bridging, sometimes spending a second of
            // its own thread on a segment worth a few blocks. It was removed once for exactly that reason and
            // the removal was wrong - in the one flight where it fired, it walked the bot out of a pocket at
            // y=103 and had it airborne again half a minute later, for five thousand more blocks. Crude and
            // working beats tidy and stuck.
            final Goal onwards = walkOnwardsGoal(feet);
            if (onwards == null) {
                return null;
            }
            this.goal = onwards;
            what = "no spot around here works either, walking on towards the goal to look further";
        }
        this.relocations++;
        this.state = State.WALK_TO_LAUNCH;
        this.takeoffStallTicks = 0;
        takeoffProgressReset();
        logDirect("Nowhere to take off from here, " + what + ".");
        return new PathingCommand(this.goal, PathingCommandType.SET_GOAL_AND_PATH);
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
     * same way the success side of those handlers already does.
     * <p>
     * This used to be {@link #onLostControl}, which ended the flight outright and left the bot standing there
     * until something else noticed and handed the goal back - and since nothing was remembered across that,
     * what came back was the identical attempt. It is a fact about this spot, so it advances the ladder past
     * whatever rung asked for that path and goes round again.
     */
    private void takeoffPathFailed() {
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
     * Starts the ladder over when {@code feet} is somewhere other than where it was last run, or when it has
     * been working the same spot long enough that the terrain around it is worth re-reading.
     * <p>
     * Horizontal distance only, and the clock is stamped when the spot changes rather than on every call.
     * Both matter: measuring in three dimensions made a climb read as a new spot and cleared the one-climb
     * rule with it, and re-stamping every tick - which is every tick, since this runs from the state that
     * loops - meant the expiry could never elapse and a spot that reached the end of the ladder stayed there
     * for the rest of the session.
     */
    private void rememberTakeoffSpot(BetterBlockPos feet) {
        final int now = ctx.player().tickCount;
        final int dx = this.takeoffSpot == null ? 0 : this.takeoffSpot.x - feet.x;
        final int dz = this.takeoffSpot == null ? 0 : this.takeoffSpot.z - feet.z;
        final boolean sameSpot = this.takeoffSpot != null
                && dx * dx + dz * dz <= TAKEOFF_SAME_SPOT_RADIUS * TAKEOFF_SAME_SPOT_RADIUS
                && now - this.takeoffSpotTick < TAKEOFF_MEMORY_TICKS;
        if (sameSpot) {
            return;
        }
        this.takeoffStage = Stage.LAUNCH;
        this.standingTakeoffs = 0;
        this.pillared = false;
        this.takeoffSpot = feet;
        this.takeoffSpotTick = now;
    }

    /**
     * One line per flight tick for the first few ticks after the elytra opens, while the journal is armed.
     * <p>
     * The line that matters is the path's first node: it is what the solver aims at and therefore where the
     * takeoff rocket is spent. If it stays the node cube the launch asked for, the path survived and a
     * collision means the runway measurement was wrong. If it changes to something at the player's feet, the
     * path was recomputed underneath the takeoff and the measurement is not on trial at all.
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

    /** Drops the ladder's memory of a spot entirely - we are flying, so whatever it was working on is moot. */
    private void forgetTakeoffSpot() {
        this.takeoffSpot = null;
        this.takeoffStage = Stage.LAUNCH;
        // the journal belongs to the takeoff, not to the flight; it stops itself after its own ticks
        this.relocations = 0;
        this.climbs = 0;
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
     * This asks the question the takeoff actually cares about. The lateral "are 3 of my 4 neighbours open"
     * test it replaces asked a neighbouring one, and answered it wrongly in both directions: in a shaft no
     * height ever satisfied it, so the climb it existed to trigger never ran, and on open ground a single
     * boulder two blocks away read as walled in.
     */
    private int liftHeight(BetterBlockPos feet) {
        if (this.liftCacheTick == ctx.player().tickCount && feet.equals(this.liftCacheAt)) {
            // standingTakeoff runs every tick while we are stuck, and this walks a few thousand blocks
            return this.liftCacheValue;
        }
        // Where we stand is always worth testing, whatever the roof is doing: clamping the search to the
        // pathfinder's ceiling used to make this loop skip h=0 entirely for anyone standing near it, which
        // reported a perfectly good launch spot as a sealed pocket.
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
    /**
     * How far the open column straight above {@code feet} can be climbed, or {@code 0} when there is not
     * enough of it to be worth a pillar.
     * <p>
     * The last resort of {@link #standingTakeoff} when nothing above measures as launchable: it does not ask
     * whether the top is a good takeoff, only whether it is somewhere else. In a shaft that is the whole
     * point - the measurements that keep approving the floor all fail the same way for the same reason, and
     * the one thing that changes any of them is height. Capped at what {@link #climbToLaunch} will accept, so
     * the rung it is meant to trigger does not refuse it on arrival.
     */
    private int climbableColumn(BetterBlockPos feet) {
        final int max = Math.min(
                Math.max(0, Math.min(TAKEOFF_MAX_LIFT, 126 - feet.y)),
                Baritone.settings().elytraTakeoffPillarMaxHeight.value
        );
        int h = 0;
        while (h < max && MovementHelper.fullyPassable(ctx, feet.above(h + 1))) {
            h++;
        }
        // Four blocks is the room a takeoff needs to stand and open in anyway; anything less is a ceiling, not
        // a shaft, and pillaring into it would only spend the climb the ladder has left.
        return h >= 4 ? h : 0;
    }

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
        // Downhill only when there is nothing else at all, and never alongside something better. Ordering the
        // list was useless: these go into a GoalComposite, which has no order - the pathfinder simply
        // satisfies whichever goal is cheapest to reach, and downhill is nearly always the cheapest. That is
        // how a bot stuck in a hole walked thirteen blocks out of it and then straight back in, ten times in
        // a row, because the hole was still one of the six goals it had been handed.
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
     * us, and failing that the highest air we have over our own head rather than our feet.
     * <p>
     * Falling back to the feet is what used to end the flight: with 4-block nodes, the cube our feet sit in is
     * solid by definition whenever the cube search came back empty, the native search has no start node, and
     * the exception that raises took the whole process down with it.
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
     * We are in lava and the elytra is not open. Whatever the ground states were doing can wait: an elytra opens
     * in lava (vanilla only refuses in water), and a rocket lit looking straight up is what gets us out. Swimming
     * is far too slow, and since vanilla moves us by the fluid rules while we are in one, glide or not, the
     * rocket is the only thrust there is and keeps a fraction of its usual push. Pointing it at the pool's wall
     * would spend all of that on basalt, so once the elytra is open {@link ElytraBehavior} aims straight up and
     * keeps lighting rockets until we are out.
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
        if (!ctx.player().onGround() && openElytra()) {
            this.state = State.START_FLYING;
            this.takeoffStallTicks = 0;
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * The centre of the nearest 4x4x4 cube of air we can actually get to from {@code feet}, aligned the way
     * the elytra pathfinder aligns its nodes, or {@code null} if there is none within
     * {@link #TAKEOFF_MAX_EXIT_HEIGHT}.
     * <p>
     * Reachable is the whole point and it used to be assumed rather than tested. A cube one grid cell towards
     * the destination is preferred when there is one, so the path leaves down the goal line instead of
     * doubling back - but "is that cube air" is not the same question as "can we get into it", and answering
     * the first while claiming the second is what put the start of the path, and the boost aimed at it,
     * through the wall next to us: on the far side of a wall is exactly where a cube of air tends to be. Every
     * candidate now has to be in plain sight of where we are standing, which is what a rocket lit here can
     * reach.
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
        onLostControl();
        logDirect(reason + TAKEOFF_ADVICE_MSG);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * Where to walk to in order to take off.
     * <p>
     * {@link WalkOffCalculationContext} only offers single steps and falls of at least
     * {@link #TAKEOFF_MIN_FALL_HEIGHT} as ways down, so asking to descend at least that far is what forces the
     * path to contain a {@link MovementFall} at all. Aiming deeper than that is what makes the biggest reachable
     * drop the cheapest route: a long fall costs a handful of ticks where the same descent by single steps costs
     * roughly 8 ticks a block, so the pathfinder will happily walk a long way to an overhang. In the nether the
     * lava ocean at y=31 is as deep as it is worth aiming, since anything that reaches it is already more runway
     * than we need.
     * <p>
     * The level is always strictly below our feet. A goal we are already standing in is never pathed to at all
     * ({@link baritone.behavior.PathingBehavior#secretInternalSetGoalAndPath}), which used to leave the process
     * paused forever waiting on an executor that nothing was calculating - most easily hit by landing at exactly
     * y=31 in the nether, where the old fixed {@code GoalYLevel(31)} was already satisfied.
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
            } else if (block != Blocks.AIR) {
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
            if (ctx.world().isLoaded(pos) && isInBounds(pos) && ctx.world().getBlockState(pos).getBlock() == Blocks.AIR) {
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
