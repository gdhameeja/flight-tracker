// Gmail import: finds booking emails, extracts the flights and adds them.
// Upcoming flights are tracked (the schedule's first live check is two days
// before departure, so importing costs no AeroDataBox units). Past flights go
// straight into the history, from the email alone.

import { emailForModel, type EmailSegment, type Extraction, fromAi, fromJsonLd, localToUtc } from "./extract";
import { Gmail, htmlToText } from "./gmail";
import type { Airport, Flight } from "./model";
import { nextCheckAt } from "./schedule";

export interface ImportEnv {
  DB: D1Database;
  AI: Ai;
  GMAIL_CLIENT_ID?: string;
  GMAIL_CLIENT_SECRET?: string;
  GMAIL_REFRESH_TOKEN?: string;
}

/** How far back the first scan reads. Upcoming flights are what matter, so a couple of months. */
export const HISTORY_DAYS = 60;
/** Emails read with the AI per run (each takes a few seconds). */
const AI_PER_RUN = 6;
/** Emails looked at per run, at most. */
const READ_PER_RUN = 25;

// Airlines and booking sites that send confirmations, for the Gmail search.
// Anything else still matches through the subject words.
const SENDERS = [
  "goindigo.in", "airindia.com", "airindia.in", "airindiaexpress.com", "akasaair.com", "spicejet.com", "airvistara.com",
  "emirates.com", "flydubai.com", "airarabia.com", "etihad.com", "qatarairways.com", "saudia.com", "omanair.com", "gulfair.com",
  "airasia.com", "vietjetair.com", "vietnamairlines.com", "bambooairways.com", "thaiairways.com", "bangkokair.com", "thaiviet",
  "lionairgroup.com", "batikair.com", "garuda-indonesia.com", "singaporeair.com", "flyscoot.com", "malaysiaairlines.com", "cathaypacific.com",
  "makemytrip.com", "cleartrip.com", "goibibo.com", "ixigo.com", "easemytrip.com", "yatra.com", "booking.com", "agoda.com", "trip.com",
  "expedia", "kiwi.com", "mytrip.com", "gotogate", "skyscanner", "traveloka.com", "tiket.com", "12go.asia",
];
const SUBJECT_WORDS = ['"booking confirmation"', '"booking confirmed"', '"e-ticket"', "eticket", "itinerary", '"flight booking"', '"boarding pass"', "PNR", '"web check-in"', '"your trip"', '"booking reference"', '"schedule change"'];

export function searchQuery(sinceDays: number): string {
  const after = new Date(Date.now() - sinceDays * 86_400_000).toISOString().slice(0, 10).replace(/-/g, "/");
  return `(from:(${SENDERS.join(" OR ")}) OR subject:(${SUBJECT_WORDS.join(" OR ")})) -category:promotions -in:chats after:${after}`;
}

/** Cheap check before spending an AI call: a flight number and booking words. */
export function looksLikeBooking(subject: string, body: string): boolean {
  // Subjects that are always worth reading (boarding passes are often one line plus a PDF)
  if (/boarding pass|e-?ticket|\bPNR\b|itinerary|booking (is )?confirm|check-?in (now|open|is open)|web check-?in|schedule change/i.test(subject)) return true;
  const t = `${subject}\n${body.slice(0, 20_000)}`;
  const flightNo = /\b(?:[A-Z][A-Z0-9]|[0-9][A-Z])[\s-]?\d{2,4}\b/.test(t);
  const words = /\b(PNR|booking|e-?ticket|itinerary|confirmation|boarding|check-?in|reservation)\b/i.test(t);
  return flightNo && words;
}

export interface ScanResult {
  looked: number;
  imported: number;
  remaining: number;
}

