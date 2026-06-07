# garmin_app — Squish for Fenix 8 Solar (Connect IQ)

Monkey C client of baby-svc. MIP-simplified take on the Squish design: flat
state colors, big timer, no idle animation. Same optimistic-update state
machine as the Wear OS app.

Targets: `fenix8solar51mm` (280×280) + `fenix8solar47mm` (260×260), CIQ 6.

## Controls

- **START button / screen tap** — contextual action (Start → Pause → Resume)
- **DOWN button** — Stop (only while sleeping/paused)
- **BACK** — exit

## TLS (important)

Garmin has no cert-pinning API and validates against system trust stores, so
this client MUST use the public Let's Encrypt endpoint (**:8443**), not the
pinned self-signed :8001 that the Wear OS app uses. During development we
point at the disposable Test-child instance on :8444.

## Local setup

1. Connect IQ SDK via Garmin SDK Manager (tested with 9.1.0).
2. Developer key: `~/.garmin/developer_key.der`
   (`openssl genrsa 4096` → `pkcs8 -topk8 -outform DER -nocrypt`).
3. `cp source/Secrets.mc.example source/Secrets.mc`, fill in
   (real values: `../LOCAL_NOTES.md`).

## Build / simulate / sideload

```bash
SDK="$HOME/Library/Application Support/Garmin/ConnectIQ/Sdks/<current>"
"$SDK/bin/monkeyc" -f monkey.jungle -d fenix8solar51mm \
  -o build/squish.prg -y ~/.garmin/developer_key.der

"$SDK/bin/connectiq"                                 # start simulator
"$SDK/bin/monkeydo" build/squish.prg fenix8solar51mm # run app in it

# Sideload: connect the watch over USB (MTP — use OpenMTP or Android File
# Transfer on macOS) and copy build/squish.prg into GARMIN/Apps/.
# The app appears in the activities/apps list after a disconnect.
```

## Files

- `source/BabyState.mc` — state + optimistic predict/reconcile (mirrors
  watch_app's SleepViewModel)
- `source/BabyApi.mc` — makeWebRequest wrapper
- `source/BabyView.mc` — MIP rendering, 1s tick
- `source/BabyDelegate.mc` — START/tap + DOWN handling (DOWN arrives as the
  onNextPage behavior, not raw onKey)
