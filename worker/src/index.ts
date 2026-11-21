// Flight Tracker backend: keeps your flights up to date on a schedule and
// records what changed. The Android app reads it over HTTPS.
//
// Every /api route except /api/health needs `Authorization: Bearer <APP_TOKEN>`.

import { diff } from "./changes";
import type { Flight, FlightEvent } from "./model";
import { fetchFlight, ProviderError } from "./providers/aerodatabox";
import { sendPush, type ServiceAccount } from "./push";
import { nextCheckAt } from "./schedule";

export interface Env {
  DB: D1Database;
  APP_TOKEN: string;
  AERODATABOX_KEY: string;
  /** Firebase service-account JSON; without it alerts aren't pushed */
  FCM_SERVICE_ACCOUNT?: string;
}

/** Scheduled checks stop when fewer units than this are left; the rest are for refreshing by hand. */
const CRON_RESERVE_UNITS = 40;
/** Refreshing by hand stops here, so a stray loop can't drain the plan. */
const MANUAL_RESERVE_UNITS = 6;
/** A manual refresh within this long of the last check returns the stored copy. */
const MANUAL_MIN_AGE_MS = 3 * 60_000;
/** Flights checked per cron run, at most. */
const CRON_BATCH = 8;

interface FlightRow {
  id: string;
  number: string;
  date: string;
  dep_iata: string | null;
  data: string;
  next_check_at: number | null;
  created_at: number;
  updated_at: number;
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    try {
      return await route(req, env);
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.message }, e.status);
      if (e instanceof ProviderError) return json({ error: e.message }, 502);
      console.error(e);
      return json({ error: "Server error" }, 500);
    }
  },

  async scheduled(_ctrl: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(runDueChecks(env));
  },
} satisfies ExportedHandler<Env>;

// ── Routes ──────────────────────────────────────────────────────────────────

async function route(req: Request, env: Env): Promise<Response> {
  const url = new URL(req.url);
  const path = url.pathname.replace(/\/+$/, "");
  const m = req.method;

  // Public pages Google's OAuth consent screen requires (home page, privacy policy)
  if ((path === "" || path === "/privacy") && m === "GET") return page(path === "/privacy" ? PRIVACY : HOME);
  if (path === "/api/health") return json({ ok: true });
  if (path === "/api/pair/claim" && m === "POST") return claimPairing(req, env);
  authorize(req, env);

  if (path === "/api/pair" && m === "POST") return json(await startPairing(env));
  if (path === "/api/flights" && m === "GET") return json({ flights: await listFlights(env) });
  if (path === "/api/flights" && m === "POST") return addFlight(req, env);
  if (path === "/api/usage" && m === "GET") return json(await usage(env));
  if (path === "/api/push-token" && m === "POST") {
    const { token } = (await req.json().catch(() => ({}))) as { token?: string };
    if (!token) throw new HttpError(400, "token missing");
    await setMeta(env, "fcm_token", token);
    return json({ ok: true, pushEnabled: !!env.FCM_SERVICE_ACCOUNT });
  }

  const one = path.match(/^\/api\/flights\/([\w-]+)(\/refresh)?$/);
  if (one) {
    const [, id, refresh] = one;
    if (refresh && m === "POST") return json(await refreshById(env, id, true));
    if (!refresh && m === "GET") return json(await flightView(env, await getRow(env, id)));
    if (!refresh && m === "DELETE") {
      await env.DB.prepare("delete from events where flight_id = ?").bind(id).run();
      await env.DB.prepare("delete from flights where id = ?").bind(id).run();
      return json({ ok: true });
    }
  }
  throw new HttpError(404, "Not found");
}

function authorize(req: Request, env: Env) {
  const got = req.headers.get("authorization") ?? "";
  if (!env.APP_TOKEN || !timingSafeEqual(got, `Bearer ${env.APP_TOKEN}`)) throw new HttpError(401, "Wrong or missing app token");
}

// ── Pairing: a 6-digit code typed on the phone hands it the app token ───────

const PAIR_TTL_MS = 10 * 60_000;
/** Wrong codes allowed before pairing locks until a new code is made. */
const PAIR_MAX_FAILS = 5;