export async function scanGmail(env: ImportEnv, opts: { sinceDays: number }): Promise<ScanResult> {
  if (!env.GMAIL_CLIENT_ID || !env.GMAIL_CLIENT_SECRET || !env.GMAIL_REFRESH_TOKEN) throw new Error("Gmail isn't connected");
  const gmail = new Gmail({ clientId: env.GMAIL_CLIENT_ID, clientSecret: env.GMAIL_CLIENT_SECRET, refreshToken: env.GMAIL_REFRESH_TOKEN });

  const ids = await gmail.search(searchQuery(opts.sinceDays), 1000);
  const seen = await seenIds(env, ids);
  const todo = ids.filter((id) => !seen.has(id));
  // Oldest first, so a later email (schedule change) is applied after the booking
  todo.reverse();

  let looked = 0;
  let aiCalls = 0;
  let imported = 0;
  for (const id of todo) {
    if (looked >= READ_PER_RUN || aiCalls >= AI_PER_RUN) break;
    looked++;
    let result = "none";
    try {
      const msg = await gmail.message(id);
      let ex: Extraction | null = msg.html ? fromJsonLd(msg.html) : null;
      if (!ex) {
        const body = msg.text.trim().length > 200 ? msg.text : htmlToText(msg.html);
        if (!looksLikeBooking(msg.subject, body)) {
          result = "skipped";
        } else {
          aiCalls++;
          ex = await fromAi(env.AI, emailForModel(msg.subject, msg.from, new Date(msg.date), body));
        }
      }
      if (ex?.isBooking && ex.segments.length) {
        const n = await applyBooking(env, ex, msg.id, msg.subject);
        imported += n;
        result = `${ex.method}:${ex.segments.length} flights${ex.cancelled ? " cancelled" : ""}`;
      }
    } catch (e) {
      result = `error: ${String(e).slice(0, 150)}`;
      console.error(`Gmail import of ${id} failed`, e);
      // An AI or Gmail outage: stop and retry these next run rather than marking them read
      if (String(e).includes("Gmail sign-in failed")) throw e;
      if (!String(e).includes("Gmail 4")) break;
    }
    await env.DB.prepare("insert or replace into gmail_seen (message_id, at, result) values (?, ?, ?)").bind(id, Date.now(), result).run();
  }
  return { looked, imported, remaining: todo.length - looked };
}

async function seenIds(env: ImportEnv, ids: string[]): Promise<Set<string>> {
  const seen = new Set<string>();
  for (let i = 0; i < ids.length; i += 90) {
    const chunk = ids.slice(i, i + 90);
    const rows = await env.DB.prepare(`select message_id from gmail_seen where message_id in (${chunk.map(() => "?").join(",")})`).bind(...chunk).all<{ message_id: string }>();
    for (const r of rows.results) seen.add(r.message_id);
  }
  return seen;
}

/** Adds or updates each segment; returns how many flights were new. */
async function applyBooking(env: ImportEnv, ex: Extraction, messageId: string, subject: string): Promise<number> {
  const now = Date.now();
  let added = 0;
  for (const seg of ex.segments) {
    const existing = await env.DB.prepare("select id, next_check_at from flights where number = ? and date = ? and dep_iata = ?")
      .bind(seg.flightNumber, seg.date, seg.from)
      .first<{ id: string; next_check_at: number | null }>();

    if (ex.cancelled) {
      // Only tell you about it: the live data decides whether the flight is really gone
      if (existing && existing.next_check_at !== null) {
        await addEvent(env, existing.id, now, "email_cancelled", `📧 ${display(seg.flightNumber)}: cancellation email`, `"${subject.slice(0, 120)}". Check with the airline; the app keeps tracking until the flight data agrees.`);
      }
      continue;
    }

    let flightId = existing?.id;
    if (!flightId) {
      const flight = await flightFromEmail(env, seg);
      if (!flight) continue; // unknown airport
      const arr = Date.parse(flight.arrival.scheduled ?? flight.departure.scheduled ?? "");
      // No times in the email: go by the date (a day's grace for time zones)
      const upcoming = Number.isNaN(arr) ? Date.parse(seg.date) > now - 2 * 86_400_000 : arr > now - 6 * 3600_000;
      await supersede(env, seg);
      // History from email only: we never saw it fly
      if (!upcoming) flight.status = "unknown";
      flightId = crypto.randomUUID();
      await env.DB.prepare("insert into flights (id, number, date, dep_iata, data, next_check_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)")
        .bind(flightId, seg.flightNumber, seg.date, seg.from, JSON.stringify(flight), upcoming ? nextCheckAt(flight, now) : null, now, now)
        .run();
      added++;
      if (upcoming) {
        const when = flight.departure.scheduled ? ` on ${fmtDay(flight.departure.scheduled, flight.departure.airport.timeZone)}` : "";
        await addEvent(env, flightId, now, "imported", `📧 Added ${display(seg.flightNumber)} from your email`, `${seg.from} → ${seg.to}${when}. Live tracking starts two days before departure.`);
      }
    }
    await env.DB.prepare(
      "insert into bookings (flight_id, pnr, seats, passengers, airline_name, message_id, subject, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?) " +
        "on conflict (flight_id) do update set pnr = coalesce(excluded.pnr, pnr), seats = coalesce(excluded.seats, seats), passengers = coalesce(excluded.passengers, passengers), " +
        "airline_name = coalesce(excluded.airline_name, airline_name), message_id = excluded.message_id, subject = excluded.subject, updated_at = excluded.updated_at",
    )
      .bind(flightId, seg.pnr, seg.seat, seg.passengers.length ? JSON.stringify(seg.passengers) : null, seg.airlineName, messageId, subject.slice(0, 200), now)
      .run();
  }
  return added;
}

