package com.lemoyneiv.marcopolobridge.core

enum class WatchedApp(
    val displayName: String,
    val primaryPackage: String,
    val packageNames: Set<String>
) {
    MARCO_POLO(
        displayName = "Marco Polo",
        primaryPackage = "co.happybits.marcopolo",
        packageNames = setOf("co.happybits.marcopolo")
    ),
    SPEECHIFY(
        displayName = "Speechify",
        primaryPackage = "com.cliffweitzman.speechify2",
        packageNames = setOf(
            "com.cliffweitzman.speechify2",
            "com.cliffweitzman.speechify"
        )
    );

    companion object {
        fun fromPackage(packageName: String?): WatchedApp? {
            if (packageName.isNullOrBlank()) return null
            val trimmed = packageName.trim()
            return entries.firstOrNull { trimmed in it.packageNames }
        }
    }
}

enum class RoutingMode {
    /**
     * Pauses Android Auto media (holding AA MediaSession + AudioFocus) AND routes
     * Marco Polo / Speechify speech streams from the phone speaker to Car Bluetooth (HFP/BLE).
     */
    SMART_AUTO,

    /**
     * Pauses Android Auto media and holds AA's media audio channel with silent PCM,
     * without changing Bluetooth communication device routing.
     */
    AA_MEDIA_HOLD_ONLY
}

enum class ScoTiming {
    /** Opens car Bluetooth route as soon as a watched app enters the foreground. */
    ON_FOREGROUND,

    /** Opens car Bluetooth route only when active audio playback is detected. */
    ON_AUDIO_PLAYBACK
}

data class BridgeConfig(
    val autoDetectEnabled: Boolean = true,
    val enableMarcoPolo: Boolean = true,
    val enableSpeechify: Boolean = true,
    val keepPlayingInBackground: Boolean = true,
    val onlyWhenAndroidAutoConnected: Boolean = true,
    val routingMode: RoutingMode = RoutingMode.SMART_AUTO,
    val scoTiming: ScoTiming = ScoTiming.ON_FOREGROUND,
    val resumeMediaOnExit: Boolean = true,
    val preferredBtIdentifier: String = "",
    val exitGraceMs: Long = 2_000L,
    val playbackGapGraceMs: Long = 3_000L
) {
    fun isAppEnabled(app: WatchedApp): Boolean = when (app) {
        WatchedApp.MARCO_POLO -> enableMarcoPolo
        WatchedApp.SPEECHIFY -> enableSpeechify
    }
}

data class CommDeviceCandidate(
    val id: Int,
    val type: Int,
    val name: String,
    val address: String = ""
) {
    val isBluetoothComm: Boolean
        get() = type == TYPE_BLUETOOTH_SCO || type == TYPE_BLE_HEADSET || type == TYPE_BLE_SPEAKER

    companion object {
        // Mirrors android.media.AudioDeviceInfo constants for pure-JVM testability
        const val TYPE_BUILTIN_EARPIECE = 1
        const val TYPE_BUILTIN_SPEAKER = 2
        const val TYPE_BLUETOOTH_SCO = 7
        const val TYPE_BLUETOOTH_A2DP = 8
        const val TYPE_BLE_HEADSET = 26
        const val TYPE_BLE_SPEAKER = 27
    }
}

data class PolicySnapshot(
    val shouldHoldBridge: Boolean,
    val shouldEngageBtCommRoute: Boolean,
    val shouldPlaySilentMediaKeepalive: Boolean,
    val activeApp: WatchedApp?,
    val foregroundApp: WatchedApp?,
    val backgroundLatchedApp: WatchedApp?,
    val manualHold: Boolean,
    val inExitGrace: Boolean,
    val graceRemainingMs: Long,
    val yieldingToPhoneCall: Boolean,
    val reason: String
) {
    val targetAppForeground: Boolean
        get() = foregroundApp != null
}

