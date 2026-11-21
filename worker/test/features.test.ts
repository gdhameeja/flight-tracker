import { describe, expect, it } from "vitest";
import { connect, type ConnFlight, findConnections, worsened } from "../src/connections";
import type { Airport, Flight } from "../src/model";
import { checkinOpensAt, reminders } from "../src/reminders";
import { assess, forecastAt, visToMetres } from "../src/weather";

const ap = (iata: string, cc: string, tz: string): Airport => ({ iata, icao: null, name: iata, city: iata, countryCode: cc, timeZone: tz, lat: null, lon: null });
const DPS = ap("DPS", "ID", "Asia/Makassar");
const DEL = ap("DEL", "IN", "Asia/Kolkata");
const DXB = ap("DXB", "AE", "Asia/Dubai");
const BOM = ap("BOM", "IN", "Asia/Kolkata");
const BKK = ap("BKK", "TH", "Asia/Bangkok");
const DMK = ap("DMK", "TH", "Asia/Bangkok");
const CNX = ap("CNX", "TH", "Asia/Bangkok");

function fl(number: string, from: Airport, to: Airport, dep: string, arr: string, extra: Partial<Flight> = {}, terms: [string?, string?] = []): Flight {
  const end = (a: Airport, t: string, term?: string) => ({ airport: a, scheduled: t, estimated: null, actual: null, terminal: term ?? null, gate: null, checkInDesk: null, baggageBelt: null });
  return {
    number, date: dep.slice(0, 10), airline: { name: null, iata: number.slice(0, 2), icao: null }, status: "scheduled",
    departure: end(from, dep, terms[0]), arrival: end(to, arr, terms[1]), aircraft: { model: null, reg: null }, source: "test", fetchedAt: dep, ...extra,
  };
}
const c = (id: string, flight: Flight, pnr: string | null = null): ConnFlight => ({ id, flight, pnr });
const bags = { checkedBags: true };

describe("connections", () => {
  // Your 5 Dec trip: Bali → Delhi → Dubai on Air India
  const a = fl("AI 2146", DPS, DEL, "2026-12-05T00:40:00Z", "2026-12-05T08:10:00Z", {}, [undefined, "3"]);
  const b = fl("AI 915", DEL, DXB, "2026-12-05T10:40:00Z", "2026-12-05T13:55:00Z", {}, ["3"]);

  it("one booking, international to international at Delhi: minimum connection time", () => {
    const conn = connect(c("a", a, "PNR1"), c("b", b, "PNR1"), bags)!;
    expect(conn.airport).toBe("DEL");
    expect(conn.layoverMin).toBe(150);
    expect(conn.selfTransfer).toBe(false);
    expect(conn.neededMin).toBe(75);
    expect(conn.level).toBe("comfortable");
  });

  it("separate bookings at Delhi: immigration, bag, check-in closing", () => {
    const conn = connect(c("a", a, "PNR1"), c("b", b, "PNR2"), bags)!;
    expect(conn.selfTransfer).toBe(true);
    expect(conn.steps.map((s) => s.label)).toEqual([
      "Get off the plane",
      "Immigration on arrival (India)",
      "Collect your bag",
      "AI check-in closes before departure",
    ]);
    expect(conn.neededMin).toBe(15 + 35 + 25 + 60);
    expect(conn.level).toBe("tight"); // 150 - 135 = 15 spare
  });

  it("goes at risk when the first flight is late", () => {
    const late = { ...a, arrival: { ...a.arrival, estimated: "2026-12-05T09:30:00Z" } };
    expect(connect(c("a", late, "X"), c("b", b, "Y"), bags)!.level).toBe("at_risk");
  });

  it("India first entry: bags and customs at Delhi even on one ticket", () => {
    const dom = fl("AI 805", DEL, BOM, "2026-12-05T11:40:00Z", "2026-12-05T13:55:00Z", {}, ["3"]);
    const conn = connect(c("a", a, "P"), c("b", dom, "P"), bags)!;
    expect(conn.steps.some((s) => s.label.includes("first airport in India"))).toBe(true);
    expect(conn.notes.join(" ")).toContain("first Indian airport");
  });

  it("Delhi T1 ↔ T3 adds the shuttle", () => {
    const dom = fl("6E 2001", DEL, BOM, "2026-12-05T12:40:00Z", "2026-12-05T14:55:00Z", {}, ["1"]);
    const conn = connect(c("a", a, "P"), c("b", dom, "Q"), bags)!;
    expect(conn.steps.find((s) => s.label === "Terminal 3 → Terminal 1")?.minutes).toBe(45);
    expect(conn.notes.join(" ")).toContain("shuttle");
  });

  it("Bangkok: Suvarnabhumi to Don Mueang is a road transfer", () => {
    const x = fl("TG 316", DEL, BKK, "2026-11-09T00:00:00Z", "2026-11-09T04:00:00Z");
    const y = fl("FD 4096", DMK, CNX, "2026-11-09T09:00:00Z", "2026-11-09T10:10:00Z");
    const conn = connect(c("x", x), c("y", y), bags)!;
    expect(conn.airport).toBe("BKK → DMK");
    expect(conn.steps.some((s) => s.minutes === 75)).toBe(true);
  });

  it("cabin bag only: no bag collection, gate closing instead", () => {
    const conn = connect(c("a", a, "X"), c("b", b, "Y"), { checkedBags: false })!;
    expect(conn.steps.map((s) => s.label)).not.toContain("Collect your bag");
    expect(conn.level).toBe("comfortable");
  });

  it("ignores flights from different airports, and stays longer than a day", () => {
    const later = fl("AI 915", DEL, DXB, "2026-12-07T10:40:00Z", "2026-12-07T13:55:00Z");
    expect(findConnections([c("a", a), c("b", later)], bags)).toHaveLength(0);
    const elsewhere = fl("EK 1", DXB, BOM, "2026-12-05T10:40:00Z", "2026-12-05T13:55:00Z");
    expect(findConnections([c("a", a), c("b", elsewhere)], bags)).toHaveLength(0);
  });

  it("alerts only when it gets worse", () => {
    expect(worsened("comfortable", "tight")).toBe(true);
    expect(worsened("tight", "comfortable")).toBe(false);
    expect(worsened("long", "comfortable")).toBe(false);
  });
});

