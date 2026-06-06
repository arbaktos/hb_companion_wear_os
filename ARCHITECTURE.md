# Huckleberry Wear OS Companion — Architecture

> A Wear OS mini-app for Pixel Watch 3 that controls baby sleep tracking in Huckleberry. Watch has no SIM and gets internet via the paired Android phone over Bluetooth (Wear OS proxies network traffic automatically). Backend is a self-hosted Python service on our own VM.

## Goal

A one-screen Wear OS app implementing the **"Squish" design** (see `design_handoff_huckleberry_watch/README.md` — the authoritative UI spec):

- One central blob showing the current timer; state communicated by color + animation, no labels.
- Three states: **Awake** (time since last sleep ended; tap = Start), **Sleeping** (live elapsed; tap = Pause), **Paused** (frozen elapsed; tap = Resume). **Stop** as a secondary bottom pill in Sleeping/Paused.

Backed by Huckleberry (baby tracking app) data via the unofficial Python client.

Later: a full Android phone app with amended/extended Huckleberry functionality, built on the same API. **Daily awake/asleep totals — originally a watch goal — are deliberately deferred to the phone app** (decision 2026-06-06; the Squish design intentionally shows only the current-state timer).

## Chosen architecture: VM-hosted Python service + direct HTTPS clients

Two layers now, three eventually. The VM is the single API boundary; every client is a thin HTTPS consumer.

```
┌──────────────────────┐  HTTPS, self-signed   ┌─────────────────────────────────┐
│  Wear OS watch app   │  cert pinned in app   │  VM (shared w/ belgrade_flat)   │
│  (Kotlin + Compose   │  (via phone's BT      │  ┌───────────────────────────┐  │
│   for Wear, "Squish")│   network proxy)      │  │ FastAPI + uvicorn :8001   │  │
│  blob UI, 1 screen   │ ────────────────────▶ │  │ TLS terminated by uvicorn │  │
└──────────────────────┘ ◀──────────────────── │  │ (self-signed cert)        │  │
 https://<vm-ip-dashed>    Bearer token auth    │  │ huckleberry-api lib       │  │
   .nip.io:8001                                │  │                           │  │
┌──────────────────────┐  HTTPS                │  │   gRPC ──▶ Firestore      │  │
│  Phone app (LATER)   │ ────────────────────▶ │  └───────────────────────────┘  │
│  amended Huckleberry │ ◀──────────────────── │  (belgrade webhook owns :8000)  │
│  functionality       │                       └─────────────────────────────────┘
└──────────────────────┘
```

## Why this shape (decisions and what we ruled out)

### Fork 1: Where does Huckleberry logic live? → A Python service

The unofficial library README is explicit:

> Huckleberry's Firebase Security Rules block non-SDK requests. Direct REST API calls return 403 Forbidden. This library uses the official Firebase SDK which uses gRPC.

Going watch-direct would mean:
- Shipping the Android Firestore SDK on Wear OS
- Embedding Firebase project config + API key in the watch APK
- Re-implementing Huckleberry's document shapes and timer state machine in Kotlin
- Re-deploying the watch (sign + sideload) every time Huckleberry tweaks their schema

**Verdict:** a Python service that wraps the existing reverse-engineered library is the only sensible boundary. Clients never talk to Firebase.

### Fork 2: Where does the Python service run? → Our own VM (public IP/domain)

History of this fork:

- ❌ **Fly.io** — works, but requires a credit card on file, puts Huckleberry creds with a third party, and has ~1.5s cold starts.
- ❌ **Termux on the phone + BT companion relay** *(previously chosen, now superseded)* — won on $0 cost and creds-stay-home, but was operationally the hardest part of the whole design: battery optimizers, Doze, wake-locks (2–5%/day battery), no remote logs, uncertain Python 3.14 availability, bash supervisor loops instead of real service management.
- ✅ **Self-owned VM** — became available and dominates:

| | Termux on phone | VM |
|---|---|---|
| Reliability | Doze/battery optimizers break it | Always on, systemd |
| Logs | Pick up the phone / SSH into Termux | SSH + `journalctl` from anywhere |
| Python version | Termux package roulette | We install whatever we need |
| Service management | bash `while true` loop | systemd unit with restart policy |
| Battery cost | wake-lock 2–5%/day | None |
| Creds location | Phone | VM **we own** — still no third party |
| Cost | $0 | VM already paid for / owned |

The privacy argument that killed Fly doesn't apply: it's our machine.

