// Everything built on top of the tracked flights: connections, reminders,
// weather alerts, alternatives when cancelled, aircraft details.
// The scheduled part (runAlerts) only reads the database and free weather
// data; AeroDataBox units are spent only when you open something in the app.

import { type Connection, type ConnFlight, findConnections, fmtMin, worsened } from "./connections";
import { bestArrival, bestDeparture, type Flight, isFinished } from "./model";
import { type AircraftInfo, fetchAircraft, fetchDepartures } from "./providers/aerodatabox";
import { AIRLINE_SITES, checkinOpensAt, isInternational, reminders, beAtAirportMin } from "./reminders";
import { getMeta, getSettings, HttpError, localDayTime, localTime, recordUnits, requireUnits, setMeta } from "./util";
import { type AirportWeather, airportWeather } from "./weather";

type Env = { DB: D1Database; AERODATABOX_KEY: string };

interface Row {
  id: string;
  data: string;
  next_check_at: number | null;
  pnr: string | null;
}

type Active = ConnFlight & { tracking: boolean };

/** Upcoming and recent flights with their booking reference. */
async function activeFlights(env: Env): Promise<Active[]> {
  const since = new Date(Date.now() - 2 * 86_400_000).toISOString().slice(0, 10);
  const rows = (await env.DB.prepare("select f.id, f.data, f.next_check_at, b.pnr from flights f left join bookings b on b.flight_id = f.id where f.date >= ?").bind(since).all<Row>()).results;
  return rows.map((r) => ({ id: r.id, flight: JSON.parse(r.data) as Flight, pnr: r.pnr, tracking: r.next_check_at !== null }));
}

export async function connections(env: Env): Promise<Connection[]> {
  return findConnections(await activeFlights(env), await getSettings(env));
}

/** Extra per-flight info the app shows: check-in, when to be at the airport. */
export function extras(f: Flight, checkedBags: boolean) {
  const dep = bestDeparture(f);
  const opens = checkinOpensAt(f);
  return {
    checkinUrl: AIRLINE_SITES[f.airline.iata ?? ""] ?? null,
    checkinOpensAt: opens ? new Date(opens).toISOString() : null,
    beAtAirportBy: dep ? new Date(Date.parse(dep) - beAtAirportMin(f, checkedBags) * 60_000).toISOString() : null,
    international: isInternational(f),
  };
}

// ── Scheduled: reminders, connection and weather alerts ─────────────────────

async function addEventOnce(env: Env, flightId: string, kind: string, title: string, body: string): Promise<boolean> {
  const seen = await env.DB.prepare("select 1 from events where flight_id = ? and kind = ?").bind(flightId, kind).first();
  if (seen) return false;
  await env.DB.prepare("insert into events (flight_id, at, kind, title, body) values (?, ?, ?, ?, ?)").bind(flightId, Date.now(), kind, title, body).run();
  return true;
}

export async function runAlerts(env: Env) {
  const now = Date.now();
  const settings = await getSettings(env);
  const list = await activeFlights(env);

  // Reminders: due now, and not hours stale (a flight imported late gets no "check-in opened 2 days ago")
  for (const c of list) {
    if (!c.tracking) continue;
    for (const r of reminders(c.flight, settings.checkedBags, (iso) => localDayTime(iso, c.flight.departure.airport.timeZone))) {
      if (r.at <= now && now - r.at < 6 * 3600_000) await addEventOnce(env, c.id, r.kind, r.title, r.body);
    }
  }

  // Connections: alert when one gets worse
  for (const conn of findConnections(list, settings)) {
    const key = `conn:${conn.fromId}:${conn.toId}`;
    const prev = (await getMeta(env, key)) as Connection["level"] | null;
    if (prev !== conn.level) await setMeta(env, key, conn.level);
    if (prev !== null && worsened(prev, conn.level)) {
      const to = list.find((c) => c.id === conn.toId)!.flight;
      const title = conn.level === "at_risk" ? `🚨 Connection at ${conn.airport} at risk` : `⚠️ Connection at ${conn.airport} is now tight`;
      const body =
        conn.slackMin < 0
          ? `${fmtMin(conn.layoverMin)} between flights, about ${fmtMin(conn.neededMin)} needed for ${to.number}. Talk to the airline now about options.`
          : `${fmtMin(conn.layoverMin)} between flights, ${fmtMin(conn.slackMin)} to spare for ${to.number}. Move quickly.`;
      await env.DB.prepare("insert into events (flight_id, at, kind, title, body) values (?, ?, ?, ?, ?)").bind(conn.toId, now, "connection", title, body).run();
    }
  }

  // Weather: once the forecast reaches the flight (about a day ahead), look every 6 hours
  for (const c of list) {
    const f = c.flight;
    const dep = Date.parse(bestDeparture(f) ?? "");
    if (Number.isNaN(dep) || isFinished(f) || dep - now > 26 * 3600_000 || dep < now) continue;
    const key = `wx_checked:${c.id}`;
    if (now - Number((await getMeta(env, key)) ?? 0) < 6 * 3600_000) continue;
    await setMeta(env, key, String(now));
    const w = await weatherFor(f);
    for (const [end, wx, iso] of [
      ["dep", w.departure, bestDeparture(f)],
      ["arr", w.arrival, bestArrival(f)],
    ] as const) {
      if (!wx?.risk || !iso) continue;
      const ap = end === "dep" ? f.departure.airport : f.arrival.airport;
      const when = localTime(iso, ap.timeZone);
      const icon = /fog|mist/i.test(wx.risk.text) ? "🌫️" : /thunder/i.test(wx.risk.text) ? "⛈️" : "💨";
      await addEventOnce(env, c.id, `weather_${end}_${wx.risk.level}`, `${icon} ${f.number}: ${wx.risk.level === "warning" ? "bad" : "watch the"} weather at ${ap.iata}`, `Forecast for ${end === "dep" ? "departure" : "arrival"} at ${when}: ${wx.risk.text}`);
    }
  }
}

