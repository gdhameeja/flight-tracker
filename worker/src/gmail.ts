// Minimal read-only Gmail client for Cloudflare Workers. Self-contained, so
// other projects can copy it as is. Credentials: an OAuth "Desktop app"
// client plus a refresh token from scripts/gmail-auth.mjs.

export interface GmailCredentials {
  clientId: string;
  clientSecret: string;
  refreshToken: string;
}

export interface GmailMessage {
  id: string;
  threadId: string;
  subject: string;
  from: string;
  /** Epoch ms when Gmail received it */
  date: number;
  text: string;
  html: string;
}

const API = "https://gmail.googleapis.com/gmail/v1/users/me";

export class Gmail {
  private access: { token: string; expires: number } | null = null;

  constructor(private creds: GmailCredentials) {}

  /** Message ids matching a Gmail search (same syntax as the search box), newest first. */
  async search(query: string, max = 500): Promise<string[]> {
    const ids: string[] = [];
    let pageToken: string | undefined;
    do {
      const params = new URLSearchParams({ q: query, maxResults: String(Math.min(100, max - ids.length)) });
      if (pageToken) params.set("pageToken", pageToken);
      const r = await this.get<{ messages?: { id: string }[]; nextPageToken?: string }>(`/messages?${params}`);
      ids.push(...(r.messages ?? []).map((m) => m.id));
      pageToken = r.nextPageToken;
    } while (pageToken && ids.length < max);
    return ids;
  }

  async message(id: string): Promise<GmailMessage> {
    const m = await this.get<RawMessage>(`/messages/${id}?format=full`);
    const header = (name: string) => m.payload?.headers?.find((h) => h.name.toLowerCase() === name)?.value ?? "";
    const parts = { text: "", html: "" };
    collectParts(m.payload, parts);
    return {
      id: m.id,
      threadId: m.threadId,
      subject: header("subject"),
      from: header("from"),
      date: Number(m.internalDate ?? 0),
      text: parts.text,
      html: parts.html,
    };
  }

  private async get<T>(path: string): Promise<T> {
    const res = await fetch(API + path, { headers: { authorization: `Bearer ${await this.token()}` } });
    if (!res.ok) throw new Error(`Gmail ${res.status}: ${(await res.text()).slice(0, 200)}`);
    return res.json() as Promise<T>;
  }

  private async token(): Promise<string> {
    if (this.access && this.access.expires > Date.now() + 60_000) return this.access.token;
    const res = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        client_id: this.creds.clientId,
        client_secret: this.creds.clientSecret,
        refresh_token: this.creds.refreshToken,
        grant_type: "refresh_token",
      }),
    });
    if (!res.ok) {
      const text = await res.text();
      // invalid_grant: access was revoked, or the OAuth app is in Testing mode (7-day tokens)
      throw new Error(`Gmail sign-in failed (${res.status}): ${text.slice(0, 200)}. Run scripts/gmail-auth.mjs again.`);
    }
    const j = (await res.json()) as { access_token: string; expires_in: number };
    this.access = { token: j.access_token, expires: Date.now() + j.expires_in * 1000 };
    return j.access_token;
  }
}

interface RawPart {
  mimeType?: string;
  body?: { data?: string };
  parts?: RawPart[];
  headers?: { name: string; value: string }[];
}
interface RawMessage {
  id: string;
  threadId: string;
  internalDate?: string;
  payload?: RawPart;
}

function collectParts(p: RawPart | undefined, out: { text: string; html: string }) {
  if (!p) return;
  if (p.body?.data) {
    if (p.mimeType === "text/plain") out.text += decodeB64Url(p.body.data);
    else if (p.mimeType === "text/html") out.html += decodeB64Url(p.body.data);
  }
  for (const c of p.parts ?? []) collectParts(c, out);
}

export function decodeB64Url(data: string): string {
  const bin = atob(data.replace(/-/g, "+").replace(/_/g, "/"));
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

/** Readable text from an HTML email: no styles, scripts or tags; table cells kept apart. */
export function htmlToText(html: string): string {
  return html
    .replace(/<(style|script|head)[\s\S]*?<\/\1>/gi, " ")
    .replace(/<(br|\/p|\/div|\/tr|\/h\d|\/li)[^>]*>/gi, "\n")
    .replace(/<\/t[dh]>/gi, " | ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .replace(/[ \t]+/g, " ")
    .replace(/\s*\n\s*/g, "\n")
    .replace(/\n{2,}/g, "\n")
    .trim();
}
