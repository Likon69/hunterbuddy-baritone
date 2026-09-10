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

package baritone.process.elytra;

import baritone.Baritone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The elytra's flight record: one line per decision or event, into the game log and never into chat, every one
 * starting with {@code [flight]} so that a whole flight comes out of latest.log with one search.
 * <p>
 * It is for reading a failure back after the fact: which rung of the takeoff ladder was chosen and on what
 * numbers, where a flight path was asked to start and where it really starts, every rocket, every stretch the
 * solver had no pitch for, every fall, and how every flight ended. The chat log only ever hinted at those, and
 * some of it never reached chat at all - the takeoff rocket, for one, was lit without a word.
 * <p>
 * Safe from any thread: the logger is, and nothing here touches the world.
 */
public final class FlightLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("Baritone");

    private FlightLog() {}

    /** Whether lines are being written, for callers whose message costs something to put together. */
    public static boolean enabled() {
        return Baritone.settings().elytraFlightLog.value;
    }

    public static void log(String message) {
        if (enabled()) {
            LOGGER.info("[flight] {}", message);
        }
    }
}