// ── On demand (from the app) ────────────────────────────────────────────────

export async function weatherFor(f: Flight): Promise<{ departure: AirportWeather | null; arrival: AirportWeather | null }> {
  const [departure, arrival] = await Promise.all([
    f.departure.airport.icao ? airportWeather(f.departure.airport.icao, bestDeparture(f)) : null,
    f.arrival.airport.icao ? airportWeather(f.arrival.airport.icao, bestArrival(f)) : null,
  ]);
  return { departure, arrival };
}

/** Other flights on the same route in the next 12 hours (when yours is cancelled or very late). 2 units. */
export async function alternatives(env: Env, f: Flight) {
  const dep = f.departure.airport;
  if (!dep.iata || !dep.timeZone) throw new HttpError(400, "Departure airport unknown");
  await requireUnits(env, 6);
  const sched = Date.parse(f.departure.scheduled ?? "") || Date.now();
  const from = Math.max(Date.now(), sched - 3600_000);
  const fmt = (ms: number) => {
    const p = Object.fromEntries(
      new Intl.DateTimeFormat("en-CA", { timeZone: dep.timeZone!, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
        .formatToParts(new Date(ms))
        .map((x) => [x.type, x.value]),
    );
    return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`;
  };
  const { flights, unitsRemaining } = await fetchDepartures(env.AERODATABOX_KEY, dep.iata, fmt(from), fmt(from + 12 * 3600_000 - 60_000));
  await recordUnits(env, unitsRemaining);
  const to = f.arrival.airport.iata;
  return flights
    .filter((x) => x.arrival.airport.iata === to && x.number.replace(/\s/g, "") !== f.number.replace(/\s/g, "") && x.status !== "cancelled")
    .map((x) => ({ number: x.number, airline: x.airline.name, departure: x.departure.estimated ?? x.departure.scheduled, terminal: x.departure.terminal, status: x.status }))
    .sort((a, b) => (a.departure ?? "").localeCompare(b.departure ?? ""));
}

/** Aircraft details, cached for 30 days (1 unit the first time). */
export async function aircraft(env: Env, reg: string, airline: string | null): Promise<(AircraftInfo & { wifi: Wifi }) | null> {
  const key = `aircraft:${reg}`;
  const cached = await getMeta(env, key);
  let info: AircraftInfo | null;
  if (cached && Date.now() - JSON.parse(cached).at < 30 * 86_400_000) info = JSON.parse(cached).info;
  else {
    await requireUnits(env, 6);
    const r = await fetchAircraft(env.AERODATABOX_KEY, reg);
    await recordUnits(env, r.unitsRemaining);
    info = r.aircraft;
    await setMeta(env, key, JSON.stringify({ at: Date.now(), info }));
  }
  return info ? { ...info, wifi: wifiGuess(airline, info.model ?? info.typeName) } : null;
}

// ── Wi-Fi: no free data source, so a guess by airline (and aircraft) ───────

export interface Wifi {
  likely: "yes" | "no" | "some";
  note: string;
}

const WIFI: Record<string, Wifi> = {
  EK: { likely: "yes", note: "Emirates has Wi-Fi on almost every plane; a basic plan is free for Skywards members." },
  EY: { likely: "yes", note: "Etihad has Wi-Fi on most planes (paid)." },
  QR: { likely: "yes", note: "Qatar Airways has Wi-Fi on most planes; free on many routes." },
  SQ: { likely: "yes", note: "Singapore Airlines has free Wi-Fi for KrisFlyer members." },
  FZ: { likely: "some", note: "flydubai has Wi-Fi on most 737 MAX planes (paid)." },
  AI: { likely: "some", note: "Air India has Wi-Fi on its newer and refitted widebodies (A350, some 787/777); not on most narrowbodies." },
  TG: { likely: "some", note: "Thai has Wi-Fi on some widebodies." },
  MH: { likely: "some", note: "Malaysia Airlines has Wi-Fi on A350/A330neo and some 737s." },
  VN: { likely: "some", note: "Vietnam Airlines has Wi-Fi on some widebodies." },
  "6E": { likely: "no", note: "IndiGo doesn't offer Wi-Fi." },
  QP: { likely: "no", note: "Akasa doesn't offer Wi-Fi." },
  IX: { likely: "no", note: "Air India Express doesn't offer Wi-Fi." },
  SG: { likely: "no", note: "SpiceJet doesn't offer Wi-Fi." },
  G9: { likely: "no", note: "Air Arabia doesn't offer Wi-Fi." },
  AK: { likely: "no", note: "AirAsia doesn't offer Wi-Fi." },
  FD: { likely: "no", note: "AirAsia doesn't offer Wi-Fi." },
  D7: { likely: "no", note: "AirAsia doesn't offer Wi-Fi." },
  QZ: { likely: "no", note: "AirAsia doesn't offer Wi-Fi." },
  VJ: { likely: "no", note: "Vietjet doesn't offer Wi-Fi." },
};

export function wifiGuess(airline: string | null, _model: string | null): Wifi {
  return WIFI[airline ?? ""] ?? { likely: "some", note: "Unknown for this airline: check the airline's site." };
}
