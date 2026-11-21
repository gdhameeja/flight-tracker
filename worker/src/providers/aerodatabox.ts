// AeroDataBox (through RapidAPI). Free plan: 400 units a month, so callers
// check the budget first (see budget.ts). Every response reports how many
// units are left, which we store.

import type { Airport, Flight, FlightEnd, FlightStatus } from "../model";

const HOST = "aerodatabox.p.rapidapi.com";

export interface AdbResult {
  flights: Flight[];
  unitsRemaining: number | null;
}

export class ProviderError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

/** All legs flying under this number on this (local departure) date. */
export async function fetchFlight(key: string, number: string, date: string): Promise<AdbResult> {
  const num = encodeURIComponent(number.replace(/\s+/g, ""));
  const url = `https://${HOST}/flights/number/${num}/${date}?withAircraftImage=false&withLocation=false&dateLocalRole=Departure`;
  const res = await fetch(url, { headers: { "X-RapidAPI-Key": key, "X-RapidAPI-Host": HOST } });
  const remaining = res.headers.get("x-ratelimit-api-units-remaining");
  const unitsRemaining = remaining === null ? null : Number(remaining);
  // 204: no flight with that number on that date
  if (res.status === 204 || res.status === 404) return { flights: [], unitsRemaining };
  if (!res.ok) {
    const text = await res.text();
    throw new ProviderError(`AeroDataBox ${res.status}: ${text.slice(0, 200)}`, res.status);
  }
  const raw = (await res.json()) as unknown[];
  const fetchedAt = new Date().toISOString();
  return { flights: raw.map((r) => normalize(r as AdbFlight, date, fetchedAt)), unitsRemaining };
}

// ── Shapes we read from AeroDataBox (only the fields we use) ─────────────────

interface AdbTime {
  utc?: string;
  local?: string;
}
interface AdbAirport {
  icao?: string;
  iata?: string;
  name?: string;
  shortName?: string;
  municipalityName?: string;
  countryCode?: string;
  timeZone?: string;
  location?: { lat?: number; lon?: number };
}
interface AdbEnd {
  airport?: AdbAirport;
  scheduledTime?: AdbTime;
  revisedTime?: AdbTime;
  predictedTime?: AdbTime;
  runwayTime?: AdbTime;
  terminal?: string;
  gate?: string;
  checkInDesk?: string;
  baggageBelt?: string;
}
export interface AdbFlight {
  number?: string;
  status?: string;
  departure?: AdbEnd;
  arrival?: AdbEnd;
  aircraft?: { reg?: string; model?: string };
  airline?: { name?: string; iata?: string; icao?: string };
}

// ── Normalising ─────────────────────────────────────────────────────────────

/** "2026-11-21 00:23Z" → "2026-11-21T00:23:00.000Z" */
export function isoUtc(t: AdbTime | undefined): string | null {
  if (!t?.utc) return null;
  const ms = Date.parse(t.utc.replace(" ", "T"));
  return Number.isNaN(ms) ? null : new Date(ms).toISOString();
}

const STATUS: Record<string, FlightStatus> = {
  expected: "scheduled",
  unknown: "unknown",
  checkin: "check_in",
  boarding: "boarding",
  gateclosed: "gate_closed",
  departed: "departed",
  enroute: "en_route",
  approaching: "approaching",
  delayed: "delayed",
  arrived: "arrived",
  canceled: "cancelled",
  canceleduncertain: "cancelled",
  diverted: "diverted",
};

export function mapStatus(s: string | undefined): FlightStatus {
  return STATUS[(s ?? "").toLowerCase()] ?? "unknown";
}

function airport(a: AdbAirport | undefined): Airport {
  return {
    iata: a?.iata ?? null,
    icao: a?.icao ?? null,
    name: a?.shortName ?? a?.name ?? null,
    city: a?.municipalityName ?? a?.name ?? null,
    countryCode: a?.countryCode?.toUpperCase() ?? null,
    timeZone: a?.timeZone ?? null,
    lat: a?.location?.lat ?? null,
    lon: a?.location?.lon ?? null,
  };
}

