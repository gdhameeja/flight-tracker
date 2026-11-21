// When to check a flight next. The free AeroDataBox plan has 400 units a
// month (2 per check), so checks bunch up where changes happen: the hours
// before departure, and around landing for the belt. About 20 checks a flight.

import { bestArrival, bestDeparture, type Flight, isFinished } from "./model";

const MIN = 60_000;
const HOUR = 60 * MIN;

/** Epoch ms of the next check, or null when there's nothing left to watch. */
export function nextCheckAt(f: Flight, now: number): number | null {
  const dep = Date.parse(bestDeparture(f) ?? "");
  const arr = Date.parse(bestArrival(f) ?? "");
  if (Number.isNaN(dep)) return now + 6 * HOUR;

  if (isFinished(f)) {
    // Arrived: one more look for the baggage belt if we don't have it yet
    if (f.status === "arrived" && !f.arrival.baggageBelt && now - arr < 45 * MIN) return now + 15 * MIN;
    return null;
  }

  const untilDep = dep - now;
  if (untilDep > 48 * HOUR) return dep - 48 * HOUR; // first look two days out
  if (untilDep > 24 * HOUR) return now + 12 * HOUR;
  if (untilDep > 6 * HOUR) return now + 3 * HOUR;
  if (untilDep > 2 * HOUR) return now + HOUR;
  if (untilDep > -30 * MIN && !hasDeparted(f)) return now + 20 * MIN; // gate, boarding, delays

  // In the air (or should be)
  if (!Number.isNaN(arr)) {
    const untilArr = arr - now;
    if (untilArr > 90 * MIN) return Math.min(now + 2 * HOUR, arr - 45 * MIN);
    if (untilArr > -2 * HOUR) return now + 15 * MIN; // landing and the belt
  }
  // Long past arrival without an "arrived" status: give up after 6 hours
  return now - (Number.isNaN(arr) ? dep : arr) > 6 * HOUR ? null : now + HOUR;
}

function hasDeparted(f: Flight): boolean {
  return ["departed", "en_route", "approaching"].includes(f.status) || f.departure.actual !== null;
}
