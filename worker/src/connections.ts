// Connection risk: does the layover leave enough time for what you must do
// between the two flights? Every step is listed so the app can show why.
// Times are typical values, not guarantees; they lean cautious.

import { bestArrival, bestDeparture, type Flight, isFinished } from "./model";

export interface ConnFlight {
  id: string;
  flight: Flight;
  pnr: string | null;
}

export type ConnLevel = "comfortable" | "tight" | "at_risk" | "long";

export interface Step {
  label: string;
  minutes: number;
}

export interface Connection {
  fromId: string;
  toId: string;
  airport: string; // where you change, e.g. "DEL" (or "BKK → DMK")
  layoverMin: number;
  neededMin: number;
  slackMin: number;
  level: ConnLevel;
  selfTransfer: boolean;
  steps: Step[];
  notes: string[];
}

export interface ConnSettings {
  /** Do you usually check a bag? Self-transfers need the bag collected and checked in again. */
  checkedBags: boolean;
}

/** Longest gap that still counts as a connection, not a stay. */
const MAX_LAYOVER_MIN = 24 * 60;

// Changing terminal, in minutes, including waiting for the shuttle. Cautious.
const TERMINAL_MOVES: Record<string, Record<string, number>> = {
  DEL: { "1-2": 45, "1-3": 45, "2-3": 15 }, // T1 is a shuttle ride away; T2 and T3 are next door
  BOM: { "1-2": 45 },
  BLR: { "1-2": 15 },
  DXB: { "1-3": 30, "2-3": 45, "1-2": 45 },
  KUL: { "1-2": 20 },
  DMK: { "1-2": 10 },
  MAA: { "1-4": 10 },
  SIN: { "1-2": 15, "1-3": 15, "2-3": 15, "1-4": 25, "2-4": 25, "3-4": 25 },
};
const UNKNOWN_TERMINAL_MOVE = 30;

// Different airports in the same city: a self-transfer by road, in minutes
const CITY_PAIRS: Record<string, number> = {
  "BKK-DMK": 75,
  "DXB-DWC": 45,
  "KUL-SZB": 45,
  "BOM-NMI": 90,
};

// Immigration on arrival, minutes. Busy airports in the region are slow.
const IMMIGRATION_IN: Record<string, number> = { IN: 35, AE: 20, TH: 45, ID: 45, MY: 30, VN: 40, SG: 20, LK: 30, NP: 40 };
const IMMIGRATION_DEFAULT = 35;

// Minimum connection time on one booking (bags go through), minutes
const MCT = { dd: 45, ii: 60, mixed: 75 };
const MCT_AIRPORT: Record<string, Partial<typeof MCT>> = { DEL: { ii: 75, mixed: 90 }, BOM: { ii: 75, mixed: 90 }, DXB: { ii: 75 } };

// Check-in counters close this long before departure (domestic, international)
const COUNTER_CLOSE: Record<string, [number, number]> = {
  "6E": [45, 60], AI: [45, 60], IX: [45, 60], QP: [45, 60], SG: [45, 60],
  EK: [60, 60], FZ: [60, 60], G9: [60, 60], EY: [60, 60], QR: [60, 60],
  AK: [60, 60], FD: [60, 60], D7: [60, 60], QZ: [60, 60], Z2: [60, 60],
  VJ: [40, 60], VN: [40, 60], TG: [45, 60], PG: [40, 60], SQ: [60, 60], TR: [60, 60], MH: [45, 60],
};
const COUNTER_DEFAULT: [number, number] = [45, 60];

export function findConnections(list: ConnFlight[], s: ConnSettings): Connection[] {
  const live = list
    .filter((c) => c.flight.status !== "cancelled" && bestDeparture(c.flight) && bestArrival(c.flight))
    .sort((a, b) => Date.parse(bestDeparture(a.flight)!) - Date.parse(bestDeparture(b.flight)!));
  const out: Connection[] = [];
  for (let i = 0; i < live.length - 1; i++) {
    const a = live[i];
    const b = live[i + 1];
    // Both over: nothing to watch
    if (isFinished(a.flight) && ["departed", "en_route", "approaching", "arrived"].includes(b.flight.status)) continue;
    const c = connect(a, b, s);
    if (c) out.push(c);
  }
  return out;
}

