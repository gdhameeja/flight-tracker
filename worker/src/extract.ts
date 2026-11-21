// Finds flights in a booking email. First the structured data many airlines
// embed (schema.org FlightReservation, what Gmail's flight cards use), then
// Cloudflare Workers AI for emails without it. Every result is checked
// (real airport codes, valid flight number and date) before it's trusted.

export interface EmailSegment {
  flightNumber: string; // "6E1406"
  airlineName: string | null;
  date: string; // local departure date YYYY-MM-DD
  from: string; // IATA
  to: string; // IATA
  departureTime: string | null; // local HH:MM
  arrivalTime: string | null; // local HH:MM
  arrivalDate: string | null; // local YYYY-MM-DD when known
  pnr: string | null;
  seat: string | null;
  passengers: string[];
}

export interface Extraction {
  isBooking: boolean;
  cancelled: boolean;
  segments: EmailSegment[];
  method: "jsonld" | "ai" | "none";
}

export const FLIGHT_NO = /^[A-Z0-9]{2}\d{1,5}[A-Z]?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const TIME = /^\d{2}:\d{2}$/;
const IATA = /^[A-Z]{3}$/;

// ── schema.org JSON-LD ──────────────────────────────────────────────────────

interface LdNode {
  "@type"?: string | string[];
  "@graph"?: LdNode[];
  [k: string]: unknown;
}

export function fromJsonLd(html: string): Extraction | null {
  const blocks = [...html.matchAll(/<script[^>]+application\/ld\+json[^>]*>([\s\S]*?)<\/script>/gi)].map((m) => m[1]);
  const nodes: LdNode[] = [];
  for (const b of blocks) {
    try {
      const v = JSON.parse(b.trim());
      for (const n of Array.isArray(v) ? v : [v]) nodes.push(...(n["@graph"] ?? [n]));
    } catch {
      /* broken JSON in the email: ignore that block */
    }
  }
  const res = nodes.filter((n) => [n["@type"]].flat().includes("FlightReservation"));
  if (res.length === 0) return null;
  let cancelled = false;
  const segments: EmailSegment[] = [];
  for (const r of res) {
    const f = (r.reservationFor ?? {}) as Record<string, any>;
    if (String(r.reservationStatus ?? "").includes("Cancelled")) cancelled = true;
    const airline = f.airline ?? {};
    const num = normFlightNo(`${airline.iataCode ?? ""}${f.flightNumber ?? ""}`) ?? normFlightNo(String(f.flightNumber ?? ""));
    const dep = splitLocal(f.departureTime);
    const arr = splitLocal(f.arrivalTime);
    const seg: EmailSegment = {
      flightNumber: num ?? "",
      airlineName: airline.name ?? null,
      date: dep.date ?? "",
      from: String(f.departureAirport?.iataCode ?? "").toUpperCase(),
      to: String(f.arrivalAirport?.iataCode ?? "").toUpperCase(),
      departureTime: dep.time,
      arrivalTime: arr.time,
      arrivalDate: arr.date,
      pnr: (r.reservationNumber as string) ?? null,
      seat: ((r.airplaneSeat as string) ?? (r.reservedTicket as any)?.ticketedSeat?.seatNumber) ?? null,
      passengers: [r.underName].flat().map((u: any) => u?.name).filter(Boolean),
    };
    if (valid(seg)) segments.push(seg);
  }
  return { isBooking: true, cancelled, segments: mergeSegments(segments), method: "jsonld" };
}

/** "2026-11-21T14:35:00+05:30" → local date and time as written (the airport's own clock). */
function splitLocal(v: unknown): { date: string | null; time: string | null } {
  const m = String(v ?? "").match(/^(\d{4}-\d{2}-\d{2})(?:[T ](\d{2}:\d{2}))?/);
  return { date: m?.[1] ?? null, time: m?.[2] ?? null };
}

// ── Workers AI ──────────────────────────────────────────────────────────────

export const AI_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";

const PROMPT = `You read emails and extract flight bookings. Reply with JSON only:
{"isBooking": boolean, "cancelled": boolean, "flights": [{"airlineName": string|null, "flightNumber": string, "date": "YYYY-MM-DD", "from": "IATA", "to": "IATA", "departureTime": "HH:MM"|null, "arrivalTime": "HH:MM"|null, "arrivalDate": "YYYY-MM-DD"|null, "pnr": string|null, "seat": string|null, "passengers": [string]}]}

Rules:
- isBooking is true only for a confirmed booking, e-ticket, itinerary, schedule change, web check-in or boarding pass for flights the recipient has booked. Promotions, newsletters, fare alerts, search results and "flights you may like" are not bookings: isBooking false, flights [].
- cancelled is true when the email says the booking or flight is cancelled or refunded.
- One entry per flight segment, including connections and return flights.
- flightNumber: airline code plus number, no spaces, e.g. "6E1406", "EK512", "AI2256".
- date, departureTime: local at the departure airport, exactly as the email shows them. arrivalTime, arrivalDate: local at the arrival airport.
- from, to: 3-letter IATA airport codes. If only a city is given, use its main airport's code.
- pnr: the booking reference / PNR for that flight.
- Never invent values: use null when the email doesn't say.`;

