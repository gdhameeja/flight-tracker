// Shared by the routes and the scheduled jobs: errors, small settings, the API budget.

export class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

type Db = { DB: D1Database };

export async function getMeta(env: Db, key: string): Promise<string | null> {
  return (await env.DB.prepare("select value from meta where key = ?").bind(key).first<{ value: string }>())?.value ?? null;
}

export async function setMeta(env: Db, key: string, value: string) {
  await env.DB.prepare("insert into meta (key, value) values (?, ?) on conflict (key) do update set value = excluded.value").bind(key, value).run();
}

// ── AeroDataBox budget (400 units a month on the free plan) ────────────────

export async function unitsLeft(env: Db): Promise<number | null> {
  const v = await getMeta(env, "adb_units_remaining");
  return v === null ? null : Number(v);
}

export async function recordUnits(env: Db, remaining: number | null) {
  if (remaining === null || Number.isNaN(remaining)) return;
  await setMeta(env, "adb_units_remaining", String(remaining));
  await setMeta(env, "adb_units_at", new Date().toISOString());
}

export async function requireUnits(env: Db, reserve: number) {
  const left = await unitsLeft(env);
  if (left !== null && left < reserve) throw new HttpError(429, `Only ${left} AeroDataBox units left this month`);
}

// ── Your preferences ───────────────────────────────────────────────────────

export interface UserSettings {
  checkedBags: boolean;
}

export async function getSettings(env: Db): Promise<UserSettings> {
  return { checkedBags: (await getMeta(env, "checked_bags")) !== "0" };
}

export function localTime(iso: string, tz: string | null): string {
  return new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", timeZone: tz ?? "UTC" }).format(new Date(iso));
}

/** "Sat 5 Dec, 23:45" local to the airport */
export function localDayTime(iso: string, tz: string | null): string {
  return new Intl.DateTimeFormat("en-GB", { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", timeZone: tz ?? "UTC" }).format(new Date(iso));
}