async function startPairing(env: Env) {
  const n = crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000;
  const code = String(n).padStart(6, "0");
  const value = JSON.stringify({ code, expires: Date.now() + PAIR_TTL_MS, fails: 0 });
  await env.DB.prepare("insert into meta (key, value) values ('pairing', ?) on conflict (key) do update set value = excluded.value").bind(value).run();
  return { code, expiresInMinutes: PAIR_TTL_MS / 60_000 };
}

async function claimPairing(req: Request, env: Env): Promise<Response> {
  const body = (await req.json().catch(() => ({}))) as { code?: string };
  const row = await env.DB.prepare("select value from meta where key = 'pairing'").first<{ value: string }>();
  const p = row ? (JSON.parse(row.value) as { code: string; expires: number; fails: number }) : null;
  if (!p || p.expires < Date.now() || p.fails >= PAIR_MAX_FAILS) throw new HttpError(410, "This code has expired. Ask for a new one.");
  if (!timingSafeEqual(String(body.code ?? "").trim(), p.code)) {
    p.fails++;
    await env.DB.prepare("update meta set value = ? where key = 'pairing'").bind(JSON.stringify(p)).run();
    throw new HttpError(403, p.fails >= PAIR_MAX_FAILS ? "Too many wrong codes. Ask for a new one." : "That code isn't right");
  }
  // One use only
  await env.DB.prepare("delete from meta where key = 'pairing'").run();
  return json({ token: env.APP_TOKEN });
}

// ── Flights ─────────────────────────────────────────────────────────────────

async function listFlights(env: Env) {
  const rows = (await env.DB.prepare("select * from flights").all<FlightRow>()).results;
  const views = await Promise.all(rows.map((r) => flightView(env, r)));
  // Soonest departure first
  return views.sort((a, b) => (a.flight.departure.scheduled ?? "").localeCompare(b.flight.departure.scheduled ?? ""));
}

async function flightView(env: Env, row: FlightRow) {
  const events = (
    await env.DB.prepare("select at, kind, title, body from events where flight_id = ? order by at").bind(row.id).all<{ at: number; kind: string; title: string; body: string }>()
  ).results.map((e) => ({ ...e, at: new Date(e.at).toISOString() }));
  return {
    id: row.id,
    flight: JSON.parse(row.data) as Flight,
    nextCheckAt: row.next_check_at ? new Date(row.next_check_at).toISOString() : null,
    events,
  };
}

async function getRow(env: Env, id: string): Promise<FlightRow> {
  const row = await env.DB.prepare("select * from flights where id = ?").bind(id).first<FlightRow>();
  if (!row) throw new HttpError(404, "No such flight");
  return row;
}

/** Body: { number: "6E 1406", date: "2026-11-21", from?: "DEL" } */
async function addFlight(req: Request, env: Env): Promise<Response> {
  const body = (await req.json().catch(() => ({}))) as { number?: string; date?: string; from?: string };
  const number = (body.number ?? "").toUpperCase().replace(/\s+/g, "");
  const date = body.date ?? "";
  if (!/^[A-Z0-9]{2}\d{1,5}[A-Z]?$/.test(number)) throw new HttpError(400, "Flight number looks wrong. Try something like 6E1406.");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new HttpError(400, "Date must be YYYY-MM-DD");
  await requireUnits(env, MANUAL_RESERVE_UNITS);

  const { flights, unitsRemaining } = await fetchFlight(env.AERODATABOX_KEY, number, date);
  await recordUnits(env, unitsRemaining);
  if (flights.length === 0) throw new HttpError(404, `No flight ${number} found on ${date}`);

  let legs = flights;
  if (body.from) legs = flights.filter((f) => f.departure.airport.iata === body.from!.toUpperCase());
  if (legs.length === 0) throw new HttpError(404, `${number} doesn't leave ${body.from} on ${date}`);
  // One number, several legs (DEL→BOM→BLR): let the app ask which one
  if (legs.length > 1) return json({ choices: legs.map((f) => ({ from: f.departure.airport.iata, to: f.arrival.airport.iata, departure: f.departure.scheduled })) });

  const flight = legs[0];
  const now = Date.now();
  const id = crypto.randomUUID();
  const dep = flight.departure.airport.iata;
  const existing = await env.DB.prepare("select id from flights where number = ? and date = ? and dep_iata is ?").bind(number, date, dep).first<{ id: string }>();
  if (existing) return json(await flightView(env, await getRow(env, existing.id)));

  await env.DB.prepare("insert into flights (id, number, date, dep_iata, data, next_check_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)")
    .bind(id, number, date, dep, JSON.stringify(flight), nextCheckAt(flight, now), now, now)
    .run();
  return json(await flightView(env, await getRow(env, id)), 201);
}