export function connect(a: ConnFlight, b: ConnFlight, s: ConnSettings): Connection | null {
  const arrAp = a.flight.arrival.airport;
  const depAp = b.flight.departure.airport;
  const sameAirport = arrAp.iata === depAp.iata;
  const cityMove = sameAirport ? 0 : CITY_PAIRS[[arrAp.iata, depAp.iata].sort().join("-")];
  if (!sameAirport && !cityMove) return null;

  const layoverMin = Math.round((Date.parse(bestDeparture(b.flight)!) - Date.parse(bestArrival(a.flight)!)) / 60_000);
  if (layoverMin > MAX_LAYOVER_MIN || layoverMin < -60) return null;

  const country = arrAp.countryCode ?? "";
  const intlIn = (a.flight.departure.airport.countryCode ?? "") !== country;
  const intlOut = (b.flight.arrival.airport.countryCode ?? "") !== (depAp.countryCode ?? "");
  const sameBooking = !!a.pnr && a.pnr === b.pnr && sameAirport;
  const steps: Step[] = [];
  const notes: string[] = [];
  const code = arrAp.iata ?? "?";
  const t1 = a.flight.arrival.terminal;
  const t2 = b.flight.departure.terminal;

  const terminalStep = () => {
    if (!sameAirport) {
      steps.push({ label: `Get from ${arrAp.iata} to ${depAp.iata} by road`, minutes: cityMove });
      return;
    }
    if (t1 && t2 && t1 !== t2) {
      const key = [t1, t2].sort().join("-");
      const min = TERMINAL_MOVES[code]?.[key] ?? UNKNOWN_TERMINAL_MOVE;
      steps.push({ label: `Terminal ${t1} → Terminal ${t2}`, minutes: min });
      if (code === "DEL" && key.includes("1")) notes.push("Delhi T1 is a separate building: take the free inter-terminal shuttle bus.");
      if (code === "BOM") notes.push("Mumbai T1 and T2 are on opposite sides of the runway: use the transfer coach.");
    } else if (!t1 || !t2) {
      notes.push("Terminals aren't confirmed yet; this assumes no terminal change.");
    }
  };

  // India: arriving from abroad and flying on within India means bags and customs at the first airport
  const indiaFirstEntry = country === "IN" && intlIn && !intlOut;

  if (sameBooking && !indiaFirstEntry) {
    const kind = !intlIn && !intlOut ? "dd" : intlIn && intlOut ? "ii" : "mixed";
    const mct = MCT_AIRPORT[code]?.[kind] ?? MCT[kind];
    steps.push({ label: `Minimum connection time at ${code} (one booking, bags go through)`, minutes: mct });
    if (t1 && t2 && t1 !== t2) terminalStep();
  } else {
    steps.push({ label: "Get off the plane", minutes: 15 });
    if (intlIn) steps.push({ label: `Immigration on arrival (${countryName(country)})`, minutes: IMMIGRATION_IN[country] ?? IMMIGRATION_DEFAULT });
    if (indiaFirstEntry) {
      steps.push({ label: "Collect bags and clear customs (first airport in India)", minutes: 30 });
      steps.push({ label: "Re-check bags at the transfer desk, then security", minutes: 35 });
      terminalStep();
      notes.push("Flying into India and on to a domestic flight, you collect your bags and clear customs at the first Indian airport, even on one ticket.");
      steps.push({ label: "Boarding closes before departure", minutes: 25 });
    } else {
      // Self-transfer: out through arrivals, then check in again as a new passenger
      if (s.checkedBags) steps.push({ label: "Collect your bag", minutes: 25 });
      terminalStep();
      const [dom, intl] = COUNTER_CLOSE[b.flight.airline.iata ?? ""] ?? COUNTER_DEFAULT;
      if (s.checkedBags) {
        steps.push({ label: `${airlineName(b)} check-in closes before departure`, minutes: intlOut ? intl : dom });
        notes.push("Separate bookings: if the first flight is late, the second airline doesn't have to wait or rebook you.");
      } else {
        steps.push({ label: "Security" + (intlOut ? " and emigration" : ""), minutes: intlOut ? 40 : 25 });
        steps.push({ label: "Boarding closes before departure", minutes: 20 });
        notes.push("Separate bookings, cabin bag only: check in online for the second flight before you land.");
      }
    }
  }

  const neededMin = steps.reduce((n, st) => n + st.minutes, 0);
  const slackMin = layoverMin - neededMin;
  const level: ConnLevel = slackMin < 0 ? "at_risk" : slackMin < 30 ? "tight" : layoverMin >= 6 * 60 ? "long" : "comfortable";
  if (level === "long" && layoverMin >= 8 * 60) notes.push("Long layover: worth checking for an airport hotel or lounge.");
  return {
    fromId: a.id,
    toId: b.id,
    airport: sameAirport ? code : `${arrAp.iata} → ${depAp.iata}`,
    layoverMin,
    neededMin,
    slackMin,
    level,
    selfTransfer: !sameBooking,
    steps,
    notes,
  };
}

const LEVEL_RANK: Record<ConnLevel, number> = { long: 0, comfortable: 0, tight: 1, at_risk: 2 };

/** An alert when a connection gets worse than it was. */
export function worsened(prev: ConnLevel | null, next: ConnLevel): boolean {
  return LEVEL_RANK[next] > LEVEL_RANK[prev ?? "comfortable"];
}

export function fmtMin(m: number): string {
  const a = Math.abs(m);
  const s = a < 60 ? `${a} min` : a % 60 ? `${Math.floor(a / 60)} h ${a % 60} min` : `${a / 60} h`;
  return m < 0 ? `-${s}` : s;
}

function airlineName(c: ConnFlight): string {
  return c.flight.airline.name ?? c.flight.airline.iata ?? "The airline";
}

function countryName(cc: string): string {
  return ({ IN: "India", AE: "UAE", TH: "Thailand", ID: "Indonesia", MY: "Malaysia", VN: "Vietnam", SG: "Singapore", LK: "Sri Lanka", NP: "Nepal" } as Record<string, string>)[cc] ?? cc;
}
