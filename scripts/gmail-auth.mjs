#!/usr/bin/env node
// One-off Gmail sign-in. Opens Google's consent page; after you click Allow,
// saves a long-lived refresh token to .dev.vars as GMAIL_REFRESH_TOKEN and
// prints how to store it in Cloudflare.
//
// Reusable for other projects: pass a different scope as the first argument.
//   node scripts/gmail-auth.mjs [scope]
// Default scope: read-only Gmail.
//
// Needs .secrets/gmail-oauth-client.json (a "Desktop app" OAuth client).

import { createServer } from "node:http";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { randomBytes, createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const scope = process.argv[2] ?? "https://www.googleapis.com/auth/gmail.readonly";
const client = JSON.parse(readFileSync(join(root, ".secrets/gmail-oauth-client.json"), "utf8")).installed;

const verifier = randomBytes(32).toString("base64url");
const challenge = createHash("sha256").update(verifier).digest("base64url");
const state = randomBytes(16).toString("hex");

const server = createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  if (url.pathname !== "/callback") return res.writeHead(404).end();
  const code = url.searchParams.get("code");
  if (url.searchParams.get("state") !== state || !code) {
    res.writeHead(400).end("Sign-in failed or was cancelled. Run the script again.");
    return finish(1, url.searchParams.get("error") ?? "bad state");
  }
  const tokenRes = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      code,
      client_id: client.client_id,
      client_secret: client.client_secret,
      redirect_uri: redirectUri,
      grant_type: "authorization_code",
      code_verifier: verifier,
    }),
  });
  const tok = await tokenRes.json();
  if (!tok.refresh_token) {
    res.writeHead(500).end("Google didn't return a refresh token. See the terminal.");
    return finish(1, JSON.stringify(tok));
  }
  saveVar("GMAIL_REFRESH_TOKEN", tok.refresh_token);
  res.writeHead(200, { "content-type": "text/html" }).end("<h2>Done. You can close this tab.</h2>");
  console.log("\n✅ Signed in. Refresh token saved to .dev.vars as GMAIL_REFRESH_TOKEN.");
  finish(0);
});

let redirectUri;
server.listen(0, "127.0.0.1", () => {
  redirectUri = `http://127.0.0.1:${server.address().port}/callback`;
  const auth = new URL("https://accounts.google.com/o/oauth2/v2/auth");
  auth.search = new URLSearchParams({
    client_id: client.client_id,
    redirect_uri: redirectUri,
    response_type: "code",
    scope,
    access_type: "offline",
    prompt: "consent", // always hand back a refresh token
    state,
    code_challenge: challenge,
    code_challenge_method: "S256",
  }).toString();
  console.log("Open this link in your browser and sign in:\n\n" + auth + "\n");
  // WSL: hand it to the Windows browser; elsewhere, xdg-open
  execFile("wslview", [auth.toString()], (e) => e && execFile("xdg-open", [auth.toString()], () => {}));
});

function saveVar(name, value) {
  const file = join(root, ".dev.vars");
  let text = existsSync(file) ? readFileSync(file, "utf8") : "";
  const line = `${name}=${value}`;
  text = new RegExp(`^${name}=.*$`, "m").test(text) ? text.replace(new RegExp(`^${name}=.*$`, "m"), line) : text.trimEnd() + "\n" + line + "\n";
  writeFileSync(file, text, { mode: 0o600 });
}

function finish(code, err) {
  if (err) console.error("❌", err);
  server.close();
  setTimeout(() => process.exit(code), 100);
}
