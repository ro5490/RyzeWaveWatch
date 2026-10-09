# Dapper’s P32 SmartTrax

**An Android companion app for the Motast P32 smartwatch**, built from the RyzeWaveWatch codebase and adapted for the P32's Bluetooth Low Energy (BLE) protocol.

SmartTrax aims to provide a practical alternative to the vendor companion app, with a focus on watch connectivity, health/activity data, and accurate weather synchronization. The Android app lives in [`ryzeapp/`](ryzeapp/).

> **Project status:** Active P32 adaptation. Some features and documentation inherited from RyzeWaveWatch were designed for different hardware and have not all been independently verified on the Motast P32.

## What SmartTrax does

- Connects to a compatible watch over BLE and provides a dashboard with connection and synchronization controls.
- Supports the existing app's health and activity workflows, including steps, heart rate, blood oxygen (SpO₂), sleep, and workout-related screens. Availability and accuracy depend on P32 firmware and individual feature compatibility.
- Includes the original codebase's GPS workout tracking and Health Connect integration; these are inherited capabilities, not a claim that every workflow has been retested on the P32.
- Sends current weather and a seven-day forecast to the P32, including temperatures and the appropriate weather-condition icons.
- Provides a **Sync weather** action without exposing protocol-debugging controls in the normal dashboard.

## P32 weather synchronization

Weather is retrieved from [Open-Meteo](https://open-meteo.com/) using a recent location supplied by Android. SmartTrax translates the provider's WMO weather codes, wind speed, and visibility into the P32's icon categories, then sends the watch's three-part `CB` weather command.

**The following condition codes and their displayed icons were verified on a physical Motast P32:**

| Code | Icon displayed on P32 |
|---|---|
| `1` | Sunny |
| `2` | Cloudy |
| `3` | Overcast |
| `4` | Shower |
| `5` | T-Storm |
| `6` | Sleet |
| `7` | Light rain |
| `8` | Heavy rain |
| `9` | Snow |
| `10` | Sand storm |
| `11` | Haze |
| `12` | Windy |

The condition-byte positions are **zero-based**: `CB01[2]` (today), `CB02[2,6,10,14]` (following four days), and `CB03[2,6]` (last two days). The current, high, and low temperature bytes in `CB01` are at offsets `4`, `5`, and `6`. These positions were recovered from GloryFit's decompiled weather implementation and checked against P32 behavior.

Automatic icon selection is an approximation between two different weather classification systems. For example, Open-Meteo has no dedicated sandstorm weather code; SmartTrax does not invent a sandstorm reading. Fog/low visibility may use the Haze icon, while strong wind may use Windy when precipitation is not the primary condition.

### Using weather sync

1. Connect the P32 to SmartTrax via Bluetooth.
2. Grant the app the relevant Bluetooth and location permissions, and ensure the phone has a recent location fix.
3. Keep an internet connection available so the app can contact Open-Meteo.
4. Use **Sync weather** on the dashboard. Check the watch for updated temperature and condition icons.

Weather fetching can fail if Android has no recent location (the current implementation requires a fix within two hours), network access is unavailable, or the watch is disconnected. Weather data is obtained from an external provider; **do not interpret the original project's “no internet permission” claim as applying to SmartTrax**.

## Building and installing

See [`BUILD.md`](BUILD.md) for the original Android build instructions and [`ryzeapp/`](ryzeapp/) for the Kotlin/Jetpack Compose project. The repository also includes a GitHub Actions workflow under [`.github/workflows/`](.github/workflows/).

The project is a fork/adaptation, so some source directories, package identifiers, scripts, screenshots, and older documentation still use `ryzewave` or refer to the Ryze Wave. Those historical names do not mean the P32 is a Ryze Wave or that the two watches have identical feature support.

## Technical notes

- **Target watch:** Motast P32.
- **Companion app:** Dapper’s P32 SmartTrax (Android).
- **BLE command service:** `55FF`, with write characteristic `33F1` and notification characteristic `33F2` observed in P32 testing.
- **Additional data service:** `56FF`, with `34F1` / `34F2` observed during BLE inspection.
- **Weather command:** Three `CB` packets, with watch responses `CB01`, `CB02`, and `CB03` observed on `33F2`.
- **Protocol research:** P32 testing, nRF Connect captures, and reverse engineering of the GloryFit Android app using JADX.

The original [`docs/PROTOCOL.md`](docs/PROTOCOL.md), [`docs/APP.md`](docs/APP.md), and other research documents primarily describe the original Ryze Wave project. Treat their device-specific details as **historical reference**, not automatically verified P32 documentation.

## Privacy and health information

SmartTrax communicates with the watch over BLE. Weather synchronization uses a phone location fix to request forecast data from Open-Meteo over the internet. Other permissions and data handling depend on the features enabled in the app; review Android's permission prompts and the source code for the precise behavior of your build.

Health measurements and workout statistics are informational and are **not medical-grade measurements or medical advice**.

## Credits, licence, and disclaimer

SmartTrax is adapted from **RyzeWaveWatch**, originally developed by **David Buzz**. The existing copyright notices, [`LICENSE`](LICENSE), and [`NOTICE`](NOTICE) remain applicable. The original project is source-available under the **PolyForm Noncommercial License 1.0.0**; it is **not an unrestricted open-source licence**. Commercial use requires separate permission under its terms.

This is an independent community adaptation. It is not affiliated with, endorsed by, or supported by Motast, GloryFit, Ryze Above, or their respective developers. Use at your own risk.
