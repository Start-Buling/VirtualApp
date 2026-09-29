# Idlefish Shop Data Pipeline Plan

## Goal

Collect the user's own Idlefish shop metrics from each VirtualApp clone and upload only business data to a server. The server must not receive or store login sessions, cookies, or account tokens.

## Architecture

```text
Idlefish clone in VirtualApp
  -> on-device collection layer
  -> local structured event file/queue
  -> HTTPS upload worker
  -> server ingest API
  -> database
  -> web dashboard and alerts
```

## Collection Strategy

1. WebView JS text collection, current primary route
   - Open the service-score H5 inside the logged-in VirtualApp Idlefish clone.
   - Run in-process `evaluateJavascript` on the UC/WindVane WebView.
   - Read only `location.href`, `document.title`, and visible `document.body.innerText`.
   - Parse the visible text into the standard `idlefish_shop_service_score` event schema.
   - Do not read or upload cookies, localStorage, request headers, sessions, or tokens.

2. UI accessibility/OCR collection, fallback
   - Use `adb uiautomator` to read visible page text from the current Idlefish service score page.
   - Parse visible text into structured metrics.
   - Keep screenshot and raw UI XML for debugging.
   - If UIAutomator cannot expose UC/WebView inner text, fall back to Windows screenshot OCR.

3. WebView/page inspection, discovery
   - Capture current Activity and H5 URL from `dumpsys activity`.
   - Attempt WebView DevTools only when a devtools socket exists.
   - Use this to identify page routes and likely network entry points.

4. In-app Hook collection, deferred target state
   - Hook mtop/network response or JS bridge callbacks inside the logged-in Idlefish process.
   - Parse original JSON responses into the same event schema as the MVP.
   - Keep WebView JS and UI/OCR collection as fallback routes.

## Event Schema

```json
{
  "event_type": "idlefish_shop_service_score",
  "schema_version": 1,
  "event_id": "uuid-or-stable-hash",
  "captured_at": "2026-05-09T12:00:00+08:00",
  "source": "uiautomator",
  "account": {
    "device_serial": "6H5PY5GEZ9QGJJO7",
    "package_name": "com.taobao.idlefish",
    "virtual_user_id": null,
    "shop_id": null,
    "account_alias": null
  },
  "page": {
    "activity": "com.taobao.idlefish/com.taobao.idlefish.webview.WebHybridActivity",
    "url": "https://h5.m.goofish.com/..."
  },
  "metrics": {
    "category": "电玩",
    "updated_date": "2026-05-08",
    "service_score": 4.35,
    "service_score_peer_status": "落后80%同行",
    "item_quality_score": 3.2,
    "response_speed_score": 5.0,
    "logistics_score": 4.44,
    "after_sales_score": 5.0
  },
  "raw": {
    "ui_texts": [],
    "api": null,
    "payload_hash": null
  }
}
```

## Security Rules

- Do not upload cookies, sessions, auth headers, tokens, or private account credentials.
- Upload only structured business metrics and optional redacted debug metadata.
- Use HTTPS for server upload.
- Later server ingest should use `device_id`, timestamp, and HMAC signing.
- Server should deduplicate by `device_id + event_id`.

## MVP Scope

- Enhance `tools/idlefish_page_probe.ps1` to emit both probe data and parsed event JSON.
- Store output under `page-probe/`.
- Verify on the currently connected device and current service score page.
- Defer server API implementation until local event shape is stable.

## Local Script

`tools/idlefish_page_probe.ps1` now supports two modes:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\idlefish_page_probe.ps1 -Serial 6H5PY5GEZ9QGJJO7
```

Collects from a connected device and writes:

- `page-probe/<timestamp>-result.json`
- `page-probe/<timestamp>-event.json`
- `page-probe/<timestamp>-ui.xml`
- `page-probe/<timestamp>.png`

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\idlefish_page_probe.ps1 -InputResultJson .\page-probe\<timestamp>-result.json
```

