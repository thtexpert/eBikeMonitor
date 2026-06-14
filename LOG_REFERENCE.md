# eBikeMonitor — Log File Reference

> Applies to **v1.15.1** and later.

Log file location on device:
`/storage/emulated/0/Android/data/com.example.ebikemonitor.debug/files/ebike_monitor_debug.txt`

Each line follows the format:
```
[YYYY-MM-DD HH:MM:SS.mmm] <Component>: <Message>
```

---

## Log File Management

| Property | Value |
|---|---|
| Max file size | **1 MB** |
| Rotation | When the active file exceeds 1 MB, it is renamed to `ebike_monitor_debug.txt.bak` and a fresh `ebike_monitor_debug.txt` starts |
| Backup files kept | **1** — the previous `.bak` is deleted before the new one is written |
| Total max disk usage | ~2 MB (current file + one backup) |
| Write thread | Dedicated single background thread — log writes never block the UI or service |

> **Practical note:** At typical usage (one ride per day with background monitoring), the 1 MB limit is reached roughly every 1–2 weeks. If you are actively debugging and need older entries, check `ebike_monitor_debug.txt.bak` in the same folder.

---

## Entry Types

### `[STATUS]` — Combined State Summary
Emitted automatically whenever **any** key state changes. The fastest way to understand what the app was doing at any moment.

```
[STATUS] APP:Foreground | Flow:Present | BLE:Connected | MQTT:Connected | Monitor:ON
```

| Field | Possible Values | Meaning |
|---|---|---|
| `APP` | `Foreground` | Activity is started and visible to the user |
| | `Background` | Activity stopped — user left the app or screen is off |
| `Flow` | `Present` | Bosch Flow pocket-mode notification is active — eBike is on and nearby |
| | `Absent` | Notification gone — eBike off, out of range, or Flow not running |
| `BLE` | `Connected` | Active BLE GATT connection to the eBike |
| | `Connecting` | Connection attempt in progress |
| | `Disconnected` | No BLE connection, none in progress |
| `MQTT` | `Connected` | Active MQTT session with the broker |
| | `Connecting` | Connection attempt in progress |
| | `Disconnected(auto)` | Not connected, auto-reconnect is enabled — loop will retry |
| | `Off` | Not connected and auto-reconnect is disabled by user setting |
| `Monitor` | `ON` | Background reconnection loop is actively running |
| | `OFF` | Loop stopped — service idle or shutting down |

---

### `[HEARTBEAT]` — Periodic Health Check
Emitted every **60 seconds** while the service is alive. Includes memory usage and all states from `[STATUS]`.

```
[HEARTBEAT] Mem:8MB/28MB | APP:Background | Flow:Present | BLE:Disconnected | MQTT:Connected | Monitor:ON
```

| Field | Meaning |
|---|---|
| `Mem:X/Y MB` | Used / allocated JVM heap. Growing used value over time may indicate a memory leak. |
| All other fields | Same definitions as `[STATUS]` above |

---

### `[APP STATE]` — Activity Lifecycle
Emitted by `MainActivity` on each Android lifecycle transition.

| Entry | Android callback | Meaning |
|---|---|---|
| `FOREGROUND (onStart)` | `onStart()` | App became visible; service gate opens (`isUiActive = true`) |
| `RESUMED — app on screen and interactive` | `onResume()` | App fully interactive |
| `PAUSED — app partially visible or switching` | `onPause()` | Another app/dialog overlaid, or user started switching away |
| `BACKGROUND (onStop) — app no longer visible` | `onStop()` | App fully hidden; service gate closes (`isUiActive = false`) |
| `DESTROYED (onDestroy)` | `onDestroy()` | Activity instance destroyed (swipe from recents, rotation, etc.) |

> **Note:** `onStart`/`onStop` control the `isUiActive` flag that the background service uses to decide whether to keep running. `onResume`/`onPause` are informational only.

---

### `BleManager` — BLE Events

| Entry | Meaning |
|---|---|
| `Connected to GATT` | BLE connection established; service discovery starting |
| `Disconnected from GATT` | Clean BLE disconnect (e.g. called by app) |
| `GATT Error: N (description)` | BLE stack error — see table below |

**GATT Error Codes:**

| Code | Name | Typical Cause |
|---|---|---|
| `8` | `GATT_CONN_TIMEOUT` | eBike went out of range before connection completed |
| `19` | `GATT_CONN_TERMINATE_PEER_USER` | eBike closed the connection (normal power-off) |
| `22` | `GATT_CONN_TERMINATE_LOCAL_HOST` | Android stack closed the connection locally |
| `133` | `GATT_ERROR` | Generic Android BLE stack error, often a stale GATT handle — usually resolves on next retry |
| `147` | `GATT_ERROR` | Too many concurrent BLE connections open, or Android internal BLE error |

---

### `MqttManager` — MQTT Events

| Entry | Meaning |
|---|---|
| `Connected to MQTT (uri)` | Initial connection succeeded (`onSuccess` callback) |
| `Connection Complete (reconnect=false, uri=...)` | Connection confirmed by broker (`connectComplete` callback), first connect |
| `Connection Complete (reconnect=true, uri=...)` | Reconnection confirmed — should only appear after a genuine drop, not rapidly repeating |
| `Connection Lost: <reason>` | Broker closed or network dropped the connection |
| `Failed to connect: <reason>` | Connection attempt rejected or timed out |

