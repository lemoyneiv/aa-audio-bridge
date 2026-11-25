# Car Audio Bridge (`aa-audio-bridge`)

A lightweight, 100% offline Android companion app built for **Pixel 8 + Wireless Android Auto** (and Hyundai Ioniq 5 / `gen5w-suite` head units) so **Marco Polo** video messages and **Speechify** text-to-speech / AI voice reading play cleanly through the car speakers while keeping Wireless Android Auto connected.

## Why This Exists

When connected to Wireless Android Auto on a Pixel phone:
1. **Android Auto (`Gearhead`)** captures `USAGE_MEDIA` streams over Wi-Fi (`192.168.0.1`) and suppresses Bluetooth A2DP (`Media audio`), so Spotify / YouTube Music / Audible keeps playing through the car.
2. **Marco Polo (`co.happybits.marcopolo`)** plays video messages on `USAGE_VOICE_COMMUNICATION` (`STRATEGY_PHONE`), and **Speechify (`com.cliffweitzman.speechify2`)** plays TTS / AI voice reading on `USAGE_ASSISTANCE_ACCESSIBILITY` (`STRATEGY_ACCESSIBILITY`) or `USAGE_VOICE_COMMUNICATION` / `USAGE_ASSISTANT`. Neither stream is captured by Android Auto's Wi-Fi media mix, so both fall back to the Pixel's built-in speaker (`TYPE_BUILTIN_SPEAKER`) while Android Auto music keeps playing in the car.

## How It Works

1. **Detects Marco Polo & Non-AA Speech Automatically (Only in the Car)**:
   - Uses a privacy-minimal `AccessibilityService` (`canRetrieveWindowContent="false"`, `TYPE_WINDOW_STATE_CHANGED` only) combined with Jetpack `CarConnection` (`content://androidx.car.app.connection`) so it **only activates while Android Auto is actively projecting**—doing nothing at home and never holding Bluetooth HFP before Wireless Android Auto finishes connecting.
   - Filters out non-Activity floating overlays (such as Speechify's optional floating Voice Keyboard dictation bubble) so typing in other apps never triggers car audio.
2. **Respects Native Android Auto Media (`Speechify`) & Pauses Music for `Marco Polo`**:
   - **Speechify** exposes its own native Android Auto `MediaSession` over `USAGE_MEDIA` (streamed directly over Android Auto Wi-Fi at 48 kHz), so the bridge stays completely out of Speechify's way unless a non-AA speech stream (`USAGE_ASSISTANCE_ACCESSIBILITY` / `USAGE_VOICE_COMMUNICATION`) plays.
   - When **Marco Polo** opens, requests transient `AudioFocusRequest` (`USAGE_MEDIA`, `AUDIOFOCUS_GAIN_TRANSIENT`) to pause background music cleanly without opening a fake Bluetooth phone call while idle.
3. **Playback-Scoped `STRATEGY_PHONE` & `STRATEGY_ACCESSIBILITY` Routing**:
   - Engages `AudioManager.MODE_IN_COMMUNICATION` and selects your car's Bluetooth hands-free device (`AudioDeviceInfo.TYPE_BLUETOOTH_SCO` / `TYPE_BLE_HEADSET`) via `AudioManager.setCommunicationDevice(...)` **strictly while non-AA voice/accessibility audio is actively playing** (plus a `2.5 s` startup settle and `2.5 s` inter-clip grace), then immediately releases HFP SCO so the car head unit never stays stuck in call mode (`Feature is unavailable during a call`).
   - Compatible out-of-the-box with `gen5w-suite`'s `BtcmFastConnect`, `ParkSession`, and `FocusGuard`.
4. **Long-Form Background & Screen-Off Continuity**:
   - Keeps Car Bluetooth routing alive if the Pixel screen locks mid-Polo or mid-TTS stream, **and** if you swipe Home or switch to another app while non-AA speech continues playing in the background (bridging inter-paragraph synthesis gaps).
   - Releases automatically once playback stops (or immediately if you press Play on another Android Auto media app), smoothly resuming your previous Android Auto media.

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

