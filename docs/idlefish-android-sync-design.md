# Idlefish Android Sync Design

## Purpose

Provide an Android-side queue and uploader that can be called from future VirtualApp collection or Hook code. This moves the pipeline toward the target design:

```text
Hook/collector finds event JSON
  -> IdlefishEventSyncer.enqueueAndUploadIfEnabled(...)
  -> app private pending queue
  -> HTTP ingest API
  -> app private sent queue
```

## Current Classes

`app/src/main/java/com/carlos/home/idlefish/IdlefishSyncConfig.java`

- Stores upload enable flag, ingest endpoint, discovery endpoint, device number, and optional HMAC secret in `SharedPreferences`.
- Upload is disabled by default.

`app/src/main/java/com/carlos/home/idlefish/IdlefishEventStore.java`

- Stores event JSON files under app private storage:
  - `files/idlefish_events/pending`
  - `files/idlefish_events/sent`
  - `files/idlefish_events/failed`
- Preserves event JSON as UTF-8.
- Adds `event_id` if missing.

`app/src/main/java/com/carlos/home/idlefish/IdlefishEventUploader.java`

- Batches pending events and posts them to the ingest server.
- Moves files to `sent` only after a successful 2xx response.
- Supports optional HMAC with the same signature contract as the Node MVP server.

`app/src/main/java/com/carlos/home/idlefish/IdlefishEventSyncer.java`

- Small async facade for future Hook code.
- Main entry point:

```java
IdlefishEventSyncer.enqueueAndUploadIfEnabled(context, eventJson);
```

`app/src/main/java/com/carlos/home/idlefish/IdlefishWebViewJsProbe.java`

- Current primary collector.
- Runs when the virtualized Idlefish `WebHybridActivity` is resumed.
- Finds the UC/WindVane WebView and calls `evaluateJavascript`.
- Reads only visible page state: `location.href`, `document.title`, and `document.body.innerText`.
- Parses service-score text into `idlefish_shop_service_score` and enqueues it through `IdlefishEventSyncer`.
- Parses workbench text into `idlefish_shop_identity` and uploads it to the discovery endpoint when configured.
- Extracts a safe whitelist of URL identity parameters from WebView `location.href` when present, such as `userId`, `sellerId`, `shopId`, `shopNo`, and `accountId`; sensitive URL keys such as token/session/auth are redacted or ignored.
- Installs a lightweight WebView-side WindVane/JSBridge probe. It records only safe bridge metadata (`module`, `method`, `api`, `v`) and whitelisted identity fields into `raw.web_bridge_signals`; it does not capture headers, cookies, tokens, signatures, or full request/response bodies.
- Deduplicates per WebView Activity instance to avoid repeated uploads while the same page is settling.

JSBridge verification status:

- 2026-05-13: WebView-side WindVane/JSBridge probe is confirmed to run on the fish-shop service score and workbench H5 pages without using native inline hooks.
- On the service score page it captured `WVIdleFishApi.callMtop` bridge calls for:
  - `mtop.alibaba.idle.shop.query.service.scores`
  - `mtop.alibaba.idle.shop.service.scores.category.query`
- This currently proves the bridge observation path is feasible, but it does not yet capture MTOP response bodies or replace the primary collector.
- The primary production collector is still DOM text parsing from `document.body.innerText`; `raw.web_bridge_signals` is sidecar evidence for future improvement.
- The WebView-side `fetch`/`XMLHttpRequest` hook has been tested, but current fish-shop pages did not expose useful signals through it (`networkSignals=0` in observed runs).

`app/src/main/java/com/carlos/home/idlefish/IdlefishDeepLinkReceiver.java`

- Opens the service-score H5 inside a selected VirtualApp user.
- Can receive upload config extras:
  - `sync_endpoint`
  - `discovery_endpoint`
  - `sync_enabled`
  - `device_no`
  - `device_secret`
- This lets the desktop wrapper enable uploads before opening the page.

## Development Endpoint

When the Node MVP server runs on the PC:

```powershell
node .\server\idlefish-ingest-server.js
```

Use `adb reverse` if the Android app should post to the PC:

```powershell
adb -s 6H5PY5GEZ9QGJJO7 reverse tcp:18080 tcp:18080
```

Then configure Android endpoint as:

```text
http://127.0.0.1:18080/api/v1/ingest/events
```