async function refreshById(env: Env, id: string, manual: boolean) {
  const row = await getRow(env, id);
  if (manual && Date.now() - row.updated_at < MANUAL_MIN_AGE_MS) return flightView(env, row);
  await requireUnits(env, manual ? MANUAL_RESERVE_UNITS : CRON_RESERVE_UNITS);
  await refreshRow(env, row);
  await deliverPending(env);
  return flightView(env, await getRow(env, id));
}

/** Look the flight up again, store it, and record what changed. */
async function refreshRow(env: Env, row: FlightRow): Promise<FlightEvent[]> {
  const prev = JSON.parse(row.data) as Flight;
  const { flights, unitsRemaining } = await fetchFlight(env.AERODATABOX_KEY, row.number, row.date);
  await recordUnits(env, unitsRemaining);
  const now = Date.now();
  const next = flights.find((f) => f.departure.airport.iata === row.dep_iata) ?? (flights.length === 1 ? flights[0] : null);
  if (!next) {
    // Provider lost the flight: try again later, keep what we had
    await env.DB.prepare("update flights set next_check_at = ?, updated_at = ? where id = ?").bind(now + 60 * 60_000, now, row.id).run();
    return [];
  }

  const events = diff(prev, next, (iso, end) => localTime(iso, end === "dep" ? next.departure.airport.timeZone : next.arrival.airport.timeZone));
  const stmts = [
    env.DB.prepare("update flights set data = ?, next_check_at = ?, updated_at = ? where id = ?").bind(JSON.stringify(next), nextCheckAt(next, now), now, row.id),
    ...events.map((e) => env.DB.prepare("insert into events (flight_id, at, kind, title, body) values (?, ?, ?, ?, ?)").bind(row.id, now, e.kind, e.title, e.body)),
  ];
  await env.DB.batch(stmts);
  return events;
}

async function runDueChecks(env: Env) {
  const units = await unitsLeft(env);
  if (units !== null && units < CRON_RESERVE_UNITS) {
    console.warn(`Only ${units} AeroDataBox units left: scheduled checks paused`);
    return;
  }
  const due = (await env.DB.prepare("select * from flights where next_check_at is not null and next_check_at <= ? order by next_check_at limit ?").bind(Date.now(), CRON_BATCH).all<FlightRow>()).results;
  for (const row of due) {
    try {
      await refreshRow(env, row);
    } catch (e) {
      console.error(`Checking ${row.number} ${row.date} failed`, e);
      // Don't hammer a failing provider: next try in 30 minutes
      await env.DB.prepare("update flights set next_check_at = ? where id = ?").bind(Date.now() + 30 * 60_000, row.id).run();
    }
  }
  await deliverPending(env);
}

// ── Push alerts ─────────────────────────────────────────────────────────────

/** Pushes alerts not sent yet. Older than 6 hours: too stale to be worth a notification. */
async function deliverPending(env: Env) {
  if (!env.FCM_SERVICE_ACCOUNT) return;
  const device = await getMeta(env, "fcm_token");
  if (!device) return;
  const pending = (
    await env.DB.prepare("select id, flight_id, title, body from events where pushed = 0 and at > ? order by at limit 20")
      .bind(Date.now() - 6 * 3600_000)
      .all<{ id: number; flight_id: string; title: string; body: string }>()
  ).results;
  const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT) as ServiceAccount;
  for (const e of pending) {
    try {
      const ok = await sendPush(sa, device, { title: e.title, body: e.body, flightId: e.flight_id, eventId: e.id });
      await env.DB.prepare("update events set pushed = 1 where id = ?").bind(e.id).run();
      if (!ok) {
        // The app was reinstalled or its token changed; it registers again when opened
        await env.DB.prepare("delete from meta where key = 'fcm_token'").run();
        return;
      }
    } catch (err) {
      console.error("Push failed", err);
      return; // try again on the next run
    }
  }
}