Replays a previous probe result. If a paired `*-ui.xml` exists, the script uses it to preserve duplicate UI text values such as repeated `5.0`, `1%`, and `0%`.

## MVP Ingest Server

API details are documented in `docs/idlefish-server-api.md`.
Android-side queue and upload design is documented in `docs/idlefish-android-sync-design.md`.

Run the local server:

```powershell
node .\server\idlefish-ingest-server.js
```

Upload one generated event:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\upload_idlefish_event.ps1 -EventPath .\page-probe\<timestamp>-event.json
```

Local MVP storage:

- `server/data/events.jsonl`
- `server/data/latest.json`

These files are local generated data and are ignored by git.

## Progress

- [x] Confirmed device can expose current Idlefish H5 route via `dumpsys activity`.
- [x] Confirmed current page text is available through `uiautomator`.
- [x] Confirmed WebView DevTools is not currently exposed.
- [x] Add structured metric parsing to the probe script.
- [x] Validate generated event JSON from a previously captured real page XML.
- [x] Design server ingest API and database tables.
- [x] Implement local MVP ingest server and upload script.
- [x] Verify event upload into local file-backed storage.
- [x] Add Android/VirtualApp-side local event queue and HTTP upload skeleton.
- [x] Add a temporary VirtualApp menu entry that uploads a simulated Idlefish event from the phone-side code path.
- [x] Build and install a debug APK containing the temporary upload entry.
- [x] Verify phone-to-PC upload with `adb reverse tcp:18080 tcp:18080`.
- [x] Add recent-events verification API so repeated debug uploads are visible even when `latest` values look unchanged.
- [x] Add one-command UI capture/upload wrapper for the current foreground phone page.
- [x] Add screenshot OCR fallback for UC/WebView pages when `uiautomator` cannot expose inner text.
- [x] Add VirtualApp deep-link opener so the service-score H5 can be opened inside the cloned Idlefish session.
- [x] Verify one-command open -> capture -> upload for VirtualApp user `0`.
- [x] Verify low-risk plan B probe: in-process WebView JS read without exporting login state.
- [x] Wire Android/VirtualApp-side queue to the WebView JS collector.
- [x] Add one-command WebView JS collection wrapper for VirtualApp user `0`.
- [x] Add WebView JS shop identity discovery from the Fish Pro workbench page.
- [x] Include `device_no`, `collector_type`, `app_slot`, and `virtual_user_id` in VirtualApp events.
- [x] Investigate mtop/network Hook source for raw JSON.
- [x] Choose WebView JS as the next stable collector route after the MTOP inline-hook experiment.
- [x] Run the UI capture/upload wrapper while the phone is on the live service-score page.

## Current Findings

- Current Activity: `com.taobao.idlefish/com.taobao.idlefish.webview.WebHybridActivity`
- Current H5 page: `https://h5.m.goofish.com/wow/moyu/moyu-project/fish-shop-data/pages/service-points?spm=a2170.28358589.0.0&isOldFriendly=false&_from__=webhybrid`
- Browser access without App session shows `Session过期`.
- MVP should therefore avoid moving session state to the server.
- Important parser finding: do not deduplicate UI text before parsing. Repeated values are meaningful, for example `5.0` can appear in both response speed and after-sales score.
- Latest valid replay event: `page-probe/20260509-135247-event.json`
- Current live device state during validation was lock screen / `NotificationShade`, so fresh live collection should be rerun after the phone is unlocked and the service score page is visible again.
- 2026-05-09 14:03 live rerun: page screenshot and H5 URL were captured correctly, but `uiautomator dump` returned `Killed`. The script now records `uiDumpStatus` and `uiDumpExitCode` in `*-result.json` for this failure mode.
- 2026-05-09 14:09 ingest verification: `page-probe/20260509-140947-event.json` was uploaded to the local ingest server. The server accepted the event and wrote latest metrics with correct UTF-8 Chinese text.
- Encoding note: Windows PowerShell HTTP upload must send UTF-8 bytes, not a raw string body, otherwise Chinese text can become `??`.
- Android sync skeleton added under `app/src/main/java/com/carlos/home/idlefish/`.
- Temporary Android debug entry added in `HomeActivity`: `Idlefish sync test`.
  - It enqueues a simulated `idlefish_shop_service_score` event.
  - It uploads pending events to `http://127.0.0.1:18080/api/v1/ingest/events` by default.
  - It requires `adb reverse tcp:18080 tcp:18080` when the ingest server is running on the PC.