const SCHEMA = {
  type: "object",
  properties: {
    isBooking: { type: "boolean" },
    cancelled: { type: "boolean" },
    flights: {
      type: "array",
      items: {
        type: "object",
        properties: {
          airlineName: { type: ["string", "null"] },
          flightNumber: { type: "string" },
          date: { type: "string" },
          from: { type: "string" },
          to: { type: "string" },
          departureTime: { type: ["string", "null"] },
          arrivalTime: { type: ["string", "null"] },
          arrivalDate: { type: ["string", "null"] },
          pnr: { type: ["string", "null"] },
          seat: { type: ["string", "null"] },
          passengers: { type: "array", items: { type: "string" } },
        },
        required: ["flightNumber", "date", "from", "to"],
      },
    },
  },
  required: ["isBooking", "cancelled", "flights"],
};

/** Text sent to the model: subject, sender and the start of the body (bookings put flights first). */
export function emailForModel(subject: string, from: string, received: Date, body: string): string {
  return `Received: ${received.toISOString().slice(0, 10)}\nFrom: ${from}\nSubject: ${subject}\n\n${body.slice(0, 12_000)}`;
}

export async function fromAi(ai: Ai, email: string): Promise<Extraction> {
  const out = (await ai.run(AI_MODEL as any, {
    messages: [
      { role: "system", content: PROMPT },
      { role: "user", content: email },
    ],
    response_format: { type: "json_schema", json_schema: SCHEMA },
    max_tokens: 1500,
    temperature: 0,
  } as any)) as { response?: unknown };
  return parseAi(out.response);
}

export function parseAi(response: unknown): Extraction {
  let v: any = response;
  if (typeof v === "string") {
    const m = v.match(/\{[\s\S]*\}/);
    try {
      v = m ? JSON.parse(m[0]) : null;
    } catch {
      v = null;
    }
  }
  if (!v || typeof v !== "object") return { isBooking: false, cancelled: false, segments: [], method: "ai" };
  const segments: EmailSegment[] = [];
  for (const f of Array.isArray(v.flights) ? v.flights : []) {
    const seg: EmailSegment = {
      flightNumber: normFlightNo(String(f.flightNumber ?? "")) ?? "",
      airlineName: str(f.airlineName),
      date: String(f.date ?? ""),
      from: String(f.from ?? "").toUpperCase(),
      to: String(f.to ?? "").toUpperCase(),
      departureTime: TIME.test(f.departureTime ?? "") ? f.departureTime : null,
      arrivalTime: TIME.test(f.arrivalTime ?? "") ? f.arrivalTime : null,
      arrivalDate: DATE.test(f.arrivalDate ?? "") ? f.arrivalDate : null,
      pnr: str(f.pnr),
      seat: str(f.seat),
      passengers: Array.isArray(f.passengers) ? f.passengers.map(String).filter(Boolean).slice(0, 9) : [],
    };
    if (valid(seg)) segments.push(seg);
  }
  return { isBooking: !!v.isBooking && segments.length > 0, cancelled: !!v.cancelled, segments: mergeSegments(segments), method: "ai" };
}

// ── Checks ──────────────────────────────────────────────────────────────────

/** "6E-1406", "6e 1406", "6E01406" → "6E1406"; null if it isn't a flight number. */
export function normFlightNo(s: string): string | null {
  const m = s.toUpperCase().replace(/[\s-]/g, "").match(/^([A-Z0-9]{2})0*(\d{1,4}[A-Z]?)$/);
  return m && FLIGHT_NO.test(m[1] + m[2]) && /[A-Z]/.test(m[1]) ? m[1] + m[2] : null;
}

function valid(s: EmailSegment): boolean {
  return FLIGHT_NO.test(s.flightNumber) && DATE.test(s.date) && IATA.test(s.from) && IATA.test(s.to) && s.from !== s.to && !Number.isNaN(Date.parse(s.date));
}

/** Same flight listed twice (one entry per passenger): keep one, with everyone's names. */
function mergeSegments(list: EmailSegment[]): EmailSegment[] {
  const byKey = new Map<string, EmailSegment>();
  for (const s of list) {
    const k = `${s.flightNumber}|${s.date}|${s.from}`;
    const prev = byKey.get(k);
    if (!prev) byKey.set(k, { ...s });
    else {
      prev.passengers = [...new Set([...prev.passengers, ...s.passengers])];
      prev.seat = [prev.seat, s.seat].filter(Boolean).join(", ") || null;
    }
  }
  return [...byKey.values()];
}

function str(v: unknown): string | null {
  return typeof v === "string" && v.trim() ? v.trim() : null;
}

// ── Time zones ──────────────────────────────────────────────────────────────

/** Local wall-clock time at an airport → ISO UTC. */
export function localToUtc(date: string, time: string, tz: string): string | null {
  const [y, m, d] = date.split("-").map(Number);
  const [hh, mm] = time.split(":").map(Number);
  if ([y, m, d, hh, mm].some((n) => Number.isNaN(n))) return null;
  const guess = Date.UTC(y, m - 1, d, hh, mm);
  const off1 = tzOffsetMin(guess, tz);
  let utc = guess - off1 * 60_000;
  const off2 = tzOffsetMin(utc, tz);
  if (off2 !== off1) utc = guess - off2 * 60_000; // across a DST change
  return new Date(utc).toISOString();
}

function tzOffsetMin(ms: number, tz: string): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: tz,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).formatToParts(new Date(ms));
  const g = (t: string) => Number(parts.find((p) => p.type === t)?.value);
  const asUtc = Date.UTC(g("year"), g("month") - 1, g("day"), g("hour"), g("minute"));
  return Math.round((asUtc - Math.floor(ms / 60_000) * 60_000) / 60_000);
}