describe("reminders", () => {
  const hhmm = (iso: string) => iso.slice(11, 16);
  it("IndiGo domestic check-in opens 48 h ahead, international 24 h", () => {
    const dom = fl("6E 2001", DEL, BOM, "2026-12-10T10:00:00Z", "2026-12-10T12:00:00Z");
    const intl = fl("6E 1406", DXB, DEL, "2026-12-10T10:00:00Z", "2026-12-10T14:00:00Z");
    expect(checkinOpensAt(dom)).toBe(Date.parse("2026-12-08T10:00:00Z"));
    expect(checkinOpensAt(intl)).toBe(Date.parse("2026-12-09T10:00:00Z"));
  });
  it("airport plan: be there 3 h before an international flight, reminder 2 h before that", () => {
    const f = fl("AI 915", DEL, DXB, "2026-12-05T10:40:00Z", "2026-12-05T13:55:00Z", { airline: { name: "Air India", iata: "AI", icao: null } });
    const plan = reminders(f, true, hhmm).find((r) => r.kind === "airport_plan")!;
    expect(plan.at).toBe(Date.parse("2026-12-05T05:40:00Z"));
    expect(plan.title).toContain("by 07:40");
  });
  it("nothing once the flight has gone", () => {
    const f = fl("AI 915", DEL, DXB, "2026-12-05T10:40:00Z", "2026-12-05T13:55:00Z", { status: "departed" });
    expect(reminders(f, true, hhmm)).toEqual([]);
  });
});

describe("weather", () => {
  it("reads visibility in miles or metres", () => {
    expect(visToMetres("6+")).toBe(10_000);
    expect(visToMetres(0.25)).toBe(402);
    expect(visToMetres("1/2")).toBe(805);
    expect(visToMetres("0800")).toBe(800);
  });
  it("fog levels", () => {
    expect(assess({ visibilityM: 300, windKt: 2, gustKt: null, weather: "FG", tempC: 8 })?.level).toBe("warning");
    expect(assess({ visibilityM: 1200, windKt: 2, gustKt: null, weather: "BR", tempC: 12 })?.level).toBe("watch");
    expect(assess({ visibilityM: 6000, windKt: 8, gustKt: null, weather: "HZ", tempC: 24 })).toBeNull();
    expect(assess({ visibilityM: 9000, windKt: 10, gustKt: null, weather: "TSRA", tempC: 30 })?.text).toContain("Thunderstorms");
  });
  it("takes the worst of overlapping forecast groups", () => {
    const taf = {
      fcsts: [
        { timeFrom: 1000, timeTo: 5000, visib: "6+", wxString: null, wspd: 5 },
        { timeFrom: 2000, timeTo: 3000, visib: 0.12, wxString: "FG", wspd: 2, fcstChange: "TEMPO" },
      ],
    };
    const f = forecastAt(taf, 2500 * 1000)!;
    expect(f.visibilityM).toBe(193);
    expect(f.weather).toBe("FG");
    expect(forecastAt(taf, 9000 * 1000)).toBeNull();
  });
});