/**
 * A newer booking for the same route within 3 days of an older one is a
 * rebooking (date or flight changed): the older imported flight goes. Emails
 * are read oldest first, so whatever is already there is older. Flights you
 * added by hand are never touched.
 */
async function supersede(env: ImportEnv, seg: EmailSegment) {
  const day = 86_400_000;
  const d = Date.parse(seg.date);
  const old = await env.DB.prepare(
    "select f.id from flights f join bookings b on b.flight_id = f.id " +
      "where f.dep_iata = ? and json_extract(f.data, '$.arrival.airport.iata') = ? and f.date between ? and ? and not (f.number = ? and f.date = ?)",
  )
    .bind(seg.from, seg.to, new Date(d - 3 * day).toISOString().slice(0, 10), new Date(d + 3 * day).toISOString().slice(0, 10), seg.flightNumber, seg.date)
    .all<{ id: string }>();
  for (const r of old.results) {
    await env.DB.batch([
      env.DB.prepare("delete from events where flight_id = ?").bind(r.id),
      env.DB.prepare("delete from bookings where flight_id = ?").bind(r.id),
      env.DB.prepare("delete from flights where id = ?").bind(r.id),
    ]);
  }
}

async function addEvent(env: ImportEnv, flightId: string, at: number, kind: string, title: string, body: string) {
  await env.DB.prepare("insert into events (flight_id, at, kind, title, body) values (?, ?, ?, ?, ?)").bind(flightId, at, kind, title, body).run();
}

export async function airport(env: { DB: D1Database }, iata: string): Promise<Airport | null> {
  const a = await env.DB.prepare("select * from airports where iata = ?").bind(iata).first<{ iata: string; icao: string; name: string; city: string; country: string; tz: string; lat: number; lon: number }>();
  return a ? { iata: a.iata, icao: a.icao, name: a.name, city: a.city, countryCode: a.country, timeZone: a.tz, lat: a.lat, lon: a.lon } : null;
}

/** A Flight built from the email only, until the first live check fills in the rest. */
export async function flightFromEmail(env: { DB: D1Database }, seg: EmailSegment): Promise<Flight | null> {
  const dep = await airport(env, seg.from);
  const arr = await airport(env, seg.to);
  if (!dep || !arr) return null;
  const depUtc = seg.departureTime ? localToUtc(seg.date, seg.departureTime, dep.timeZone!) : null;
  let arrUtc = seg.arrivalTime ? localToUtc(seg.arrivalDate ?? seg.date, seg.arrivalTime, arr.timeZone!) : null;
  // Overnight flight with no arrival date given: it lands the next day
  if (depUtc && arrUtc && !seg.arrivalDate && Date.parse(arrUtc) <= Date.parse(depUtc)) arrUtc = new Date(Date.parse(arrUtc) + 86_400_000).toISOString();
  const end = (a: Airport, t: string | null) => ({ airport: a, scheduled: t, estimated: null, actual: null, terminal: null, gate: null, checkInDesk: null, baggageBelt: null });
  return {
    number: display(seg.flightNumber),
    date: seg.date,
    airline: { name: seg.airlineName, iata: seg.flightNumber.slice(0, 2), icao: null },
    status: "scheduled",
    departure: end(dep, depUtc),
    arrival: end(arr, arrUtc),
    aircraft: { model: null, reg: null },
    source: "email",
    fetchedAt: new Date().toISOString(),
  };
}

/** "6E1406" → "6E 1406" */
export function display(n: string): string {
  return `${n.slice(0, 2)} ${n.slice(2)}`;
}

function fmtDay(iso: string, tz: string | null): string {
  return new Intl.DateTimeFormat("en-GB", { weekday: "short", day: "numeric", month: "short", timeZone: tz ?? "UTC" }).format(new Date(iso));
}