### Fork 3: Do we need a phone relay app? → No. Watch talks to the VM directly

The old companion app existed *only* because the service lived at `127.0.0.1` on the phone — unreachable from the watch. With the service on a public domain:

- Wear OS transparently proxies network traffic over Bluetooth through the paired phone. A watch app makes ordinary HTTPS calls; no SIM, no WiFi required (WiFi is used when available, e.g. at home).
- The entire BT `MessageClient` protocol — request-ID correlation, `/api/response/{id}`, `CompletableDeferred` maps — is deleted. The watch does plain request/response HTTP with a timeout.
- "Phone must be nearby" remains the de-facto constraint (no SIM), unchanged from before.

**The phone app survives in a new role:** not a relay, but a *second client* with amended/extended Huckleberry functionality. Built **after** the watch app, against the same VM API. It holds no credentials and contains no Firestore logic, same as the watch.

### Fork 4: How is the service exposed + authenticated? → Self-signed TLS (pinned) + static bearer token

The VM is the same one running `belgrade_flat_notifier`'s Telegram webhook. History of this fork:

- ❌ **Cloudflare quick tunnel (what belgrade uses)** — `cloudflared tunnel --url ...` mints an ephemeral `https://<random>.trycloudflare.com` URL **that rotates on every restart**. Belgrade tolerates this by re-registering the URL with Telegram on each boot (`vm/bootstrap.sh`). The watch has no re-registration channel — a rotating URL can't be baked into an APK. Non-starter.
- ❌ **Cloudflare named tunnel** — stable hostname, no open ports, clean — but requires a domain in the Cloudflare account, and there isn't one. Not worth ~$10/yr + a renewal on the critical path for this project. (If a domain ever lands in the CF account for other reasons, this becomes the better option again — belgrade's stable-webhook upgrade wants it too.)
- ❌ **Plain HTTP to the VM's IP** — works, and the threat model is mild (worst case: a sniffed bearer token lets someone toggle sleep timers; Huckleberry creds never travel). But the token would be readable on any shared/public WiFi the phone joins, and Android requires a cleartext-traffic opt-out anyway. Rejected for one tier more effort buying real encryption:
- ✅ **Self-signed cert, terminated by uvicorn, pinned in the clients** — addressed as **`baby` under nip.io** (wildcard DNS that resolves `<vm-ip-dashed>.nip.io` → `<vm-ip>`; free, no account, gives us a real hostname without owning a domain):
  ```bash
  # once, on the VM (10-year; hostname + raw-IP fallback in SAN):
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -keyout baby-svc.key -out baby-svc.crt \
    -subj "/CN=baby-svc" \
    -addext "subjectAltName=DNS:<vm-ip-dashed>.nip.io,IP:<vm-ip>"
  # uvicorn: --ssl-keyfile baby-svc.key --ssl-certfile baby-svc.crt
  ```
  The watch (and later phone) app ships `baby-svc.crt` as a trust anchor via Android `network-security-config` scoped to that hostname — trusts exactly this cert, nothing else. No CA, no Cloudflare, no renewals for 10 years; the pin is *stronger* than public CA trust. Clients call `https://<vm-ip-dashed>.nip.io:8001` (or `https://<vm-ip>:8001` if nip.io's DNS is ever down — both names are in the cert).
  - Note: nip.io can't be used for Let's Encrypt in practice (all users share one registered domain's rate limits) or for CF tunnels (not our zone) — it's purely a resolver convenience here.
- **Port 8001** open in the VM firewall (belgrade's uvicorn owns `127.0.0.1:8000` and stays loopback-only).

Auth on top of the transport:

- ✅ **Static bearer token** — one long random token (`openssl rand -hex 32`) baked into client config, checked by FastAPI middleware on every route except `/healthz`. Right-sized for a single-user personal service. (Same pattern as belgrade's `WEBHOOK_SECRET_TOKEN`.)
- ❌ **Network-level only (Tailscale ACLs / LAN)** — Wear OS Tailscale was already ruled out as the flakiest possible link; LAN-only would limit tracking to home.

Caveat to accept: **the cert is bound to the VM's IP.** If the VM's IP ever changes (provider migration), regenerate the cert and rebuild the clients. For a VPS with a static IP this is a rare event, but it's the price of skipping a domain.

## What each layer owns

### Wear OS watch app

Pure UI + HTTP client. Knows nothing about Huckleberry internals or Firestore.

- **UI: the "Squish" design** — `design_handoff_huckleberry_watch/README.md` is the authoritative spec (tokens, palettes, motion, state machine). One circular screen, central blob, three states (Awake / Sleeping / Paused), Stop pill in Sleeping/Paused. Jetpack Compose for Wear; recreate natively, don't transpile the HTML prototype.
- **No daily totals on the watch** — deferred to the phone app by decision.
- Pulls `/status` on screen resume and after each action; ticks the displayed timer locally between polls — no background polling.
- Plain OkHttp/Ktor calls with `Authorization: Bearer <token>` header.
- 10s timeout per request; clear error surface on timeout or 5xx ("server unreachable — is the phone nearby?").
- Server is the source of truth for state; local persistence across process death matters less than the handoff assumed (a `/status` on resume rebuilds everything).

### VM Python service

All Huckleberry logic. The single API boundary for every client.

- FastAPI + uvicorn on `127.0.0.1:8001` (`--workers 1`, `--limit-max-requests 1000`), exposed via Cloudflare named tunnel.
- Managed by a **systemd unit** with `Restart=always`.
- `huckleberry_api.HuckleberryAPI` as a module-level singleton, created in FastAPI `lifespan` startup, never recreated.
- One shared `aiohttp.ClientSession` (the lib needs it injected).
- SQLite at `~/baby-svc/state.db` for: refresh token + cached `child_uid`.
- Env vars in `/etc/baby-svc.env` (root-owned where written, `chmod 600`, belgrade pattern): Huckleberry email + password + timezone + API bearer token.
- Bearer-token middleware on all routes except `/healthz`.
- **No** `setup_sleep_listener` — long-lived gRPC streams give no benefit to a polling watch.

### Phone app (later)

Second client with amended Huckleberry functionality — scope TBD after the watch app ships. Same contract: HTTPS + bearer token, no creds, no Firestore knowledge. New endpoints get added to the VM service as that scope firms up.

Confirmed scope so far: **daily awake/asleep totals** (cut from the watch design). A future `/stats/today` endpoint will own the `list_sleep_intervals(midnight, now)` summing logic.

## HTTP endpoints (clients → VM)

| Method | Path | Auth | What it does |
|---|---|---|---|
| `GET` | `/healthz` | none | Pure liveness, no upstream calls. |
| `GET` | `/status` | bearer | `sleep_ref.get()` (1 Firestore read) + most recent completed interval. Returns everything the Squish face needs: `{ state: "awake"\|"sleeping"\|"paused", session_started_at, session_elapsed_sec, last_sleep_end }`. |
| `POST` | `/sleep/start` | bearer | `start_sleep(child_uid)` |
| `POST` | `/sleep/pause` | bearer | `pause_sleep(child_uid)` |
| `POST` | `/sleep/resume` | bearer | `resume_sleep(child_uid)` |
| `POST` | `/sleep/stop` | bearer | `complete_sleep(child_uid)` |
| `GET` | `/stats/today` | bearer | **(later, for the phone app)** `list_sleep_intervals(midnight, now)` → `{ awake_today_sec, asleep_today_sec }`. |

**`/status` ↔ design mapping:** the Squish timer logic needs `lastSleepEnd` (Awake counts up from it), and the current session's elapsed/paused state. The server computes `session_elapsed_sec`; the watch ticks locally between polls. Daily totals were dropped from this endpoint when they moved to the phone app — `/status` got *cheaper* (no interval summing on the hot path; just the sleep doc + the latest completed interval for `last_sleep_end`).

**⚠️ Pause semantics — server overrides the design handoff.** The handoff specifies an accumulating `baseElapsed` model (pause time subtracted from the total). Huckleberry's actual Firestore behavior (verified in the lib source) is simpler: pause freezes the visible timer at `timerEndTime`, resume un-pauses *without* subtracting the gap, and the final duration is plain wall-clock start→end. The watch must mirror the server (`status_logic.py` encodes this), or it would disagree with the Huckleberry phone app. Concretely: after resume, the displayed timer jumps forward to include the paused period.

**Expected latency (watch):** BT proxy adds overhead vs. native WiFi, but the service is always warm — no Termux idle wake-up, no Fly cold start. Expect ~300–800ms warm; the watch on home WiFi will be at the low end.

## Service lifecycle (now boring, by design)

One systemd unit on the shared VM, mirroring the belgrade pattern (unprivileged service user, `/etc/<name>.env` secrets file, `chmod 600`):

- **`baby-svc.service`**: uvicorn on `0.0.0.0:8001` with `--ssl-keyfile/--ssl-certfile` (self-signed cert), `Restart=always`, `RestartSec=2`, `EnvironmentFile=/etc/baby-svc.env`. Runs as its own unprivileged user (same pattern as the `belgrade` user).
- **Firewall:** open `8001/tcp`; everything else stays closed.
- **Coexistence:** belgrade's webhook owns `127.0.0.1:8000` and its own quick tunnel; we don't touch either.
- **Updates:** `git pull && pip install -r requirements.txt && systemctl restart baby-svc`.
- **Logs:** `journalctl -u baby-svc -f` via SSH.

This entire section used to be the hardest part of the architecture (Termux boot scripts, wake-locks, supervisor loops). On a VM it's commodity ops.

## Auth & secrets

- **Clients ↔ VM:** TLS via self-signed cert pinned in the apps + static bearer token. Token lives in `/etc/baby-svc.env` on the VM and in client build config. Rotate by changing both. The cert (`baby-svc.crt`) is cryptographically public material, but its SAN embeds the VM's IP — since the repo is public, it stays **gitignored** (kept locally + regenerable on the VM); the key (`baby-svc.key`) stays on the VM only.
- **VM ↔ Huckleberry:** email + password in `/etc/baby-svc.env`. Refresh token + `child_uid` in `~/baby-svc/state.db`.
- **Backups:** `/etc/baby-svc.env` (Huckleberry creds + bearer token) should be backed up off-VM (password manager). `state.db` is reconstructible from creds — no backup needed. `baby-svc.key` is regenerable (just rebuild clients) — backup optional.
- **Exposure note:** port 8001 is open to the internet — uvicorn will see background scanner noise. TLS + bearer middleware rejects all of it; the 6-route surface keeps risk small. Keep the VM patched.

## Memory & resources

- One `HuckleberryAPI` singleton (module-level, lifespan-managed).
- `--limit-max-requests 1000` recycles the uvicorn worker as a backstop against drift.
- No real-time listeners — polling only.
- Expected steady-state RSS: ~150 MB. A non-issue on a VM.

## Key library finding — `huckleberry_api`

Source: https://github.com/Woyken/py-huckleberry-api

What we learned from reading `src/huckleberry_api/api.py` (2389 lines):

| Need | API |
|---|---|
| Auth | `await api.authenticate()`, `await api.ensure_session()`, `await api.refresh_session_token()` |
| Get child UID once | `await api.get_user()` → `.childList[0].cid` (cache it) |
| Start | `await api.start_sleep(child_uid)` |
| Pause | `await api.pause_sleep(child_uid)` (native, not faked) |
| Resume | `await api.resume_sleep(child_uid)` |
| Stop | `await api.complete_sleep(child_uid)` |
| Current state | `client.collection("sleep").document(child_uid).get()` → `FirebaseSleepDocumentData` with `timer.active`, `timer.paused`, `timer.timerStartTime` |
| Today's totals | `await api.list_sleep_intervals(child_uid, midnight, now)` — returns list of `FirebaseSleepIntervalData` with `duration` in seconds. Sum them. |

**Important constraints:**
- Python 3.14+ required (per the lib's `pyproject.toml`). On our own VM this is a non-issue — install 3.14 directly (e.g. via `uv`).
- gRPC channel is loop-bound (lib comment: "grpc.aio channels are loop-bound and cannot be reused across loops"). With `--workers 1` this is fine.
- No explicit `close()` method on `HuckleberryAPI`. OS reclaims on process exit.
- ID tokens last 1 hour, refresh tokens are long-lived. Persist refresh token to SQLite, refresh on cold start.

## Development & testing workflow (watch)

- **Day-to-day: Wear OS emulator** in Android Studio (Device Manager → "Wear OS Large Round" AVD, 454px — matches Pixel Watch 3). Fast iteration on the blob animations, palette crossfades, and layout. Note: the emulator's network goes through the Mac directly, so it does *not* exercise the BT-proxy path.
- **Real device: Pixel Watch 3 over wireless adb** (no USB data on this watch):
  1. Watch: Settings → System → About → tap Build number 7× → Developer options → enable **ADB debugging** + **Wireless debugging**.
  2. Watch + Mac on the same WiFi.
  3. Wireless debugging → Pair new device → `adb pair <ip>:<pair-port>` with the code, then `adb connect <ip>:<connect-port>` (different port). Android Studio's Device Manager QR-pairing flow also works and is less fiddly.
  4. Connection drops when the watch sleeps/roams — re-`adb connect` is routine.
- **Real-device test matrix:** HTTPS over the BT proxy (emulator can't), animation performance, ambient/always-on, Comfortaa rendering on the physical display.

## Known costs of this choice (be honest)

1. **Public attack surface.** Port 8001 is internet-reachable; uvicorn faces scanner noise directly. Mitigated by TLS + bearer token + small surface (6 routes), but it's a real difference vs. loopback or a tunnel.
2. **VM is a dependency.** If the VM is down, tracking is down. (But VM uptime ≫ Termux-under-Doze uptime.) It's also shared with belgrade_flat_notifier — be a good neighbor (port 8001, own service user, own unit).
3. **Creds live on the VM.** Our machine, but still one more box holding the Huckleberry password. Keep it patched.
4. **Cert is pinned to the VM's IP.** IP change (provider migration) = regenerate cert + rebuild both client apps. Rare, but it's the price of skipping a domain.
5. **Two-then-three codebases.** Watch app now, phone app later. Each is a thin client, and there's no BT protocol to maintain between them.

## Recommended build order

1. **VM Python service** — self-signed cert, systemd unit, firewall rule, bearer middleware, prove `/status` returns sane numbers against the real Huckleberry account via `curl --cacert baby-svc.crt https://<vm-ip-dashed>.nip.io:8001/status` from anywhere. Validates the whole foundation before any Android work.
2. **Watch UI** — Compose for Wear, the Squish design per the handoff spec, plain HTTPS client. Develop on the emulator, verify on the real watch. The contract is already concrete and curl-testable.
3. **Phone app (later)** — amended Huckleberry functionality + daily totals (`/stats/today`), scope TBD; extends the same API.

## Open questions still to resolve

- **Phone app scope** — which Huckleberry functionality gets amended/extended (daily totals confirmed in); drives future endpoint design. Deferred until after the watch ships.
- **Secrets backup** — `/etc/baby-svc.env` into a password manager.
- **Is the VM's IP (<vm-ip>) static?** The pinned cert and the nip.io hostname both encode it. Confirm with the hosting provider.

## Resolved questions (for the record)

- ~~Termux Python 3.14 availability~~ — moot, we control the VM.
- ~~Termux lifecycle / wake-locks / boot scripts~~ — replaced by systemd.
- ~~BT MessageClient protocol design~~ — deleted along with the relay role of the phone app.
- ~~Caddy vs. nginx vs. Cloudflare tunnel for TLS~~ — none; uvicorn terminates TLS with a self-signed cert pinned in the clients. Quick tunnel rotates URLs (unusable), named tunnel needs a domain (don't have one), plain HTTP leaks the bearer token on shared WiFi.
- ~~Domain + Cloudflare~~ — not needed; pinned self-signed cert. Revisit only if a domain lands in the CF account for other reasons.
- ~~adb workflow for Pixel Watch 3~~ — wireless debugging (pair + connect over WiFi); emulator for day-to-day work.
- ~~Daily totals on the watch~~ — intentionally cut from the Squish design; moved to the future phone app (`/stats/today`).
- ~~Watch UI design~~ — the "Squish" handoff in `design_handoff_huckleberry_watch/` is the spec.

## File layout for this project (proposed)

```
huckleberry_companion/
├── ARCHITECTURE.md                    (this file)
├── design_handoff_huckleberry_watch/  (Squish design spec + prototype — UI source of truth)
├── python_service/                    (the VM side)
│   ├── pyproject.toml
│   ├── main.py                        (FastAPI app + bearer middleware)
│   ├── state.py                       (SQLite for refresh token + child_uid)
│   ├── deploy/
│   │   ├── baby-svc.service           (systemd unit, uvicorn :8001 + TLS)
│   │   ├── gen-cert.sh                (one-shot self-signed cert generation)
│   │   ├── baby-svc.crt               (pinned by clients — gitignored: SAN has the IP, repo is public)
│   │   └── README.md                  (VM install steps, belgrade-pattern)
│   └── tests/
├── watch_app/                         (Wear OS app, Squish UI — build second)
│   ├── build.gradle.kts
│   └── app/src/main/kotlin/...
└── phone_app/                         (Android app: amended functionality + daily totals — later)
    └── ...
```
