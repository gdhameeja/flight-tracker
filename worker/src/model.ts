// The flight as the app sees it, whichever data provider it came from.
// Times are ISO-8601 UTC; each airport carries its time zone, so the app
// shows local times at each end.

export type FlightStatus =
  | "scheduled"
  | "delayed"
  | "check_in"
  | "boarding"
  | "gate_closed"
  | "departed"
  | "en_route"
  | "approaching"
  | "arrived"
  | "cancelled"
  | "diverted"
  | "unknown";

export interface Airport {
  iata: string | null;
  icao: string | null;
  name: string | null;
  city: string | null;
  countryCode: string | null;
  timeZone: string | null;
  lat: number | null;
  lon: number | null;
}

export interface FlightEnd {
  airport: Airport;
  scheduled: string | null; // ISO UTC
  estimated: string | null; // latest revised or predicted time, ISO UTC
  actual: string | null; // runway or gate time once it happened, ISO UTC
  terminal: string | null;
  gate: string | null;
  checkInDesk: string | null; // departures only
  baggageBelt: string | null; // arrivals only
}

export interface Flight {
  number: string; // "6E 1406"
  date: string; // departure date, local to the departure airport (YYYY-MM-DD)
  airline: { name: string | null; iata: string | null; icao: string | null };
  status: FlightStatus;
  departure: FlightEnd;
  arrival: FlightEnd;
  aircraft: { model: string | null; reg: string | null };
  source: string; // "aerodatabox"
  fetchedAt: string; // ISO UTC
}

export interface FlightEvent {
  kind: string; // "gate", "terminal", "delay", "status", "belt", "cancelled", ...
  title: string;
  body: string;
}

/** Best-known departure time: actual, then estimated, then scheduled. */
export function bestDeparture(f: Flight): string | null {
  return f.departure.actual ?? f.departure.estimated ?? f.departure.scheduled;
}

export function bestArrival(f: Flight): string | null {
  return f.arrival.actual ?? f.arrival.estimated ?? f.arrival.scheduled;
}

/** Minutes late against the schedule (negative = early), or null. */
export function delayMinutes(end: FlightEnd): number | null {
  const best = end.actual ?? end.estimated;
  if (!best || !end.scheduled) return null;
  return Math.round((Date.parse(best) - Date.parse(end.scheduled)) / 60000);
}

export function isFinished(f: Flight): boolean {
  return f.status === "arrived" || f.status === "cancelled" || f.status === "diverted";
}
