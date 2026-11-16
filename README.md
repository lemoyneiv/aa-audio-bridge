# Marco Polo Car Bridge (`marcopolo-aa-bridge`)

A lightweight, 100% offline Android companion app built for **Pixel 8 + Wireless Android Auto** (and Hyundai Ioniq 5 / `gen5w-suite` head units) so **Marco Polo** video messages play cleanly through the car speakers while keeping Wireless Android Auto connected.

## Why This Exists

When connected to Wireless Android Auto on a Pixel phone:
1. **Android Auto (`Gearhead`)** captures `USAGE_MEDIA` streams over Wi-Fi (`192.168.0.1`) and suppresses Bluetooth A2DP (`Media audio`), so Spotify / YouTube Music / Audible keeps playing through the car.
2. **Marco Polo (`co.happybits.marcopolo`)** plays video messages using `USAGE_VOICE_COMMUNICATION` (`MODE_IN_COMMUNICATION`) and defaults to the phone's built-in speaker (`TYPE_BUILTIN_SPEAKER`) instead of requesting Android Auto media focus or opening the car's Bluetooth hands-free link (`TYPE_BLUETOOTH_SCO`).

As a result, Android Auto continues playing music in the car while Marco Polo plays out of the phone speaker.

## How It Works

1. **Detects Marco Polo Automatically (Only in the Car)**:
   - Uses a privacy-minimal `AccessibilityService` (`canRetrieveWindowContent="false"`, `TYPE_WINDOW_STATE_CHANGED` only) combined with Jetpack `CarConnection` (`content://androidx.car.app.connection`) so it **only activates while Android Auto is connected**—doing nothing when you open Marco Polo at home.
2. **Pauses Android Auto Music & Holds the Session**:
   - Requests `AudioFocusRequest` (`USAGE_MEDIA`, `AUDIOFOCUS_GAIN_TRANSIENT` by default) and activates an Android Auto `MediaSessionCompat` (`BridgeMediaBrowserService`) so Spotify / YouTube Music pauses immediately and never auto-resumes between back-to-back Polos.
3. **Routes Marco Polo Audio from Phone Speaker to Car Speakers**:
   - Engages `AudioManager.MODE_IN_COMMUNICATION` with a zero-CPU `MODE_STATIC` silent `USAGE_VOICE_COMMUNICATION` primer and selects your car's Bluetooth hands-free device (`AudioDeviceInfo.TYPE_BLUETOOTH_SCO` / `TYPE_BLE_HEADSET`) via `AudioManager.setCommunicationDevice(...)`.
   - Listens to `OnCommunicationDeviceChangedListener` and `AudioPlaybackCallback` to immediately re-assert the car Bluetooth route if Marco Polo tries to flip back to speakerphone when starting a clip (with cellular-call yield and rate-limit backoff).
   - Compatible out-of-the-box with `gen5w-suite`'s `FocusGuard` (which permits `STREAM_VOICE_CALL` while blocking stray `MODE_BT_AUDIO` takeovers).
4. **Restores Everything on Exit**:
   - Two seconds after you switch away from Marco Polo (or after a playing Polo finishes if the screen locked mid-message), it clears the Bluetooth communication route and abandons transient media focus so your previous Android Auto music smoothly resumes.

## Zero Network / Minimal Permissions

- **No `INTERNET` permission** — 100% offline.
- **No `RECORD_AUDIO` or `CAMERA` permission**.
- **No screen-reading capability** (`canRetrieveWindowContent="false"`).

## One-Time Pixel 8 Setup (~60 Seconds)

1. **Install the APK** (`app-release.apk` or `app-debug.apk`) on the Pixel 8 (or run `adb install -r app/build/outputs/apk/release/app-release.apk`).
2. Open **Marco Polo Car Bridge** and follow the 4-step card:
   - **Step 1 — Grant Permissions**: Tap **Grant Permissions** to allow **Nearby devices** (`BLUETOOTH_CONNECT`) and **Notifications**.
   - **Step 2 — Enable Auto-Detect (Accessibility)**:
     - *Note for sideloaded APKs on Pixel (Android 13+)*: If the Accessibility toggle says **"Restricted setting"**, tap **1. App Info** in the app → tap the top-right **⋮** menu → tap **Allow restricted settings**, then tap **2. Accessibility** and turn on **Marco Polo Car Bridge**. *(Installing via `adb install` skips Android's restricted-setting step automatically.)*
   - **Step 3 — Unrestricted Battery**: Tap **Open App Battery Settings** → **App battery usage** → select **Unrestricted**.
   - **Step 4 — Optional Android Auto Car Screen Tile**: To also show manual Hold/Release controls directly on the Ioniq 5's Android Auto screen, open **Android Auto Settings** → scroll to the bottom and tap **Version** 10 times → tap top-right **⋮** → **Developer settings** → check **Unknown sources**.

You can also add the **Marco Polo Audio** Quick Settings tile in the Pixel notification shade for 1-tap manual control anytime.

## Building & Testing

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Output APKs:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`