async function getMeta(env: Env, key: string): Promise<string | null> {
  return (await env.DB.prepare("select value from meta where key = ?").bind(key).first<{ value: string }>())?.value ?? null;
}

async function setMeta(env: Env, key: string, value: string) {
  await env.DB.prepare("insert into meta (key, value) values (?, ?) on conflict (key) do update set value = excluded.value").bind(key, value).run();
}

// ── API budget ──────────────────────────────────────────────────────────────

async function unitsLeft(env: Env): Promise<number | null> {
  const r = await env.DB.prepare("select value from meta where key = 'adb_units_remaining'").first<{ value: string }>();
  return r ? Number(r.value) : null;
}

async function recordUnits(env: Env, remaining: number | null) {
  if (remaining === null || Number.isNaN(remaining)) return;
  await env.DB.batch([
    env.DB.prepare("insert into meta (key, value) values ('adb_units_remaining', ?) on conflict (key) do update set value = excluded.value").bind(String(remaining)),
    env.DB.prepare("insert into meta (key, value) values ('adb_units_at', ?) on conflict (key) do update set value = excluded.value").bind(new Date().toISOString()),
  ]);
}

async function requireUnits(env: Env, reserve: number) {
  const left = await unitsLeft(env);
  if (left !== null && left < reserve) throw new HttpError(429, `Only ${left} AeroDataBox units left this month`);
}

async function usage(env: Env) {
  const at = await env.DB.prepare("select value from meta where key = 'adb_units_at'").first<{ value: string }>();
  return { aerodatabox: { unitsRemaining: await unitsLeft(env), checkedAt: at?.value ?? null, monthlyLimit: 400 } };
}

// ── Public pages ────────────────────────────────────────────────────────────

const HOME = `<h1>Flights</h1>
<p>A personal flight tracker for one person's own trips: live status, gates, delays,
baggage belts and alerts. It is not a public service and has no other users.</p>
<p>Source code: <a href="https://github.com/gdhameeja/flight-tracker">github.com/gdhameeja/flight-tracker</a>.
<a href="/privacy">Privacy policy</a>.</p>`;

const PRIVACY = `<h1>Flights: privacy policy</h1>
<p>Flights is a personal app used only by its owner.</p>
<h2>Gmail</h2>
<p>With the owner's permission, Flights reads (read-only) the owner's Gmail to find flight
booking confirmations, and extracts only flight details: flight numbers, dates, routes,
booking references and seats. It never sends, deletes or changes email, and never reads
other messages beyond what's needed to recognise a booking.</p>
<h2>What is stored</h2>
<p>Flight details and a record of which emails were already read, in the owner's own
Cloudflare database. Nothing is sold, shared or used for advertising. Flight numbers and
dates are sent to flight-data providers (AeroDataBox, FlightAware) to look up status.</p>
<h2>Use of Google data</h2>
<p>Flights' use of information received from Google APIs adheres to the
<a href="https://developers.google.com/terms/api-services-user-data-policy">Google API Services User Data Policy</a>,
including the Limited Use requirements.</p>
<h2>Removing access</h2>
<p>Access can be revoked at any time at <a href="https://myaccount.google.com/permissions">myaccount.google.com/permissions</a>.
Contact: the owner, via the GitHub repository above.</p>`;

function page(body: string): Response {
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Flights</title><style>body{font:16px/1.6 system-ui,sans-serif;max-width:40rem;margin:3rem auto;padding:0 1.25rem;color:#1b1f2a}h1{font-size:1.6rem}h2{font-size:1.1rem;margin-top:1.8rem}a{color:#1e4fd8}</style>
</head><body>${body}</body></html>`;
  return new Response(html, { headers: { "content-type": "text/html; charset=utf-8" } });
}

// ── Helpers ─────────────────────────────────────────────────────────────────

class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

function localTime(iso: string, tz: string | null): string {
  return new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", timeZone: tz ?? "UTC" }).format(new Date(iso));
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}
