# Idlefish Ingest Server API

## Purpose

Receive structured shop metric events from VirtualApp collection clients. The server stores business metrics only. It must not receive cookies, sessions, tokens, or account passwords.

## Development Server

The first MVP server is a dependency-free Node.js process:

```powershell
node .\server\idlefish-ingest-server.js
```

Convenience script:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\start_idlefish_ingest_server.ps1
```

Keep that terminal open while testing Android uploads. If the terminal is closed, the phone-side test button will fail because `127.0.0.1:18080` no longer has a server behind it.

Check both the PC server and phone port forwarding:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\check_idlefish_ingest.ps1
```

After the phone is already on the Idlefish service-score page, capture and upload the current foreground page:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\capture_upload_idlefish_page.ps1
```

To open the service-score page inside a VirtualApp Idlefish clone first, then capture and upload:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\capture_upload_idlefish_page.ps1 -OpenInVirtualApp -VirtualUserId 0
```

The underlying opener uses Idlefish's internal WebView deep link:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\open_idlefish_service_points_in_va.ps1 -UserId 0 -UseFleamarketWebView
```

The wrapper refuses to upload partial events by default. If the phone is on the wrong page, it still keeps the local probe files under `page-probe/` for debugging.

Primary local collection route:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\collect_idlefish_webview_js.ps1 -Serial 6H5PY5GEZ9QGJJO7 -VirtualUserId 0
```

This route:

- checks `GET /health`;
- runs `adb reverse tcp:18080 tcp:18080`;
- configures the Android-side ingest endpoint through `IdlefishDeepLinkReceiver`;
- opens the service-score H5 inside the selected VirtualApp Idlefish clone;
- lets `IdlefishWebViewJsProbe` read visible WebView text and upload `source=webview_js`;
- prints `GET /api/v1/events/recent?limit=5` for verification.

Shop identity discovery route:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\discover_idlefish_shop_identity.ps1 -Serial 6H5PY5GEZ9QGJJO7 -VirtualUserId 0 -DeviceNo device_001
```

For the remote data-collector service, pass the remote endpoints explicitly:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\discover_idlefish_shop_identity.ps1 `
  -Serial 6H5PY5GEZ9QGJJO7 `
  -VirtualUserId 0 `
  -DeviceNo device_001 `
  -IngestUrl https://<collector-host>/api/v1/ingest/events `
  -DiscoveryUrl https://<collector-host>/api/v1/idlefish/discovery/events
```

This opens the Fish Pro workbench page and uploads an `idlefish_shop_identity` event. The verified fields are:

- `device_no`
- `device_id`
- `collector_type=virtualapp_webview_js`
- `app_slot`
- `virtual_user_id`
- `identity.shop_name`
- `identity.category`
- `identity.service_score`

Batch discovery for multiple VirtualApp clones:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\discover_idlefish_virtual_shops.ps1 `
  -Serial 6H5PY5GEZ9QGJJO7 `
  -DeviceNo device_001 `
  -VirtualUserIds 0,1 `
  -IngestUrl http://10.6.0.10:8000/api/v1/ingest/events `
  -DiscoveryUrl http://10.6.0.10:8000/api/v1/idlefish/discovery/events `
  -SkipHealthCheck
```

After the discovered shops are bound in the web console, collect service scores for the same clones:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\collect_idlefish_virtual_service_scores.ps1 `
  -Serial 6H5PY5GEZ9QGJJO7 `
  -DeviceNo device_001 `
  -VirtualUserIds 0,1 `
  -IngestUrl http://10.6.0.10:8000/api/v1/ingest/events `
  -DiscoveryUrl http://10.6.0.10:8000/api/v1/idlefish/discovery/events `
  -SkipHealthCheck
```

The scripts force-stop the VirtualApp host before each clone by default so that the WebView Activity resumes and the JS sampler runs again.

If Android UIAutomator cannot read the inner UC/WebView text, the wrapper falls back to screenshot OCR through:

```powershell
tools\ocr_idlefish_screenshot_event.ps1
```

This fallback currently extracts the first-screen service-score fields: category, update date, total service score, item quality, response speed, logistics, and after-sales score.

WebView JS collector status:

- WebView DevTools sockets are not exposed on the current Idlefish UC WebView.
- `IdlefishWebViewJsProbe` reads `document.body.innerText` in-process through `evaluateJavascript`.
- The collector intentionally avoids cookies, localStorage, tokens, and request headers.
- Current event type: `idlefish_shop_service_score`.
- Current identity discovery event type: `idlefish_shop_identity`.
- Current source: `webview_js`.
- UIAutomator/OCR remains the fallback path.

Defaults:

- `PORT`: `18080`
- `DATA_DIR`: `server/data`
- `INGEST_DEVICE_SECRET`: empty, HMAC disabled for local development

## Endpoints

### `GET /health`

Returns server status.

```json
{
  "ok": true,
  "service": "idlefish-ingest",
  "time": "2026-05-09T14:10:00.000Z"
}
```

### `POST /api/v1/ingest/events`

Accepts either a batch payload:

```json
{
  "device_id": "6H5PY5GEZ9QGJJO7",
  "events": [
    {
      "event_type": "idlefish_shop_service_score",
      "schema_version": 1,
      "event_id": "uuid",
      "captured_at": "2026-05-09T14:00:00+08:00",
      "source": "uiautomator",
      "account": {},
      "page": {},
      "metrics": {},
      "raw": {}
    }
  ]
}
```

or a single event object. Single events are wrapped into a batch internally.

Response:

```json
{
  "ok": true,
  "accepted": 1,
  "event_ids": ["uuid"]
}
```

### `GET /api/v1/events/latest`

Returns latest accepted event per logical shop key and event type.

### `GET /api/v1/events/recent?limit=10`

Returns the newest accepted events from the append-only event log. Use this endpoint to verify repeated phone uploads, because `latest` overwrites the same `shopKey:eventType` row.

## Storage

MVP storage is file-based:

- `server/data/events.jsonl`: append-only event log
- `server/data/latest.json`: latest event map

Later production storage can replace this with database tables:

```sql
devices(id, name, secret_hash, created_at, last_seen_at)
shops(id, device_id, virtual_user_id, shop_id, account_alias, created_at)
metric_events(id, event_type, schema_version, device_id, shop_key, captured_at, source, page_url, metrics_json, raw_json, received_at)
shop_metric_latest(shop_key, event_type, captured_at, metrics_json, event_id, updated_at)
```

## Optional HMAC

When `INGEST_DEVICE_SECRET` is set, clients must send:

- `x-device-id`
- `x-timestamp`
- `x-signature`

Signature:

```text
hex_hmac_sha256(secret, "<timestamp>.<raw request body>")
```

The development upload script supports this.