function end(e: AdbEnd | undefined, happened: boolean): FlightEnd {
  const scheduled = isoUtc(e?.scheduledTime);
  const revised = isoUtc(e?.revisedTime) ?? isoUtc(e?.predictedTime);
  // Once it has departed/arrived, the revised (or runway) time is the actual time
  const actual = happened ? (isoUtc(e?.runwayTime) ?? revised) : null;
  return {
    airport: airport(e?.airport),
    scheduled,
    estimated: revised,
    actual,
    terminal: e?.terminal ?? null,
    gate: e?.gate ?? null,
    checkInDesk: e?.checkInDesk ?? null,
    baggageBelt: e?.baggageBelt ?? null,
  };
}

const DEPARTED: FlightStatus[] = ["departed", "en_route", "approaching", "arrived", "diverted"];

export function normalize(r: AdbFlight, date: string, fetchedAt: string): Flight {
  const status = mapStatus(r.status);
  return {
    number: (r.number ?? "").trim(),
    date,
    airline: { name: r.airline?.name ?? null, iata: r.airline?.iata ?? null, icao: r.airline?.icao ?? null },
    status,
    departure: end(r.departure, DEPARTED.includes(status)),
    arrival: end(r.arrival, status === "arrived"),
    aircraft: { model: r.aircraft?.model ?? null, reg: r.aircraft?.reg ?? null },
    source: "aerodatabox",
    fetchedAt,
  };
}

/** Departures from an airport between two local times (at most 12 hours apart). 2 units. */
export async function fetchDepartures(key: string, iata: string, fromLocal: string, toLocal: string): Promise<AdbResult> {
  const url = `https://${HOST}/flights/airports/iata/${iata}/${fromLocal}/${toLocal}?direction=Departure&withLeg=true&withCancelled=true&withCodeshared=false&withCargo=false&withPrivate=false`;
  const res = await fetch(url, { headers: { "X-RapidAPI-Key": key, "X-RapidAPI-Host": HOST } });
  const remaining = res.headers.get("x-ratelimit-api-units-remaining");
  const unitsRemaining = remaining === null ? null : Number(remaining);
  if (res.status === 204) return { flights: [], unitsRemaining };
  if (!res.ok) throw new ProviderError(`AeroDataBox ${res.status}: ${(await res.text()).slice(0, 200)}`, res.status);
  const raw = (await res.json()) as { departures?: AdbFlight[] };
  const fetchedAt = new Date().toISOString();
  return { flights: (raw.departures ?? []).map((r) => normalize(r, fromLocal.slice(0, 10), fetchedAt)), unitsRemaining };
}

export interface AircraftInfo {
  reg: string;
  model: string | null;
  typeName: string | null;
  airline: string | null;
  ageYears: number | null;
  firstFlight: string | null;
  seats: number | null;
  engines: string | null;
}

/** An aircraft by registration (age, type, seats). 1 unit; callers cache it. */
export async function fetchAircraft(key: string, reg: string): Promise<{ aircraft: AircraftInfo | null; unitsRemaining: number | null }> {
  const res = await fetch(`https://${HOST}/aircrafts/reg/${encodeURIComponent(reg)}`, { headers: { "X-RapidAPI-Key": key, "X-RapidAPI-Host": HOST } });
  const remaining = res.headers.get("x-ratelimit-api-units-remaining");
  const unitsRemaining = remaining === null ? null : Number(remaining);
  if (res.status === 204 || res.status === 404) return { aircraft: null, unitsRemaining };
  if (!res.ok) throw new ProviderError(`AeroDataBox ${res.status}: ${(await res.text()).slice(0, 200)}`, res.status);
  const a = (await res.json()) as Record<string, any>;
  return {
    aircraft: {
      reg: a.reg ?? reg,
      model: a.modelCode ?? a.model ?? null,
      typeName: a.typeName ?? a.productionLine ?? null,
      airline: a.airlineName ?? null,
      ageYears: typeof a.ageYears === "number" ? a.ageYears : null,
      firstFlight: a.firstFlightDate ?? a.deliveryDate ?? null,
      seats: typeof a.numSeats === "number" ? a.numSeats : null,
      engines: a.engineType ?? (a.numEngines ? `${a.numEngines} engines` : null),
    },
    unitsRemaining,
  };
}
