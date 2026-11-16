# Car Audio Bridge (`marcopolo-aa-bridge`)

A lightweight, 100% offline Android companion app built for **Pixel 8 + Wireless Android Auto** (and Hyundai Ioniq 5 / `gen5w-suite` head units) so **Marco Polo** video messages and **Speechify** text-to-speech / AI voice reading play cleanly through the car speakers while keeping Wireless Android Auto connected.

## Why This Exists

When connected to Wireless Android Auto on a Pixel phone:
1. **Android Auto (`Gearhead`)** captures `USAGE_MEDIA` streams over Wi-Fi (`192.168.0.1`) and suppresses Bluetooth A2DP (`Media audio`), so Spotify / YouTube Music / Audible keeps playing through the car.
2. **Marco Polo (`co.happybits.marcopolo`)** plays video messages on `USAGE_VOICE_COMMUNICATION` (`STRATEGY_PHONE`), and **Speechify (`com.cliffweitzman.speechify2`)** plays TTS / AI voice reading on `USAGE_ASSISTANCE_ACCESSIBILITY` (`STRATEGY_ACCESSIBILITY`) or `USAGE_VOICE_COMMUNICATION` / `USAGE_ASSISTANT`. Neither stream is captured by Android Auto's Wi-Fi media mix, so both fall back to the Pixel's built-in speaker (`TYPE_BUILTIN_SPEAKER`) while Android Auto music keeps playing in the car.

## How It Works

1. **Detects Marco Polo & Speechify Automatically (Only in the Car)**:
   - Uses a privacy-minimal `AccessibilityService` (`canRetrieveWindowContent="false"`, `TYPE_WINDOW_STATE_CHANGED` only) combined with Jetpack `CarConnection` (`content://androidx.car.app.connection`) so it **only activates while Android Auto is connected**—doing nothing when you open Marco Polo or Speechify at home.
   - Filters out non-Activity floating overlays (such as Speechify's optional floating Voice Keyboard dictation bubble) so typing in other apps never triggers car audio.
2. **Pauses Android Auto Music & Never Fights App Playback**:
   - Requests `AudioFocusRequest` (`USAGE_MEDIA`, `AUDIOFOCUS_GAIN_TRANSIENT` by default) and activates an Android Auto `MediaSessionCompat` (`BridgeMediaBrowserService`) so car music pauses immediately.
   - If Speechify claims media focus itself while in the foreground, the bridge yields media focus cleanly to Speechify without pausing it, while keeping Car Bluetooth routing active.
3. **Routes Both `STRATEGY_PHONE` & `STRATEGY_ACCESSIBILITY` to Car Speakers**:
   - Engages `AudioManager.MODE_IN_COMMUNICATION` with a zero-CPU `MODE_STATIC` silent `USAGE_VOICE_COMMUNICATION` primer and selects your car's Bluetooth hands-free device (`AudioDeviceInfo.TYPE_BLUETOOTH_SCO` / `TYPE_BLE_HEADSET`) via `AudioManager.setCommunicationDevice(...)`—routing both Marco Polo (`STRATEGY_PHONE`) and Speechify TTS (`STRATEGY_ACCESSIBILITY` / `STRATEGY_PHONE`) to the car speakers.
   - Compatible out-of-the-box with `gen5w-suite`'s `FocusGuard` (which permits `STREAM_VOICE_CALL` while blocking stray `MODE_BT_AUDIO` takeovers).
4. **Long-Form Background & Screen-Off Continuity**:
   - Keeps Car Bluetooth routing alive if the Pixel screen locks mid-Polo or mid-article, **and** if you swipe Home or switch to another app while Speechify continues reading aloud in the background (bridging inter-paragraph TTS synthesis gaps).
   - Releases automatically once playback stops (or immediately if you press Play on Spotify on the Android Auto screen), smoothly resuming your previous Android Auto media.

## Zero Network / Minimal Permissions

- **No `INTERNET` permission** — 100% offline.
- **No `RECORD_AUDIO` or `CAMERA` permission**.
- **No screen-reading capability** (`canRetrieveWindowContent="false"`).

## One-Time Pixel 8 Setup (~60 Seconds)

1. **Install the APK** (`app-release.apk` or `app-debug.apk`) on the Pixel 8 (or run `adb install -r app/build/outputs/apk/release/app-release.apk`).
2. Open **Car Audio Bridge** and follow the 4-step card:
   - **Step 1 — Grant Permissions**: Tap **Grant Permissions** to allow **Nearby devices** (`BLUETOOTH_CONNECT`) and **Notifications**.
   - **Step 2 — Enable Auto-Detect (Accessibility)**:
     - *Note for sideloaded APKs on Pixel (Android 13+)*: If the Accessibility toggle says **"Restricted setting"**, tap **1. App Info** in the app → tap the top-right **⋮** menu → tap **Allow restricted settings**, then tap **2. Accessibility** and turn on **Car Audio Bridge**. *(Installing via `adb install` skips Android's restricted-setting step automatically.)*
   - **Step 3 — Unrestricted Battery**: Tap **Open App Battery Settings** → **App battery usage** → select **Unrestricted**.
   - **Step 4 — Optional Android Auto Car Screen Tile**: To also show manual Hold/Release controls directly on the Ioniq 5's Android Auto screen, open **Android Auto Settings** → scroll to the bottom and tap **Version** 10 times → tap top-right **⋮** → **Developer settings** → check **Unknown sources**.

You can also add the **Car Audio Bridge** Quick Settings tile in the Pixel notification shade, or use hands-free Google Assistant voice commands in Android Auto (*"Play Car Audio Bridge"* / *"Stop Car Audio Bridge"*), for manual control anytime.

## Building & Testing

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Output APKs:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

