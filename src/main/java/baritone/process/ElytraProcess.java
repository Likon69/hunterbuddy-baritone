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
    private boolean lavaPathRequested;
    /** Whether this takeoff sequence has already pillared up once. One pillar per sequence: a spot that still fails after it is walled in some other way a pillar can't fix. */
    private boolean pillared;
    /** The feet-Y the current pillar is climbing to. */
    private int pillarTargetY;

    @Override
    public void onLostControl() {
        this.state = State.START_FLYING; // TODO: null state?
        this.goingToLandingSpot = false;
        this.landingSpot = null;
        this.reachedGoal = false;
        this.goal = null;
        this.takeoffStallTicks = 0;
        this.standingTakeoffs = 0;
        this.walkOffAttempts = 0;
        this.takeoffAirborneTicks = 0;
        this.walkOffImpossible = false;
        this.takeoffBoostPending = false;
        this.takeoffOpenedTicksAgo = -1;
        this.lavaPathRequested = false;
        this.pillared = false;
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
            if (this.state == State.LOCATE_JUMP || this.state == State.GET_TO_JUMP || this.state == State.PILLAR_UP) {
                // PILLAR_UP included: a failed calculation to the pillar goal falls back to
                // standingTakeoff() the same way GET_TO_JUMP's own walking failures already do --
                // this.pillared is already true by the time we can get here, so it takes off from
                // wherever the climb got to rather than starting a second pillar.
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
                    behavior.pathManager.pathToDestination(from).whenComplete((result, ex) -> {
                        if (ex == null) {
                            this.state = State.GET_TO_JUMP;
                            return;
                        }
                        onLostControl();
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
                // Climbed high enough: back to standingTakeoff() to actually take off from here.
                // this.pillared is already true, so this pass through can't start a second pillar.
                baritone.getPathingBehavior().secretInternalSegmentCancel();
                this.goal = null;
                return standingTakeoff();
            }

            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor != null || baritone.getPathingBehavior().getInProgress().isPresent()) {
                this.takeoffStallTicks = 0;
            } else if (++this.takeoffStallTicks > TAKEOFF_STALL_TICKS) {
                // Stalled climbing (ran out of blocks mid-pillar, obstruction) -- give up on the
                // pillar and let standingTakeoff() fall through to its ordinary abort messages
                // rather than sitting here forever.
                this.goal = null;
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
     * Takes off from where we are standing: jump, open the elytra on the way up, and light a firework the moment
     * it opens, since a glide that starts with no speed a block and a half off the floor only ends one way. This
     * is what flat terrain leaves us with, where {@link WalkOffCalculationContext} has no ledge to offer and the
     * alternative is standing around telling the user to go find a cliff.
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
        // Ceiling and fireworks first, pillar second, the standing-takeoffs cap third. pillarHeight
        // deliberately doesn't test headroom itself -- its own javadoc says the ceiling check
        // already refuses anything under 2 blocks -- which is only true if that check has actually
        // run by the time pillarHeight is called. Checking it here first, before ever attempting a
        // pillar, is what makes that true: a spot with a genuinely low ceiling (open on every side,
        // just short) aborts right here with its ordinary message instead of pillaring up blindly,
        // possibly straight into solid ground above with allowBreak on. Fireworks first for the
        // same reason: a pillar is wasted effort if there's nothing to jump with once it's done.
        // The cap stays after: a pillar that gets us out of a walled-in spot is worth resetting
        // that counter for, so it has to run before the cap could give up on a spot a few blocks
        // of climbing would have fixed.
        if (!MovementHelper.fullyPassable(ctx, feet.above(2)) || !MovementHelper.fullyPassable(ctx, feet.above(3))) {
            return abortTakeoff("There is no spot to jump off from, and not enough room above me to jump and open the elytra. ");
        }
        if (!this.behavior.selectFirework()) {
            return abortTakeoff("There is no spot to jump off from, and no fireworks in my hotbar to take off from here with. ");
        }

        // One pillar per takeoff sequence -- this.pillared only clears in onLostControl -- so a
        // spot that's still bad after climbing falls through to the cap and the ordinary abort
        // messages below instead of pillaring over and over.
        if (Baritone.settings().elytraTakeoffPillar.value && !this.pillared) {
            if (!baritone.getInventoryBehavior().hasGenericThrowaway()) {
                logDebug("elytraTakeoffPillar is on but there's no throwaway block in the hotbar to pillar with");
            } else {
                int h = pillarHeight(feet);
                if (h == 0 && this.standingTakeoffs >= 1) {
                    // Landed right back here once already at "already open" -- the ring test
                    // passed but the takeoff still failed some other way a few blocks of height
                    // might still fix (a bad angle for the takeoff exit search, a corner ringOpen's
                    // 4-neighbour test missed). Worth one climb before accepting the cap. Ceiling and
                    // fireworks are already known good at this point, so this isn't chasing the low-
                    // ceiling case the ceiling check above already ruled out.
                    h = Math.min(3, Baritone.settings().elytraTakeoffPillarMaxHeight.value);
                }
                if (h > 0) {
                    this.pillared = true;
                    this.pillarTargetY = feet.y + h;
                    this.standingTakeoffs = 0;
                    this.goal = new GoalBlock(feet.x, feet.y + h, feet.z);
                    this.state = State.PILLAR_UP;
                    this.takeoffStallTicks = 0;
                    logDirect("Walled in, pillaring up " + h + " blocks before taking off.");
                    return new PathingCommand(this.goal, PathingCommandType.SET_GOAL_AND_PATH);
                }
            }
        }

        if (this.standingTakeoffs >= MAX_STANDING_TAKEOFFS) {
            return abortTakeoff("Took off from here " + this.standingTakeoffs + " times and ended up back on the ground every time. ");
        }
        if (this.standingTakeoffs++ == 0) {
            logDirect("No spot to jump off from, taking off from here instead.");
        }
        // The path starts from the first clear cube straight above us, which is where a climb on the takeoff
        // rocket comes out. In a basalt delta crevice or the hollow beside a lava pond the walls are a couple of
        // blocks away on every side, and up is the one direction that is reliably clear. Left to itself the
        // pathfinder would start from the nearest clear cube to our feet, and its search for one ignores walls:
        // from a hole that is very often a cube on the far side of one, which nothing here could ever reach.
        final BetterBlockPos exit = takeoffExit(feet);
        // the elytra path has to exist before we leave the ground: the behavior does nothing without one, and the
        // handful of ticks between opening the elytra and hitting the ground is no time to compute one
        this.state = State.PAUSE;
        this.behavior.pathManager.pathToDestination(exit != null ? exit : feet).whenComplete((result, ex) -> {
            if (ex == null) {
                this.state = State.TAKEOFF_JUMP;
                return;
            }
            onLostControl();
        });
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
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
            final BetterBlockPos feet = ctx.playerFeet();
            final BetterBlockPos exit = takeoffExit(feet);
            this.behavior.pathManager.pathToDestination(exit != null ? exit : feet);
        }
        if (!ctx.player().onGround() && openElytra()) {
            this.state = State.START_FLYING;
            this.takeoffStallTicks = 0;
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * The centre of the first 4x4x4 cube of air straight above {@code feet}, aligned the way the elytra
     * pathfinder aligns its nodes, or {@code null} if there is none within {@link #TAKEOFF_MAX_EXIT_HEIGHT}.
     */
    private BetterBlockPos takeoffExit(BetterBlockPos feet) {
        // the pathfinder is nether only and its world stops at the roof
        final int limit = Math.min(feet.y + TAKEOFF_MAX_EXIT_HEIGHT, 128 - 4);
        final int ox = feet.x & ~3;
        final int oz = feet.z & ~3;
        final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        cubes:
        for (int oy = feet.y & ~3; oy <= limit; oy += 4) {
            for (int x = ox; x < ox + 4; x++) {
                for (int y = oy; y < oy + 4; y++) {
                    for (int z = oz; z < oz + 4; z++) {
                        if (!ctx.world().getBlockState(mut.set(x, y, z)).isAir()) {
                            continue cubes;
                        }
                    }
                }
            }
            return new BetterBlockPos(ox + 2, oy + 2, oz + 2);
        }
        return null;
    }

    /**
     * How many blocks {@code feet} would have to pillar up before it isn't walled in any more --
     * the first height at which the 4 cardinal neighbours of that level and the one above it
     * (room enough to stand and to open an elytra into) are open on at least 3 of those 4 sides,
     * {@code 0} if it already qualifies, {@code -1} if no such height turns up within
     * {@link Settings#elytraTakeoffPillarMaxHeight}. Tolerating one blocked side rather than
     * demanding all 4: a single bump or a lone column a block or two away on otherwise flat, open
     * ground would otherwise read as "walled in" and pillar for nothing. Ceiling headroom is
     * deliberately not tested here: {@link #standingTakeoff} already refuses a spot with less
     * than 2 blocks above the feet before this is ever called, and pillaring can only make that
     * worse, never better.
     */
    private int pillarHeight(BetterBlockPos feet) {
        final int max = Baritone.settings().elytraTakeoffPillarMaxHeight.value;
        for (int h = 0; h <= max; h++) {
            if (ringOpen(feet.above(h)) && ringOpen(feet.above(h + 1))) {
                return h;
            }
        }
        return -1;
    }

    /** At least 3 of the 4 cardinal neighbours of {@code at} are open -- see {@link #pillarHeight}. */
    private boolean ringOpen(BlockPos at) {
        int blocked = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (!MovementHelper.fullyPassable(ctx, at.relative(dir))) {
                blocked++;
            }
        }
        return blocked < 2;
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