- Android compile verification: direct `javac` check against local Android SDK `android-35/android.jar` passed for the new sync classes.
- Android full build verification: `:app:assembleDebug --offline` passed using the workspace `.tmp_gradle_home` cache.
- Installed APK to connected device `6H5PY5GEZ9QGJJO7` with package `com.carlos.multiapp` and launched `com.carlos.splash.splashActivity`.
- Local server health check passed on `http://127.0.0.1:18080/health`.
- `adb reverse` is active for `tcp:18080 tcp:18080`.
- 2026-05-09 15:16 Android debug upload verification passed.
  - The phone-side `Idlefish sync test` menu item uploaded a simulated event through `HttpURLConnection`.
  - The server latest API now contains `debug-local:idlefish_shop_service_score`.
- 2026-05-09 16:10 stable APK verification passed on device `6H5PY5GEZ9QGJJO7`.
  - The APK was rebuilt and installed with the MTOP probe disabled by default.
  - VirtualApp opened normally, and the first Idlefish clone entered the Idlefish home screen.
  - Logs showed only `IdlefishApp callback installed`; no `IdlefishMtopProbe` installation and no crash were observed.
- MTOP inline-hook experiment status:
  - The prototype could install hooks for `mtopsdk.mtop.domain.MtopResponse` and `MtopRequest`.
  - On this Android 14 device, SandHook/ART inline hooks crashed the Idlefish process when MTOP callback traffic arrived.
  - The route is therefore blocked for the stable build and should stay behind `ENABLE_IDLEFISH_MTOP_PROBE = false`.
  - Next recommended collector routes are lower-risk H5/WebView state capture or UI-driven collector fallback, both feeding the existing Android queue and ingest API.
- Verification note:
  - `GET /api/v1/events/latest` is a de-duplicated latest-value view keyed by `shopKey:eventType`.
  - If repeated debug uploads have the same metrics, the content can look unchanged; check `received_at`, `event.event_id`, or use `GET /api/v1/events/recent?limit=10`.
- 2026-05-11 UI capture wrapper added:
  - `tools/capture_upload_idlefish_page.ps1` checks the ingest server, ensures `adb reverse`, runs the UI probe, uploads only when `service_score` is parsed, and prints recent accepted events.
  - First validation reached the phone successfully, but the foreground page was Android launcher / app folder, not the Idlefish service-score page, so the script correctly refused to upload a partial event.
  - Latest local capture from that validation: `page-probe/20260511-114410-event.json`.
- 2026-05-11 live service-score capture/upload passed.
  - Current page: `com.taobao.idlefish/com.taobao.idlefish.webview.WebHybridActivity`.
  - `uiautomator` can be inconsistent on this UC/WebView page: one run exposed 161 texts, another returned `Killed`.
  - Added `tools/ocr_idlefish_screenshot_event.ps1` as fallback. It uses Windows OCR over the screenshot and numeric crops.
  - Uploaded OCR event id: `2ab564a1-73e5-4e78-bb2a-a352490c6c0e`.
  - Parsed metrics: category `模玩动漫桌游`, update date `2026-05-10`, service score `4.83`, item quality `4.62`, response speed `4.99`, logistics `4.64`, after-sales `5.0`.
