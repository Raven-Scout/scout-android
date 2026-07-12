# Scout Android

Scout's **third surface** — the Android companion to the [Scout engine](https://github.com/Raven-Scout/scout-plugin) (scheduled
brain) and [Scout.app](https://github.com/Raven-Scout/Scout) (macOS daily driver).

What it does (v1):

- **Action Items** — today's vault list on your phone, sectioned 💡🔴🟡🟢✅, with mark-done
  and snooze write-back through `scoutctl` (the markdown stays canonical; this app is another
  wrapper around it, same as Scout.app).
- **Notification capture** — `NotificationListenerService` persists every system notification
  to a local Room DB, then forwards batches to the Mac-side bridge, which appends them to
  `~/Scout/.scout-inbox/phone-notifications-YYYY-MM-DD.jsonl` for scheduled Scout sessions to
  consume. This is the phone-as-connector path that replaces the Google-Messages WebView bridge.
- **FCM push (dormant)** — code-complete receiver + deep links; lights up when you add a
  Firebase config (below). Until then the Settings screen shows "Not configured".

Deferred to v2 (per the vault spec): SMS/`Telephony.Sms` ingestion, Health Connect,
QR-pairing, the `ConnectorModule` framework. RCS messages already arrive via notification
capture of `com.google.android.apps.messaging`.

## Quickstart

### 1. Start the bridge on the Mac

```bash
bridge/run-bridge.sh          # listens on :8787, prints the pairing secret
```

The secret auto-generates to `~/.scout-secrets/scout-bridge.secret` (0600). The bridge wraps
`scoutctl` — make sure it's on PATH (`~/.local/bin/scoutctl`).

### 2. Build + install the app

```bash
export JAVA_HOME=~/.jdks/temurin-21/Contents/Home
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk    # USB debugging on
```

### 3. Pair

**QR (recommended):** the bridge prints a QR code on startup (`brew install qrencode` for the
nice rendering; otherwise it prints the pairing link as text). In the app: Settings →
**Scan QR to pair** — URL + secret fill themselves and the connection is tested automatically.
Scanning with the stock camera app also works: the `scout://pair` link opens Scout and pairs.

**Manual fallback:** Settings → Bridge URL `http://<mac-lan-ip>:8787` + the printed secret →
**Test connection**. Either way, grant Notification Access afterwards and pick which apps Scout
may monitor (Settings → "Choose apps to monitor" — capture is opt-in, nothing is captured
until you select apps).

Transport is plain HTTP on the LAN, authenticated by the bearer secret. For off-LAN access,
put both devices on a Tailscale tailnet and use the Mac's tailnet IP — no code changes needed.

## Firebase (10 minutes, optional — enables push)

1. [console.firebase.google.com](https://console.firebase.google.com) → create project →
   add Android app with package `com.jordanrburger.scout`.
2. Download `google-services.json` into `app/` (gitignored) and rebuild. The build applies the
   google-services plugin only when that file exists, and the app subscribes to topic
   `scout-default` on next launch.
3. Engine-side sender (follow-up work in `~/scout-plugin`): POST to the FCM HTTP v1 API with a
   service account, data payload `{"title","body","prefix"?,"urgency":"high"|"normal"}`.

## Control Center (v0.3.0)

The **Control Center** tab is a remote mirror of the macOS Scout.app Control Center, served by the
bridge's `GET /status` (parsed from `.scout-logs/` + `schedule.yaml` + `connector-health.md`):

- **Now** — is Scout running, and which session type; **next scheduled run**; **today's token spend**;
  **connector health** (ok / dark / unknown dots); a **recent-runs** list with outcome chips.
- **Run a session now** — pick consolidation / briefing / research / dreaming → a confirm dialog
  (it spawns a real run on the Mac and **spends tokens**) → the bridge launches the matching
  `run-*.sh` detached. Budget is pre-checked; if over budget you get a "Run anyway" prompt. One run
  at a time (guarded by the runner's existing lock).
- **Retry** — a failed run's detail view has a Retry button (re-runs with `SCOUT_RETRY_OF`).

The Control Center polls `/status` every ~15s while open (5s briefly after you launch a run).

## Development

```bash
./gradlew test                              # JVM unit tests (no emulator needed)
(cd bridge && python3 -m unittest discover tests)   # bridge tests
bridge/tests/e2e-smoke.sh                   # end-to-end against a fixture-copy vault
```

Design specs + implementation plans are maintained privately (they reference the
author's personal Scout vault); this public repo carries the app + bridge code,
its tests, and synthetic test fixtures.

## License & legal

This app is open-source under the [MIT License](LICENSE).

Scout is local-first and collects no data of its own. Captured notifications are stored in a
local database on your phone and forwarded **only to your own paired Mac over your local
network** — never to us or any third-party server. The app does not read SMS or health data, and
push notifications (FCM) are dormant unless you add your own Firebase config. See the project's
shared legal documents:

- **Privacy Policy** — https://raven-scout.github.io/scout-plugin/privacy.html
- **Terms of Use** — https://raven-scout.github.io/scout-plugin/terms.html
- **[Security Policy](https://github.com/Raven-Scout/.github/blob/main/SECURITY.md)** · **[Code of Conduct](https://github.com/Raven-Scout/.github/blob/main/CODE_OF_CONDUCT.md)**

Scout is an independent project, not affiliated with Anthropic, Microsoft, Keboola, Google, or any other company.
