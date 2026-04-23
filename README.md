# RTK Infra AR
## Production-Ready RTK + ARCore Underground Infrastructure Visualizer
### TUSAGA-Aktif (Turkey CORS Network) Compatible

---

## Architecture Overview

```
com.tusaga.rtkinfra/
├── gnss/
│   ├── GnssManager.kt          ← GNSS raw measurements API, L1/L5 detection
│   ├── GnssRtkService.kt       ← Foreground service for continuous positioning
│   ├── GnssModels.kt           ← RtkState, FixType, SatelliteMeasurement
│   └── NmeaGenerator.kt        ← NMEA GGA sentence builder for VRS
│
├── ntrip/
│   ├── NtripClient.kt          ← NTRIP Rev1 socket client (TUSAGA compatible)
│   ├── NtripClientManager.kt   ← Lifecycle management + auto-reconnect
│   ├── NtripConfig.kt          ← TUSAGA-Aktif connection parameters
│   └── RtcmParser.kt           ← RTCM 3.x binary frame parser + CRC-24Q
│
├── coordinate/
│   └── CoordinateTransformer.kt ← WGS84↔ECEF↔ENU, ITRF96, geoid undulation
│
├── ar/
│   ├── ArRenderer.kt           ← OpenGL ES 2.0 renderer (camera + 3D objects)
│   └── InfrastructureAnchorManager.kt ← GNSS→ENU→ARCore pose computation
│
├── data/
│   ├── model/InfraFeature.kt   ← Infrastructure domain models
│   └── repository/
│       ├── GeoJsonParser.kt    ← GeoJSON FeatureCollection parser
│       └── SettingsRepository.kt ← TUSAGA credentials persistence
│
└── ui/
    ├── MainActivity.kt         ← Entry point, permissions, HUD
    ├── MainViewModel.kt        ← MVVM ViewModel coordinating all subsystems
    ├── ar/ArFragment.kt        ← ARCore GLSurfaceView fragment
    ├── map/MapFragment.kt      ← Google Maps with infrastructure overlay
    └── settings/SettingsFragment.kt ← TUSAGA-Aktif config UI
```

---

## TUSAGA-Aktif Configuration

