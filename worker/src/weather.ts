// Airport weather from aviationweather.gov (free, no key): the current report
// (METAR) and the forecast (TAF). Fog in North India from December to
// February is the main thing to watch: low visibility means delays,
// diversions and cancellations.

export interface Conditions {
  visibilityM: number | null; // metres
  windKt: number | null;
  gustKt: number | null;
  weather: string | null; // e.g. "FG", "TSRA"
  tempC: number | null;
}

export interface AirportWeather {
  icao: string;
  now: (Conditions & { observed: string | null; raw: string | null }) | null;
  /** Forecast for the hour of your flight there, when the TAF reaches it */
  atFlight: (Conditions & { from: string; to: string }) | null;
  risk: WeatherRisk | null;
}

export interface WeatherRisk {
  level: "watch" | "warning";
  text: string;
}

const API = "https://aviationweather.gov/api/data";
const UA = { "user-agent": "flight-tracker (personal use)" };

export async function airportWeather(icao: string, atIso: string | null): Promise<AirportWeather> {
  const [metars, tafs] = await Promise.all([
    getJson<any[]>(`${API}/metar?ids=${icao}&format=json`),
    getJson<any[]>(`${API}/taf?ids=${icao}&format=json`),
  ]);
  const m = metars?.[0];
  const now = m
    ? {
        visibilityM: visToMetres(m.visib),
        windKt: num(m.wspd),
        gustKt: num(m.wgst),
        weather: m.wxString ?? null,
        tempC: num(m.temp),
        observed: m.reportTime ?? (m.obsTime ? new Date(m.obsTime * 1000).toISOString() : null),
        raw: m.rawOb ?? null,
      }
    : null;
  const atFlight = atIso ? forecastAt(tafs?.[0], Date.parse(atIso)) : null;
  return { icao, now, atFlight, risk: assess(atFlight ?? now) };
}

/** The TAF period covering a time. Later change groups (BECMG/TEMPO) override the base. */
export function forecastAt(taf: any, at: number): (Conditions & { from: string; to: string }) | null {
  const t = Math.floor(at / 1000);
  const periods: any[] = (taf?.fcsts ?? []).filter((p: any) => p.timeFrom <= t && t < p.timeTo);
  if (periods.length === 0) return null;
  // Start from the base period, take the worst of the overlapping change groups
  let vis: number | null = null;
  let wx: string | null = null;
  let wind: number | null = null;
  let gust: number | null = null;
  for (const p of periods) {
    const v = visToMetres(p.visib);
    if (v !== null && (vis === null || v < vis)) vis = v;
    if (p.wxString) wx = wx ? `${wx} ${p.wxString}` : p.wxString;
    if (num(p.wspd) !== null && (wind === null || p.wspd > wind)) wind = p.wspd;
    if (num(p.wgst) !== null && (gust === null || p.wgst > gust)) gust = p.wgst;
  }
  const p0 = periods[0];
  return { visibilityM: vis, windKt: wind, gustKt: gust, weather: wx, tempC: null, from: new Date(p0.timeFrom * 1000).toISOString(), to: new Date(p0.timeTo * 1000).toISOString() };
}

/** Is this weather likely to disrupt flights? */
export function assess(c: Conditions | null): WeatherRisk | null {
  if (!c) return null;
  const wx = c.weather ?? "";
  const vis = c.visibilityM;
  if (vis !== null && vis < 550) return { level: "warning", text: `Dense fog: visibility ${fmtVis(vis)}. Delays, diversions and cancellations are likely.` };
  if (/\bFG\b/.test(wx) || (vis !== null && vis < 1500)) return { level: "watch", text: `Fog or mist: visibility ${vis !== null ? fmtVis(vis) : "low"}. Some delays are likely.` };
  if (/TS/.test(wx)) return { level: "watch", text: "Thunderstorms forecast. Short delays and holding are common." };
  if ((c.gustKt ?? 0) >= 35) return { level: "watch", text: `Strong gusts (${c.gustKt} kt). Expect a bumpy approach, possibly delays.` };
  return null;
}

export function fmtVis(m: number): string {
  return m >= 10_000 ? "10 km+" : m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m / 50) * 50} m`;
}

/** aviationweather.gov gives visibility in statute miles ("6+", 0.25, "1/2"); some reports give metres. */
export function visToMetres(v: unknown): number | null {
  if (v === null || v === undefined || v === "") return null;
  const s = String(v).trim();
  if (s.endsWith("+")) return 10_000;
  let miles: number;
  if (/^\d+\/\d+$/.test(s)) {
    const [a, b] = s.split("/").map(Number);
    miles = a / b;
  } else miles = Number(s);
  if (Number.isNaN(miles)) return null;
  // Values above 15 can only be metres (e.g. "0800" from a metric report)
  return miles > 15 ? Math.round(miles) : Math.round(miles * 1609);
}

function num(v: unknown): number | null {
  return typeof v === "number" && !Number.isNaN(v) ? v : null;
}

async function getJson<T>(url: string): Promise<T | null> {
  try {
    const r = await fetch(url, { headers: UA });
    if (!r.ok || r.status === 204) return null;
    return (await r.json()) as T;
  } catch {
    return null;
  }
}
