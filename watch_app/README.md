# watch_app — Squish (Wear OS)

One-screen Wear OS client for baby-svc. UI spec: `../design_handoff_huckleberry_watch/README.md`.

## Local setup (files the public repo can't carry)

1. `cp secrets.properties.example secrets.properties` and fill in BASE_URL +
   BEARER_TOKEN (real values: `../LOCAL_NOTES.md`, or `/etc/baby-svc.env` on the VM).
2. Copy the pinned TLS cert into resources:
   `cp ../python_service/deploy/baby-svc.crt app/src/main/res/raw/baby_svc.crt`
   (gitignored — the SAN embeds the VM address).
3. Open `watch_app/` in Android Studio, or `./gradlew assembleDebug`.

## Run

- **Emulator:** Device Manager → "Wear OS Large Round" AVD → run.
- **Pixel Watch 3:** wireless debugging (see ARCHITECTURE.md §Development &
  testing workflow), then run on the paired device.

## Architecture notes

- Server is the source of truth; UI polls `/status` on resume and ticks
  locally on the monotonic clock between polls.
- Pause semantics follow the SERVER (freeze on pause, jump forward on
  resume) — deliberately overriding the handoff's baseElapsed model.
- TLS: OkHttp trusts exactly `res/raw/baby_svc.crt` (no system CAs needed).
- Not yet implemented from the handoff: tiled doodle wallpaper layer,
  reduced-motion fallback, ambient/always-on handling, Tile/complication.