> **Health indicator:** `Connection Lost` immediately followed by `Connection Complete` in a rapid loop (< 5 s apart) indicates the reconnect storm bug. After fix #28 this should never occur.

---

### `EBikeBackgroundService` — Service Lifecycle & Logic

| Entry | Meaning |
|---|---|
| `onCreate` | Service started (by UI open or by notification listener detecting eBike) |
| `onDestroy` | Service stopping — BLE and MQTT will be disconnected |
| `onTaskRemoved: Background monitoring active. Keeping service running.` | User swiped app from recents but bike is present; service intentionally stays alive |
| `onTaskRemoved: No active background monitoring. Stopping service.` | User swiped app from recents and bike is absent; service stops |
| `Starting reconnection loop` | Active monitoring started — BLE and MQTT will be reconnected as needed |
| `Stopping reconnection loop` | Monitoring stopped (bike gone or UI closed with no bike) |
| `MQTT Connected. Flagging for full sync.` | MQTT connected while BLE was also connected; all topics will be published |
| `MQTT Connected but BLE not ready — skipping sync until BLE connects.` | MQTT connected before BLE — sync deferred; BLE connect in next loop cycle will trigger full sync |
| `Performing full sync for <deviceId>` | All MQTT topics being published (first connect or after reconnect) |
| `Pre-loading N persistent baselines for <MAC>` | Stored per-mode cumulative values loaded from DataStore for trip tracking |

**Home Sync Window** (`[HOME SYNC]` prefix):

| Entry | Meaning |
|---|---|
| `[HOME SYNC] Window started (Xs) — MQTT-only reconnect for post-ride sync` | eBike turned off; BLE disconnected; MQTT-only loop started to sync ride data on home WiFi arrival |
| `[HOME SYNC] Connecting MQTT to <uri>` | Home Sync loop attempting MQTT connection |
| `[HOME SYNC] MQTT connected — pushing end-of-ride snapshot (full sync)` | MQTT reached; publishing the complete end-of-ride state (all topics) captured at bike-off moment |
| `[HOME SYNC] Window cancelled` | Bike turned back on (main loop takes over) or service stopped |
| `[HOME SYNC] Window expired without MQTT sync. Stopping service.` | Duration elapsed without reaching MQTT broker; data not pushed this cycle |
| `[HOME SYNC] shouldServiceRun=false but home sync active — keeping service alive` | UI and monitoring both inactive, but Home Sync Window is still running; service stays alive until window finishes |

**Reconnection Loop:**

| Entry | Meaning |
|---|---|
| `Reconnection Loop: Bike absent — exiting stale iteration` | Loop detected bike is off at start of iteration — exits cleanly (normal after bike-off race condition) |

---

### `BikePresenceManager` — Flow Notification State

| Entry | Meaning |
|---|---|
| `Bike presence updated to true by NotificationListener` | Bosch Flow pocket-mode notification appeared — eBike detected |
| `Bike presence updated to false by NotificationListener` | Notification removed — eBike turned off or Flow app stopped |

---

### `EBikeNotificationListener` — Notification Listener Service

| Entry | Meaning |
|---|---|
| `Connected and listening...` | Notification listener bound and active |
| `Disconnected.` | Listener unbound (usually temporary; Android rebinds automatically) |
| `Bosch Pocket Mode detected!` | Trigger notification seen — initiates background service start if `backgroundStartup` is enabled |
| `Duplicate notification ignored (Flow already present).` | Flow app posted the same pocket-mode notification twice; second instance safely suppressed |
| `Bosch Pocket Mode notification removed.` | Trigger notification gone — presence set to false |
| `Background Startup is disabled by user.` | Setting is off; service will not be started automatically |

---

### `MainViewModel` — UI-Initiated Actions

These entries distinguish user-triggered events from automatic background actions, making it easy to explain unexpected MQTT connections in the log.

| Entry | Meaning |
|---|---|
| `[USER ACTION] Manual MQTT connect via UI` | User pressed the MQTT connect button in the app |
| `[USER ACTION] Manual MQTT disconnect via UI` | User pressed the MQTT disconnect button |
| `[USER ACTION] Manual BLE connect via UI to <MAC>` | User pressed the BLE connect button |
| `[USER ACTION] Manual BLE disconnect via UI` | User pressed the BLE disconnect button |
| `Auto-connect on UI start (MQTT not yet connected)` | App opened, auto-connect setting is ON, MQTT was not yet connected — triggered automatically, not by user |
| `onCleared — disconnecting BLE and MQTT as safety measure` | ViewModel destroyed (activity finished) — safety disconnect called |

> **Tip:** If you see `MqttManager: Connected to MQTT` without a preceding `Reconnection Loop: Connecting MQTT`, look for a `[USER ACTION]` or `Auto-connect on UI start` entry just before it to identify the source.

---

## Quick Search Patterns

| What you want to find | Search for |
|---|---|
| All state snapshots | `[STATUS]` |
| All health checks | `[HEARTBEAT]` |
| App going to background | `BACKGROUND (onStop)` |
| App coming to foreground | `FOREGROUND (onStart)` |
| eBike turned on/off | `Bike presence updated` |
| MQTT connect events | `Connected to MQTT` |
| MQTT disconnect events | `Connection Lost` |
| BLE errors | `GATT Error` |
| Reconnect loop start/stop | `reconnection loop` |
| Reconnect storm indicator | rapid `Connection Lost` + `Connection Complete` pairs |
| Home Sync Window events | `[HOME SYNC]` |
| User UI actions (connect/disconnect) | `[USER ACTION]` |
