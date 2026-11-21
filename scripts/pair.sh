#!/usr/bin/env bash
# Prints a 6-digit code (valid 10 minutes, one use). Type it in the app:
# Settings → Pairing code → Connect.
# Needs SERVER_URL and APP_TOKEN in .dev.vars (git-ignored).
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.dev.vars; set +a
: "${SERVER_URL:?Add SERVER_URL=https://flight-tracker.<you>.workers.dev to .dev.vars}"
curl -fsS -X POST "$SERVER_URL/api/pair" -H "Authorization: Bearer $APP_TOKEN" |
  node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const r=JSON.parse(s);console.log(`Pairing code: ${r.code}  (valid ${r.expiresInMinutes} minutes)`)})'