Without `adb reverse`, `127.0.0.1` means the phone itself, not the PC.

## Manual Upload Test

`HomeActivity` now has a temporary debug entry in the left menu:

```text
Idlefish sync test
```

This entry builds a simulated `idlefish_shop_service_score` event, stores it in the Android private pending queue, and immediately uploads pending events to the configured endpoint.

Local verification flow:

```powershell
node .\server\idlefish-ingest-server.js
adb -s <device-serial> reverse tcp:18080 tcp:18080
```

Then on the phone:

1. Open VirtualApp.
2. Open the left menu.
3. Tap `Idlefish sync test`.
4. Keep the default endpoint:
   `http://127.0.0.1:18080/api/v1/ingest/events`
5. Tap `Send`.

Expected server verification:

```text
http://127.0.0.1:18080/api/v1/events/latest
```

The debug event should appear under:

```text
debug-local:idlefish_shop_service_score
```

For repeated debug uploads, `latest` can look unchanged because it keeps only the newest value for the same shop and event type. Use this endpoint to see each accepted upload as a separate row:

```text
http://127.0.0.1:18080/api/v1/events/recent?limit=10
```

The fields to check are `received_at`, `event.event_id`, and `event.captured_at`.

Verification status:

- 2026-05-09 15:16: passed on device `6H5PY5GEZ9QGJJO7`.
- Installed package: `com.carlos.multiapp`.
- Foreground Activity during verification: `com.carlos.home.HomeActivity`.
- 2026-05-09 16:10: stable APK passed launch verification after disabling the MTOP inline-hook probe.
- Idlefish clone opened to the Idlefish home screen without `IdlefishMtopProbe` logs or crash logs.
- 2026-05-11: WebView JS collector verification passed on device `6H5PY5GEZ9QGJJO7`, VirtualApp user `0`.
  - Wrapper: `tools/collect_idlefish_webview_js.ps1`.
  - Uploaded event id: `93706427-9ec8-40f6-9e3b-81b23f4d41e3`.
  - Source: `webview_js`.
- 2026-05-11: Workbench identity discovery verification passed on device `6H5PY5GEZ9QGJJO7`, VirtualApp user `0`.
  - Wrapper: `tools/discover_idlefish_shop_identity.ps1`.
  - Uploaded event id: `ccc14004-10b9-415d-8ab6-ece8fd478cbe`.
  - Parsed identity: `device_001`, `一番市集次元漫物馆`, `动漫周边`, score `4.83`.

## Future Integration Points

1. Multi-shop orchestration
   - Loop over each VirtualApp user id, open the workbench H5, wait for `idlefish_shop_identity`, and let the server bind `device_no + collector_type + virtual_user_id` to shop metadata.
   - After binding, open the service-score H5 for each user id and upload `idlefish_shop_service_score`.
   - Current desktop wrappers:
     - `tools/discover_idlefish_virtual_shops.ps1`
     - `tools/collect_idlefish_virtual_service_scores.ps1`
   - Original app discovery and collection still uses the UIAutomator/OCR route and should be implemented separately as `collector_type=uiautomator`, `app_slot=0`.

2. UI/OCR fallback collector
   - Keep the desktop UIAutomator/OCR scripts available for pages where WebView JS cannot read text.

3. mtop/network Hook
   - The first SandHook/ART inline-hook prototype reached `MtopResponse`/`MtopRequest` hook installation, but crashed the Idlefish process on Android 14 during MTOP callback traffic.
   - Keep `ENABLE_IDLEFISH_MTOP_PROBE = false` for stable builds.
   - Prefer a lower-risk H5/WebView state collector or UI-driven collector before trying more ART inline hooks.
   - Current next network direction is WebView-side WindVane/JSBridge observation, not native MTOP interception. Do not store cookies, headers, signatures, full request bodies, or full response bodies.

4. Periodic retry
   - Add a lightweight scheduler that calls `IdlefishEventSyncer.uploadPendingIfEnabled(context)` on app launch and periodically.

5. Configuration UI
   - Add a simple settings surface for endpoint, enable flag, account alias, and optional secret.

## Safety

- Do not enqueue cookies, sessions, auth headers, tokens, or passwords.
- Keep raw payloads disabled or redacted by default.
- Prefer structured `metrics` fields over raw response storage.
