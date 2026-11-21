// Reminders that come from the timetable alone: online check-in opening, and
// when to head to the airport. Each fires once per flight.

import { bestDeparture, type Flight } from "./model";

export interface Reminder {
  kind: "checkin_open" | "airport_plan";
  at: number; // epoch ms when it's due
  title: string;
  body: string;
}

// Hours before departure that online check-in opens [domestic, international].
// When unsure we use the later time: a reminder after it opens is fine, one before isn't.
const CHECKIN_OPENS_H: Record<string, [number, number]> = {
  "6E": [48, 24], AI: [48, 24], IX: [48, 24], QP: [48, 24], SG: [48, 24],
  EK: [48, 48], EY: [30, 30], QR: [48, 48],
};
const CHECKIN_DEFAULT_H: [number, number] = [24, 24];

// Airline websites (home pages: deep links move too often)
export const AIRLINE_SITES: Record<string, string> = {
  "6E": "https://www.goindigo.in", AI: "https://www.airindia.com", IX: "https://www.airindiaexpress.com",
  QP: "https://www.akasaair.com", SG: "https://www.spicejet.com", EK: "https://www.emirates.com", FZ: "https://www.flydubai.com",
  G9: "https://www.airarabia.com", EY: "https://www.etihad.com", QR: "https://www.qatarairways.com", AK: "https://www.airasia.com",
  FD: "https://www.airasia.com", D7: "https://www.airasia.com", QZ: "https://www.airasia.com", Z2: "https://www.airasia.com",
  VJ: "https://www.vietjetair.com", VN: "https://www.vietnamairlines.com", TG: "https://www.thaiairways.com", PG: "https://www.bangkokair.com",
  SQ: "https://www.singaporeair.com", TR: "https://www.flyscoot.com", MH: "https://www.malaysiaairlines.com", JT: "https://www.lionair.co.id",
  ID: "https://www.batikair.com.my", GA: "https://www.garuda-indonesia.com",
};

export function isInternational(f: Flight): boolean {
  return (f.departure.airport.countryCode ?? "") !== (f.arrival.airport.countryCode ?? "");
}

/** Minutes before departure to be at the airport. */
export function beAtAirportMin(f: Flight, checkedBags: boolean): number {
  const intl = isInternational(f);
  return checkedBags ? (intl ? 180 : 120) : intl ? 150 : 90;
}

export function checkinOpensAt(f: Flight): number | null {
  const dep = Date.parse(bestDeparture(f) ?? "");
  if (Number.isNaN(dep)) return null;
  const [dom, intl] = CHECKIN_OPENS_H[f.airline.iata ?? ""] ?? CHECKIN_DEFAULT_H;
  return dep - (isInternational(f) ? intl : dom) * 3600_000;
}

export function reminders(f: Flight, checkedBags: boolean, tzTime: (iso: string) => string): Reminder[] {
  const depIso = bestDeparture(f);
  if (!depIso || ["departed", "en_route", "approaching", "arrived", "cancelled", "diverted"].includes(f.status)) return [];
  const dep = Date.parse(depIso);
  const route = `${f.departure.airport.iata} → ${f.arrival.airport.iata}`;
  const out: Reminder[] = [];

  const opens = checkinOpensAt(f);
  if (opens !== null) {
    out.push({
      kind: "checkin_open",
      at: opens,
      title: `✅ Check-in is open: ${f.number}`,
      body: `${route}, departing ${tzTime(depIso)}. Check in on the ${f.airline.name ?? "airline"} website or app.`,
    });
  }

  const beAt = beAtAirportMin(f, checkedBags);
  const beAtIso = new Date(dep - beAt * 60_000).toISOString();
  const term = f.departure.terminal ? ` Terminal ${f.departure.terminal}` : "";
  out.push({
    kind: "airport_plan",
    at: dep - (beAt + 120) * 60_000,
    title: `🧳 ${f.number} today: be at ${f.departure.airport.iata}${term} by ${tzTime(beAtIso)}`,
    body: `Departs ${tzTime(depIso)}. Open the app for your drive time and when to leave.`,
  });
  return out;
}
