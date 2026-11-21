// Sends alerts to the phone through Firebase Cloud Messaging (HTTP v1 API).
// Needs the FCM_SERVICE_ACCOUNT secret: the JSON key from Firebase →
// Project settings → Service accounts. Without it, alerts stay in the app's
// "Updates" list only.

export interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

export interface PushMessage {
  title: string;
  body: string;
  flightId: string;
  eventId: number;
}

let cached: { token: string; expires: number } | null = null;

/** Sends one alert. Returns false when the phone's token is no longer valid. */
export async function sendPush(sa: ServiceAccount, deviceToken: string, msg: PushMessage): Promise<boolean> {
  return sendData(sa, deviceToken, { title: msg.title, body: msg.body, flightId: msg.flightId, eventId: String(msg.eventId) });
}

/** A data message: alerts (with title/body) or a quiet { type: "sync" } that refreshes the app's widget and pinned notification. */
export async function sendData(sa: ServiceAccount, deviceToken: string, data: Record<string, string>): Promise<boolean> {
  const access = await accessToken(sa);
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: "POST",
    headers: { authorization: `Bearer ${access}`, "content-type": "application/json" },
    body: JSON.stringify({
      message: {
        token: deviceToken,
        // Data-only: the app builds the notification itself (channel, tap to open the flight)
        data,
        android: { priority: "high", ttl: "3600s" },
      },
    }),
  });
  if (res.status === 404 || res.status === 400) {
    const text = await res.text();
    if (text.includes("UNREGISTERED") || text.includes("INVALID_ARGUMENT")) return false;
    throw new Error(`FCM ${res.status}: ${text.slice(0, 200)}`);
  }
  if (!res.ok) throw new Error(`FCM ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return true;
}

/** OAuth access token from the service account (signed JWT), cached for its hour. */
async function accessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.expires > now + 60) return cached.token;
  const enc = (o: unknown) => b64url(new TextEncoder().encode(JSON.stringify(o)));
  const unsigned = `${enc({ alg: "RS256", typ: "JWT" })}.${enc({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  })}`;
  const key = await crypto.subtle.importKey("pkcs8", pemToDer(sa.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)));
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${unsigned}.${b64url(sig)}` }),
  });
  if (!res.ok) throw new Error(`Google token ${res.status}: ${(await res.text()).slice(0, 200)}`);
  const j = (await res.json()) as { access_token: string; expires_in: number };
  cached = { token: j.access_token, expires: now + j.expires_in };
  return j.access_token;
}

function pemToDer(pem: string): ArrayBuffer {
  const b64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

function b64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