### Step 1: Get TUSAGA-Aktif Credentials
1. Visit [tusaga-aktif.gov.tr](https://www.tusaga-aktif.gov.tr)
2. Apply for user account from KGM (Karayolları Genel Müdürlüğü)
3. Receive username and password via email (processing takes 1-3 business days)

### Step 2: Configure the App
Launch app → bottom navigation → **Settings** → fill in:

| Field | Value |
|-------|-------|
| Caster Host | `cors.tusaga-aktif.gov.tr` |
| Port | `2101` |
| Mountpoint | `TUSK00TUR0` (VRS recommended) |
| Username | Your TUSAGA username |
| Password | Your TUSAGA password |

### Recommended Mountpoints by City

| City | Mountpoint | Note |
|------|-----------|------|
| **VRS (Turkey-wide)** | `TUSK00TUR0` | **Recommended** – auto-selects nearest station |
| Istanbul | `IST000TUR0` | Physical station |
| Ankara | `ANK000TUR0` | Physical station |
| İzmir | `IZM000TUR0` | Physical station |
| Bursa | `BRS000TUR0` | Physical station |
| Antalya | `ANT000TUR0` | Physical station |
| Trabzon | `TRB000TUR0` | Physical station |
| Konya | `KON000TUR0` | Physical station |

**VRS Note**: The `TUSK00TUR0` VRS mountpoint synthesizes a virtual reference station at your current position. The app sends your GPS position via NMEA GGA every 5 seconds, and TUSAGA generates tailored RTCM corrections. This provides the best accuracy anywhere in Turkey.

---

## Minimum Device Requirements

| Requirement | Minimum | Recommended |
|-------------|---------|-------------|
| Android version | 8.0 (API 26) | 10.0+ (API 29+) |
| ARCore support | Required | Required |
| GNSS | GPS+GLONASS | GPS+GLONASS+Galileo+BeiDou |
| **Dual-frequency GNSS** | Optional | **Required for cm accuracy** |
| RAM | 3 GB | 6 GB+ |
| Camera | Any rear camera | Wide-angle, OIS |

### Confirmed Dual-Frequency (L1+L5) Android Devices:
- Samsung Galaxy S21/S22/S23/S24 series
- Google Pixel 4/5/6/7/8 series
- Xiaomi Mi 10/11/12/13 series
- OnePlus 8/9/10/11 series
- Huawei P40/Mate 40+ series

---

## Build & Run Instructions

### Prerequisites
```bash
# Install Android Studio Hedgehog (2023.1.1) or newer
# Android SDK 34
# Kotlin 1.9.x
```

### 1. Clone and Open
```bash
git clone <repo>
cd RTKInfra
# Open in Android Studio
```

### 2. Add Google Maps API Key
In `app/src/main/AndroidManifest.xml`, add inside `<application>`:
```xml
<meta-data
    android:name="com.google.android.geo.API_KEY"
    android:value="YOUR_MAPS_API_KEY" />
```

Get a Maps API key at: https://console.cloud.google.com/google/maps-apis

### 3. Build
```bash
./gradlew assembleDebug
# or release:
./gradlew assembleRelease
```

### 4. Deploy to Device
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 5. First Run
1. Grant **Location (Fine)** and **Camera** permissions
2. Go to **Settings** tab
3. Enter TUSAGA-Aktif credentials
4. Wait for **NTRIP: Connected** status (green dot in header)
5. Walk outdoors – watch RTK status change from SINGLE → DGPS → FLOAT → **FIX**
6. Switch to **AR View** when RTK FIX is achieved for centimeter accuracy

---

## RTK Positioning States

| Status | Color | Accuracy | Description |
|--------|-------|----------|-------------|
| **FIX** | 🟢 Green | 1–5 cm | RTK Fixed. TUSAGA corrections applied, carrier phase resolved. Best for AR. |
| **FLOAT** | 🟡 Orange | 10–50 cm | RTK Float. Corrections applied, phase ambiguity not fully resolved. |
| **DGPS** | 🔵 Blue | 0.3–3 m | Differential GPS. Code-only corrections. |
| **SINGLE** | 🔴 Red | 3–15 m | Standard GPS. No corrections. |
| **NONE** | ⚫ Gray | >15 m | No position fix. |

---

## GNSS Raw Measurements API

The app uses `GnssMeasurementsEvent.Callback` to access raw carrier phase and pseudorange data:

```kotlin
// In GnssManager.kt
private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
    override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
        // Detect L5 satellites
        val l5Sats = event.measurements.filter { m ->
            m.hasCarrierFrequencyHz() &&
            m.carrierFrequencyHz in 1_175_000_000f..1_178_000_000f
        }
        // l5Sats.size >= 3 → dual-frequency positioning available
    }
}
```

---

## NTRIP Protocol Implementation

The TUSAGA-Aktif connection uses raw TCP sockets with NTRIP Rev1:

```
Client → Server:
  GET /TUSK00TUR0 HTTP/1.0\r\n
  Host: cors.tusaga-aktif.gov.tr\r\n
  Ntrip-Version: Ntrip/1.0\r\n
  Authorization: Basic <base64(user:pass)>\r\n
  \r\n

Server → Client:
  ICY 200 OK\r\n
  <binary RTCM 3.x stream>

Client → Server (every 5s):
  $GPGGA,<UTC>,<lat>,N,<lon>,E,<quality>,<sats>,<hdop>,<alt>,M,...*<CS>\r\n
```

---

## Coordinate System Pipeline

```
GNSS Position (WGS84)        Infrastructure Point (GeoJSON WGS84)
        │                                    │
        ▼                                    ▼
 geodeticToEcef()                    geodeticToEcef()
        │                                    │
        ▼                                    ▼
   User ECEF ──────────────────── Feature ECEF
        │                                    │
        └──────── ecefToEnu() ───────────────┘
                       │
                       ▼
               ENU Displacement
              (East, North, Up) in meters
                       │
                       ▼
           ARCore Coordinate System
        (+X=East, +Y=Up, +Z=-North)
                       │
                       ▼
              AR Object Placement
```

---

## Loading Custom GeoJSON

```kotlin
// From ViewModel:
viewModel.loadGeoJsonFromAssets("my_pipes.geojson")

// Or from a string (e.g., from network/WFS):
viewModel.loadGeoJsonFromString(geoJsonString, "city_data")
```

### Required GeoJSON Properties
```json
{
  "id": "unique_id",
  "type": "water|sewer|gas|telecom|electric",
  "depth": -1.5,          // meters below surface (negative)
  "label": "Display Name"  // optional
}
```

---

## Performance Notes

- GNSS callbacks run on a dedicated `HandlerThread` (no main thread blocking)
- NTRIP socket I/O runs on `Dispatchers.IO` coroutine
- AR rendering: GL thread is separate from all GNSS processing
- GeoJSON parsing runs on `Dispatchers.Default`
- Max render distance: 100m (adjustable in `InfrastructureAnchorManager`)
- RTCM parser uses a single 4096-byte rolling buffer (zero-allocation hot path)

---

## Known Limitations

1. **Geoid undulation**: The built-in EGM2008 approximation uses a coarse 1° grid for Turkey. For sub-centimeter vertical accuracy, load the full EGM2008 geoid grid file.

2. **ITRF transformation**: The WGS84→ITRF96 Helmert parameters included are the official values but the shift is ~3mm – negligible for field RTK work.

3. **AR drift**: ARCore's VIO can drift several centimeters per minute. The app re-anchors infrastructure when RTK Fix is acquired. In tunnels or under cover, accuracy degrades to IMU-only.

4. **NTRIP Rev1 only**: TUSAGA-Aktif supports both Rev1 and Rev2. This implementation uses Rev1 (universally compatible). Upgrade to Rev2 for GNSS-specific streaming if needed.

---

## License
MIT License – see LICENSE file.

## Author
Built for production field use with Turkey TUSAGA-Aktif CORS network.
