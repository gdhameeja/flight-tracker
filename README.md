# Flights

A personal flight tracker for Android: your trips, live status, gates, delays
and baggage belts, with alerts when something changes. Built for flying around
India, the UAE and Asia.

- `android/`: the app (Kotlin, Jetpack Compose, Material 3)
- `worker/`: the backend, a Cloudflare Worker with a D1 database. It checks your
  flights on a schedule (`worker/src/schedule.ts`) and records what changed
  (`worker/src/changes.ts`)
- `docker/`: builds the APK (same setup as running-app)
- `.github/workflows/release.yml`: every push to `main` publishes an APK
  release; the app's **Check for updates** installs it

Setup and day-to-day commands: [docs/SETUP.md](docs/SETUP.md).

No secrets live in this repo: keys are Cloudflare secrets, the signing key is a
GitHub secret, and your local copies are in `.dev.vars` (git-ignored).
