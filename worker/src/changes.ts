// What changed between two looks at a flight, as alerts worth sending.

import { delayMinutes, type Flight, type FlightEvent } from "./model";

/** Smaller moves than this in the estimated time aren't worth an alert. */
const DELAY_STEP_MIN = 10;
/** A flight counts as delayed from this many minutes late. */
const DELAYED_MIN = 15;

export function diff(prev: Flight, next: Flight, tz: (iso: string, end: "dep" | "arr") => string): FlightEvent[] {
  const out: FlightEvent[] = [];
  const n = next.number;
  const route = `${next.departure.airport.iata ?? "?"} → ${next.arrival.airport.iata ?? "?"}`;

  if (prev.status !== next.status) {
    switch (next.status) {
      case "cancelled":
        out.push({ kind: "cancelled", title: `❌ ${n} cancelled`, body: `${route} has been cancelled. Open the app for the next flights on this route.` });
        break;
      case "diverted":
        out.push({ kind: "diverted", title: `⚠️ ${n} diverted`, body: `${route} is diverting.` });
        break;
      case "boarding":
        out.push({ kind: "boarding", title: `🚶 ${n} is boarding`, body: gateLine(next) });
        break;
      case "gate_closed":
        out.push({ kind: "gate_closed", title: `🚪 ${n} gate closed`, body: route });
        break;
      case "departed":
      case "en_route":
        if (!["departed", "en_route", "approaching"].includes(prev.status)) {
          const arr = next.arrival.estimated ?? next.arrival.scheduled;
          out.push({ kind: "departed", title: `🛫 ${n} took off`, body: arr ? `Landing ${tz(arr, "arr")} at ${next.arrival.airport.iata}.` : route });
        }
        break;
      case "arrived":
        out.push({
          kind: "arrived",
          title: `🛬 ${n} landed`,
          body: next.arrival.baggageBelt ? `Baggage belt ${next.arrival.baggageBelt}.` : `At ${next.arrival.airport.iata}.`,
        });
        break;
    }
  }

  // Gate / terminal at departure
  const dTerm = changed(prev.departure.terminal, next.departure.terminal);
  const dGate = changed(prev.departure.gate, next.departure.gate);
  if (dGate) {
    out.push({
      kind: "gate",
      title: prev.departure.gate ? `🔀 ${n} gate change: ${prev.departure.gate} → ${next.departure.gate}` : `${n} departs from gate ${next.departure.gate}`,
      body: dTerm ? `Now Terminal ${next.departure.terminal}.` : gateLine(next),
    });
  } else if (dTerm && prev.departure.terminal) {
    out.push({ kind: "terminal", title: `🔀 ${n} terminal change`, body: `Terminal ${prev.departure.terminal} → ${next.departure.terminal}.` });
  }

  // Delay (only before departure: in the air, the arrival time matters)
  if (!["departed", "en_route", "approaching", "arrived", "cancelled"].includes(next.status)) {
    const before = delayMinutes(prev.departure) ?? 0;
    const after = delayMinutes(next.departure) ?? 0;
    const est = next.departure.estimated;
    if (est && Math.abs(after - before) >= DELAY_STEP_MIN && (after >= DELAYED_MIN || before >= DELAYED_MIN)) {
      out.push(
        after >= DELAYED_MIN
          ? { kind: "delay", title: `⏰ ${n} delayed ${fmtMin(after)}`, body: `Now departing ${tz(est, "dep")} (was ${tz(next.departure.scheduled ?? est, "dep")}).` }
          : { kind: "delay", title: `✅ ${n} back on time`, body: `Departing ${tz(est, "dep")}.` },
      );
    }
  }

  // Belt
  // (skipped when the "landed" alert above already names the belt)
  const justLanded = next.status === "arrived" && prev.status !== "arrived";
  if (changed(prev.arrival.baggageBelt, next.arrival.baggageBelt) && !justLanded) {
    const term = next.arrival.terminal ? ` Terminal ${next.arrival.terminal}` : "";
    out.push({ kind: "belt", title: `🧳 ${n} baggage: belt ${next.arrival.baggageBelt}`, body: `${next.arrival.airport.iata}${term}.` });
  }

  return out;
}

function changed(a: string | null, b: string | null): boolean {
  return b !== null && b !== "" && a !== b;
}

function gateLine(f: Flight): string {
  const t = f.departure.terminal ? `Terminal ${f.departure.terminal}` : null;
  const g = f.departure.gate ? `Gate ${f.departure.gate}` : null;
  return [t, g].filter(Boolean).join(", ") || `${f.departure.airport.iata}`;
}

export function fmtMin(m: number): string {
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60);
  const r = m % 60;
  return r ? `${h} h ${r} min` : `${h} h`;
}