- 2026-05-11 VirtualApp in-session H5 open passed.
  - Added `IdlefishDeepLinkReceiver` in the host app.
  - `tools/open_idlefish_service_points_in_va.ps1 -UseFleamarketWebView` sends `fleamarket://webview?url=<encoded H5>` with action `android.intent.action.idlefish` to virtual user `0`.
  - Direct plain `https` ACTION_VIEW was not stable: it reached WebHybridActivity once, then fell back/black-screened.
  - The fleamarket WebView scheme opened the service-score page correctly inside the cloned Idlefish session.
  - `tools/capture_upload_idlefish_page.ps1 -OpenInVirtualApp -VirtualUserId 0` now opens the page, waits for H5 load, captures, parses, and uploads.
  - Verified upload event id: `21a41be1-f725-40cc-9d23-3b0457ee165f`.
- 2026-05-11 plan B low-risk probe passed.
  - DevTools check: no `webview_devtools_remote` socket was exposed, so direct Chrome DevTools/CDP is not available.
  - Added `IdlefishWebViewJsProbe`, enabled as an experiment in `IdlefishApp`.
  - The probe runs inside the virtualized Idlefish process, finds `android.taobao.windvane.extra.uc.WVUCWebView`, and calls `evaluateJavascript`.
  - It reads only `location.href`, `document.title`, and `document.body.innerText`; it does not read cookies, localStorage, tokens, or headers.
  - Result: after H5 load, JS returned the full service-score visible text (`textLength=725`, `hasServiceScore=true`).
  - Uploaded probe event id: `c2505f28-519e-407c-a717-40e3cd66b278`.
  - Conclusion: plan B is feasible through WebView JS state/text access. Next step is to parse the JS text into the same `idlefish_shop_service_score` schema and use UI/OCR as fallback.
- 2026-05-11 WebView JS collector promoted to primary local route.
  - `IdlefishWebViewJsProbe` now parses `document.body.innerText` into `idlefish_shop_service_score` with `source=webview_js`.
  - `IdlefishDeepLinkReceiver` can receive upload config extras and enable the Android queue before opening the page.
  - Added `tools/collect_idlefish_webview_js.ps1` as the dedicated one-command collection wrapper.
  - Verification on device `6H5PY5GEZ9QGJJO7`, VirtualApp user `0`, uploaded event id: `93706427-9ec8-40f6-9e3b-81b23f4d41e3`.
  - Parsed fields included category, update date, service score, peer status, four sub-scores, refund/response/delivery/service coverage metrics, complaint count, and extra score.
- 2026-05-11 shop identity discovery route added.
  - Workbench URL: `https://h5.m.goofish.com/wow/moyu/moyu-project/fish-pro-workbench/pages/Workbench?...`.
  - Added `tools/discover_idlefish_shop_identity.ps1`.
  - `IdlefishWebViewJsProbe` now emits `idlefish_shop_identity` when the workbench text is loaded.
  - The event includes `device_no`, `device_id`, `collector_type=virtualapp_webview_js`, `app_slot=virtual_user_id+1`, `virtual_user_id`, `shop_name`, `category`, and workbench service score.
  - Empty early WebView reads are skipped; the JS sampler now checks longer load windows.
  - Local verification event id: `ccc14004-10b9-415d-8ab6-ece8fd478cbe`.
  - Parsed identity: `device_001`, virtual user `0`, shop `一番市集次元漫物馆`, category `动漫周边`, score `4.83`.

## Parsed Fields Verified From Replay

- `category`: `电玩`
- `updated_date`: `2026-05-08`
- `service_score`: `4.35`
- `service_score_peer_status`: `落后80%同行`
- `item_quality_score`: `3.2`
- `response_speed_score`: `5.0`
- `logistics_score`: `4.44`
- `after_sales_score`: `5.0`
- `quality_refund_rate_percent`: `0`
- `punished_item_ratio_percent`: `1`
- `risk_item_ratio_percent`: `1`
- `description_service_coverage_percent`: `0`
- `five_minute_response_rate_percent`: `98`
- `average_response_time`: `2分钟`
