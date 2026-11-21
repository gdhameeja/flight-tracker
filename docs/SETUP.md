# Setup

## Flight data

- **AeroDataBox** (RapidAPI, free Basic plan: 400 units a month). A flight
  lookup costs about 2 units; the schedule spends about 40 a flight. Automatic
  checks pause below 40 units left, refreshing by hand below 6, so the plan is
  never exceeded. Settings in the app shows what's left.
- **FlightAware AeroAPI** (Personal plan): coming once the account is approved.

## Google Cloud / Firebase

One project does both push alerts and Gmail: **flight-tracker-a162b**
(Firebase console and console.cloud.google.com).

| What | Where it lives |
|---|---|
| Android app registered as `com.gdhameeja.flighttracker` | Firebase → Project settings → Your apps |
| `google-services.json` (app's Firebase config) | `android/app/google-services.json` (git-ignored); GitHub secret `GOOGLE_SERVICES_JSON_B64` |
| Service-account key (backend sends pushes) | Cloudflare secret `FCM_SERVICE_ACCOUNT`; make a new one at Firebase → Project settings → Service accounts |
| Gmail OAuth client (Desktop app) | Client ID `255457130972-jqffdvj0qvgthr6lmr9n167qhbnt3rkp.apps.googleusercontent.com`; JSON in `.secrets/gmail-oauth-client.json` (git-ignored). Manage at Google Auth Platform → Clients |
| OAuth app publishing status | Must stay **In production** (Google Auth Platform → Audience), or Gmail access expires every 7 days |

The client ID isn't secret; the client secret inside the JSON is.

## Backend (Cloudflare Worker)

From `worker/`:

```
npm install
npm test                         # unit tests
npx wrangler deploy              # publish
npx wrangler tail                # live logs
npx wrangler d1 execute flight-tracker --remote --file=schema.sql   # after schema changes (safe to rerun)
```

Secrets (never in the repo):

```
npx wrangler secret put APP_TOKEN          # the app sends this with every request
npx wrangler secret put AERODATABOX_KEY
npx wrangler secret put FCM_SERVICE_ACCOUNT < firebase-service-account.json
```

Local copies live in `../.dev.vars` (git-ignored).

## App releases (no cable)

Every push to `main` that touches the app builds the APK on GitHub and publishes
it as release `v1.<commit count>`. In the app: **Settings → Check for updates →
Download and install**. The first time, Android asks you to allow installs from
Flights.

The release must be signed with the key the app on your phone was installed
with. It's the same key as running-app. Repo secret `DEBUG_KEYSTORE_B64`
(**GitHub → Settings → Secrets and variables → Actions**) holds:

```
base64 -w0 .android-user/debug.keystore
```

Local build: `docker/build-apk.sh` (APK in `android/app/build/outputs/apk/debug/`).

## Connecting the app

The server address is built into the app (`DEFAULT_SERVER_URL` in
`android/app/build.gradle`). The app token is handed over with a pairing code:

```
scripts/pair.sh        # prints a 6-digit code, valid 10 minutes, one use
```

Type it in the app: **Settings → Pairing code → Connect**. Five wrong codes
lock pairing until you make a new code. **Advanced** in the same screen takes
an address and token by hand instead.
