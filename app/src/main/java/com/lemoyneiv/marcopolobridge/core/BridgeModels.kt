package com.lemoyneiv.marcopolobridge.core

enum class RoutingMode {
    /**
     * Pauses Android Auto media (holding AA MediaSession + AudioFocus) AND routes
     * Marco Polo's communication stream from the phone speaker to Car Bluetooth (HFP/BLE).
     */
    SMART_AUTO,

    /**
     * Pauses Android Auto media and holds AA's media audio channel with silent PCM,
     * without changing Bluetooth communication device routing.
     */
    AA_MEDIA_HOLD_ONLY
}

enum class ScoTiming {
    /** Opens car Bluetooth route as soon as Marco Polo enters the foreground. */
    ON_FOREGROUND,

    /** Opens car Bluetooth route only when active audio playback/recording is detected. */
    ON_AUDIO_PLAYBACK
}

data class BridgeConfig(
    val autoDetectEnabled: Boolean = true,
    val onlyWhenAndroidAutoConnected: Boolean = true,
    val routingMode: RoutingMode = RoutingMode.SMART_AUTO,
    val scoTiming: ScoTiming = ScoTiming.ON_FOREGROUND,
    val resumeMediaOnExit: Boolean = true,
    val preferredBtIdentifier: String = "",
    val exitGraceMs: Long = 2_000L
)

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
    val marcoPoloForeground: Boolean,
    val manualHold: Boolean,
    val inExitGrace: Boolean,
    val yieldingToPhoneCall: Boolean,
    val reason: String
)
