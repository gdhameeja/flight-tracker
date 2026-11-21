import { describe, expect, it } from "vitest";
import departed from "./fixtures/adb-departed.json";
import arrived from "./fixtures/adb-arrived.json";
import { diff } from "../src/changes";
import type { Flight } from "../src/model";
import { delayMinutes } from "../src/model";
import { type AdbFlight, isoUtc, mapStatus, normalize } from "../src/providers/aerodatabox";
import { nextCheckAt } from "../src/schedule";

const AT = "2026-11-21T06:00:00.000Z";
const hhmm = (iso: string) => iso.slice(11, 16);
const clone = (f: Flight): Flight => JSON.parse(JSON.stringify(f));

describe("AeroDataBox normalising", () => {
  it("reads times as UTC ISO", () => {
    expect(isoUtc({ utc: "2026-11-21 00:23Z" })).toBe("2026-11-21T00:23:00.000Z");
    expect(isoUtc(undefined)).toBeNull();
  });

  it("maps statuses", () => {
    expect(mapStatus("GateClosed")).toBe("gate_closed");
    expect(mapStatus("Canceled")).toBe("cancelled");
    expect(mapStatus("whatever")).toBe("unknown");
  });

  it("normalises a departed flight", () => {
    const f = normalize((departed as AdbFlight[])[0], "2026-11-21", AT);
    expect(f.number).toBe("AI 1712");
    expect(f.status).toBe("departed");
    expect(f.departure.airport.iata).toBe("DEL");
    expect(f.departure.gate).toBe("T30");
    expect(f.departure.actual).toBe("2026-11-21T00:23:00.000Z");
    expect(f.arrival.airport.iata).toBe("BHJ");
    expect(f.arrival.actual).toBeNull();
    expect(f.aircraft.reg).toBe("VT-TQM");
  });

  it("normalises an arrived flight with its belt", () => {
    const f = normalize((arrived as AdbFlight[])[0], "2026-11-21", AT);
    expect(f.status).toBe("arrived");
    expect(f.arrival.baggageBelt).toBe("10");
    expect(f.departure.airport.timeZone).toBe("Asia/Dubai");
    expect(f.departure.checkInDesk).toBe("L6-L10");
  });
});

function upcoming(depInMin: number, now: number): Flight {
  const f = normalize((departed as AdbFlight[])[0], "2026-11-21", AT);
  f.status = "scheduled";
  f.departure.actual = null;
  f.departure.scheduled = f.departure.estimated = new Date(now + depInMin * 60_000).toISOString();
  f.arrival.scheduled = f.arrival.estimated = new Date(now + (depInMin + 120) * 60_000).toISOString();
  return f;
}

describe("check schedule", () => {
  const now = Date.parse(AT);
  const inMin = (f: Flight) => (nextCheckAt(f, now)! - now) / 60_000;

  it("waits until two days out", () => {
    const f = upcoming(5 * 24 * 60, now);
    expect(nextCheckAt(f, now)).toBe(Date.parse(f.departure.scheduled!) - 48 * 3600_000);
  });
  it("checks every 20 minutes in the last two hours", () => expect(inMin(upcoming(90, now))).toBe(20));
  it("checks hourly 2-6 hours out", () => expect(inMin(upcoming(4 * 60, now))).toBe(60));
  it("stops after a cancellation", () => {
    const f = upcoming(60, now);
    f.status = "cancelled";
    expect(nextCheckAt(f, now)).toBeNull();
  });
  it("looks once more for the belt after landing", () => {
    const f = normalize((arrived as AdbFlight[])[0], "2026-11-21", AT);
    f.arrival.baggageBelt = null;
    f.arrival.actual = new Date(now - 10 * 60_000).toISOString();
    expect(inMin(f)).toBe(15);
    f.arrival.baggageBelt = "4";
    expect(nextCheckAt(f, now)).toBeNull();
  });
  // About 20 checks a flight keeps us inside 400 units a month
  it("uses a bounded number of checks per flight", () => {
    let t = now;
    const f = upcoming(3 * 24 * 60, now);
    let checks = 0;
    for (let next = nextCheckAt(f, t); next !== null && checks < 100; next = nextCheckAt(f, t)) {
      t = next;
      checks++;
      const dep = Date.parse(f.departure.scheduled!);
      const arr = Date.parse(f.arrival.scheduled!);
      if (t > dep + 5 * 60_000) { f.status = "en_route"; f.departure.actual = f.departure.scheduled; }
      if (t > arr) { f.status = "arrived"; f.arrival.actual = f.arrival.scheduled; f.arrival.baggageBelt = "3"; }
    }
    expect(checks).toBeLessThanOrEqual(25);
    expect(f.status).toBe("arrived");
  });
});

describe("alerts", () => {
  const now = Date.parse(AT);
  const base = upcoming(120, now);

  it("says nothing when nothing changed", () => expect(diff(base, clone(base), hhmm)).toEqual([]));

  it("announces a gate change", () => {
    const next = clone(base);
    next.departure.gate = "T42";
    const [e] = diff(base, next, hhmm);
    expect(e.kind).toBe("gate");
    expect(e.title).toContain("T30 → T42");
  });

  it("announces a delay once it's 15+ minutes, and not small wobbles", () => {
    const small = clone(base);
    small.departure.estimated = new Date(Date.parse(base.departure.scheduled!) + 8 * 60_000).toISOString();
    expect(diff(base, small, hhmm)).toEqual([]);

    const late = clone(base);
    late.status = "delayed";
    late.departure.estimated = new Date(Date.parse(base.departure.scheduled!) + 45 * 60_000).toISOString();
    expect(delayMinutes(late.departure)).toBe(45);
    const kinds = diff(base, late, hhmm).map((e) => e.kind);
    expect(kinds).toContain("delay");
  });

  it("announces a cancellation", () => {
    const next = clone(base);
    next.status = "cancelled";
    expect(diff(base, next, hhmm)[0].kind).toBe("cancelled");
  });

  it("puts the belt in the landing alert, and announces a later belt", () => {
    const air = clone(base);
    air.status = "en_route";
    const landed = clone(air);
    landed.status = "arrived";
    landed.arrival.baggageBelt = "7";
    const ev = diff(air, landed, hhmm);
    expect(ev.map((e) => e.kind)).toEqual(["arrived"]);
    expect(ev[0].body).toContain("belt 7");

    const noBelt = clone(landed);
    noBelt.arrival.baggageBelt = null;
    const later = clone(landed);
    expect(diff(noBelt, later, hhmm).map((e) => e.kind)).toEqual(["belt"]);
  });
});
