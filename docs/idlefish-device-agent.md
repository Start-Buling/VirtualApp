# Idlefish Device Agent

## Purpose

`IdlefishDeviceAgent.exe` runs on the Windows PC that is physically connected to the Android phone by USB/ADB.

It is the bridge between the web server and the phone:

```text
server/data-collector
  <- Windows Agent relay
  <- adb reverse tcp:18080
  <- Android phone / CarlosApp / VirtualApp
```

The phone does not need direct network access to the server. The Android collector posts to `http://127.0.0.1:18080`, and the Agent forwards those requests to the configured server.

## Packaged Output

Package command:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\package_idlefish_device_agent.ps1
```

Output:

```text
dist/idlefish-device-agent/IdlefishDeviceAgent.exe
dist/idlefish-device-agent/IdlefishDeviceAgent.Tray.exe
dist/idlefish-device-agent/idlefish-agent.json
```

Copy this folder to another Windows PC. The packaging script also bundles Android `platform-tools` when it can find the local Android SDK. If `idlefish-agent.json` already exists in the output folder, the packaging script keeps it and writes a fresh `idlefish-agent.example.json` beside it. Use `-OverwriteConfig` only when you intentionally want to reset the config.

ADB lookup order:

1. `adbPath` in `idlefish-agent.json`
2. `platform-tools\adb.exe` next to `IdlefishDeviceAgent.exe`
3. `adb.exe` next to `IdlefishDeviceAgent.exe`
4. `adb` from `PATH`
5. common Android SDK locations, such as `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`

## Requirements

- Windows PC connected to the phone by USB.
- USB debugging enabled and authorized on the phone.
- `adb devices` shows the phone in `device` state.
- CarlosApp debug build installed on the phone.
- The PC can access the data-collector API configured in `.env`.

## Config

`idlefish-agent.json`:

```json
{
  "mode": "poll",
  "agentId": "",
  "serverBaseUrl": "",
  "deviceNo": "device_001",
  "deviceName": "Android Phone",
  "serial": "",
  "adbPath": "",
  "agentToken": "",
  "autoDeviceNoBySerial": true,
  "deviceMapPath": "idlefish-devices.json",
  "deviceNoPrefix": "device_",
  "localPort": 18080,
  "waitSeconds": 42,
  "pollSeconds": 5,
  "heartbeatSeconds": 15,
  "maxTasks": 0,
  "forceStop": true,
  "virtualUserMode": "all",
  "virtualUserIds": []
}
```

Fields:

- `mode`: `poll`, `discover`, `collect`, or `both`.
- `agentId`: optional. Leave empty to use `<computer-name>-<deviceNo>`.
- `serverBaseUrl`: data-collector API base URL.
- `deviceNo`: business device number shown in the web console.
- `autoDeviceNoBySerial`: when enabled, the Agent maps the connected ADB serial to a stable local `deviceNo`.
- `deviceMapPath`: local serial-to-deviceNo map file. Relative paths are resolved next to `idlefish-agent.json`.
- `deviceNoPrefix`: prefix used when assigning a new device number, for example `device_002`.
- `deviceName`: human-readable device name shown in the web console.
- `serial`: ADB serial. Leave empty to use the first connected device.
- `adbPath`: optional. Leave empty to auto-detect bundled `platform-tools\adb.exe`, PATH, or common SDK locations.
- `localPort`: local relay port used by `adb reverse`.
- `virtualUserMode`: `all` auto-detects all VirtualApp users where Idlefish is installed. Use `manual` with `virtualUserIds` for a fixed subset.
- `virtualUserIds`: fallback or manual VirtualApp clone ids. Leave empty when `virtualUserMode` is `all`.
- `forceStop`: force-stop CarlosApp before each clone so the WebView collector runs fresh.
- `pollSeconds`: task polling interval in `poll` mode.
- `heartbeatSeconds`: heartbeat interval in `poll` mode.
- `maxTasks`: `0` means keep running forever; useful for a long-lived Agent.

## Automatic Device Number Mapping

When `autoDeviceNoBySerial` is enabled, the Agent keeps a local map from ADB serial to `deviceNo`:

```text
dist/idlefish-device-agent/idlefish-devices.json
```

Behavior:

- If the current ADB serial is already in the map, the Agent uses the mapped `deviceNo`.
- If this is the first phone ever seen by this Agent folder, the Agent uses the configured `deviceNo` as the initial value, such as `device_001`.
- If a new ADB serial appears later, the Agent assigns the next number, such as `device_002`.
- Leave `agentId` empty so it follows the resolved `deviceNo`, for example `editor-device_002`.

This lets one Windows PC rotate between phones without manually editing `deviceNo`. If the same phone is plugged in again later, it gets its previous `deviceNo` back. If you intentionally want to force a specific `deviceNo`, start the Agent with `--no-auto-device-no` or set `autoDeviceNoBySerial` to `false`.

In `poll` mode the Agent accepts task payloads like:

```json
{
  "include_original_app": true,
  "virtual_user_mode": "all",
  "wait_seconds": 42
}
```

`include_original_app=true` means the server wants the original Idlefish app included as `app_slot=0`. Original-app collection is still a separate uiautomator/OCR path; until that path is implemented the Agent reports it as skipped. `virtual_user_mode=all` means the Agent should run every detected VirtualApp clone on that phone. `virtual_user_ids` can still be sent for a manual subset.

## Run

Recommended tray mode for web button tasks:

```powershell
.\IdlefishDeviceAgent.Tray.exe
```

The tray app starts `IdlefishDeviceAgent.exe --mode poll` in the background, shows status in the Windows notification area, and provides right-click actions for start, stop, restart, logs, and exit. Double-click the tray icon to open `agent-tray.log`.

Console mode for web button tasks:

```powershell
.\IdlefishDeviceAgent.exe --mode poll
```

Discover shops:

```powershell
.\IdlefishDeviceAgent.exe --mode discover
```

Collect service scores after binding:

```powershell
.\IdlefishDeviceAgent.exe --mode collect
```

Override without editing JSON:

```powershell
.\IdlefishDeviceAgent.exe --mode discover --device-no device_001 --users 0,1,2
```

## Current Limit

Original-app collection currently uses the uiautomator/OCR route and may fail when the original app WebView does not expose readable text. VirtualApp clones use `virtualapp_webview_js`.
