package com.lemoyneiv.marcopolobridge.core

import java.util.Locale

/**
 * Pure Kotlin policy & state machine deciding when to hold Android Auto media focus
 * and when to route communication audio to the car's Bluetooth hands-free channel.
 *
 * Free of Android framework imports so all foreground transitions, transient overlays,
 * screen-off playback continuity, cellular call yields, and speakerphone anti-hijack
 * rate limits are deterministically unit-tested on the host JVM.
 */
class BridgePolicy(
    private val targetPackages: Set<String> = setOf(PKG_MARCO_POLO)
) {
    companion object {
        const val PKG_MARCO_POLO = "co.happybits.marcopolo"

        // Mirrors android.media.AudioManager mode constants
        const val AUDIO_MODE_NORMAL = 0
        const val AUDIO_MODE_RINGTONE = 1
        const val AUDIO_MODE_IN_CALL = 2
        const val AUDIO_MODE_IN_COMMUNICATION = 3

        const val MAX_REASSERTIONS_IN_WINDOW = 5
        const val REASSERT_WINDOW_MS = 6_000L
        const val MIN_REASSERT_GAP_MS = 120L

        private val TRANSIENT_OVERLAY_PREFIXES = listOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.inputmethod",
            "com.samsung.android.honeyboard",
            "com.touchtype.swiftkey",
            "com.android.inputmethod"
        )

        fun isTransientOverlay(packageName: String?): Boolean {
            if (packageName.isNullOrBlank()) return true
            val lower = packageName.lowercase(Locale.US)
            if (TRANSIENT_OVERLAY_PREFIXES.any { lower.startsWith(it) }) return true
            if (lower.contains("inputmethod") || lower.contains(".keyboard")) return true
            return false
        }
    }

    private var config: BridgeConfig = BridgeConfig()
    private var marcoPoloInForeground: Boolean = false
    private var manualHoldActive: Boolean = false
    private var suppressedForCurrentVisit: Boolean = false
    private var aaConnected: Boolean = false
    private var screenInteractive: Boolean = true
    private var externalVoiceOrMediaPlaying: Boolean = false
    private var audioMode: Int = AUDIO_MODE_NORMAL
    private var exitGraceUntilMs: Long = 0L

    private val reassertionTimestamps = ArrayDeque<Long>()
    private var routeBackoffActive: Boolean = false

    @Synchronized
    fun updateConfig(newConfig: BridgeConfig, nowMs: Long): PolicySnapshot {
        config = newConfig
        return evaluate(nowMs)
    }

    @Synchronized
    fun onAndroidAutoConnectionChanged(connected: Boolean, nowMs: Long): PolicySnapshot {
        aaConnected = connected
        if (!connected && config.onlyWhenAndroidAutoConnected && !manualHoldActive) {
            exitGraceUntilMs = 0L
        }
        return evaluate(nowMs)
    }

    @Synchronized
    fun onScreenInteractiveChanged(interactive: Boolean, nowMs: Long): PolicySnapshot {
        val wasHolding = computeAutoHold(nowMs)
        screenInteractive = interactive
        if (!interactive && marcoPoloInForeground && wasHolding && !externalVoiceOrMediaPlaying) {
            // Give a grace period when locking screen if nothing is actively playing;
            // if a Polo IS actively playing when screen turns off, externalVoiceOrMediaPlaying
            // keeps the session alive until the message finishes!
            exitGraceUntilMs = maxOf(exitGraceUntilMs, nowMs + config.exitGraceMs)
        }
        return evaluate(nowMs)
    }

    @Synchronized
    fun onForegroundPackageChanged(packageName: String?, nowMs: Long): PolicySnapshot {
        if (isTransientOverlay(packageName)) {
            return evaluate(nowMs)
        }
        val pkg = packageName!!.trim()
        val isTarget = targetPackages.contains(pkg)
        if (isTarget) {
            if (!marcoPoloInForeground) {
                marcoPoloInForeground = true
                exitGraceUntilMs = 0L
                resetReassertionBudget()
            }
        } else {
            if (marcoPoloInForeground) {
                val wasAutoHolding = computeAutoHold(nowMs)
                marcoPoloInForeground = false
                suppressedForCurrentVisit = false
                resetReassertionBudget()
                if (wasAutoHolding && config.exitGraceMs > 0L) {
                    exitGraceUntilMs = nowMs + config.exitGraceMs
                } else {
                    exitGraceUntilMs = 0L
                }
            }
        }
        return evaluate(nowMs)
    }

    @Synchronized
    fun onExternalAudioActiveChanged(active: Boolean, nowMs: Long): PolicySnapshot {
        val previouslyActive = externalVoiceOrMediaPlaying
        externalVoiceOrMediaPlaying = active
        if (previouslyActive && !active && marcoPoloInForeground && !screenInteractive) {
            // Message finished playing while screen was off -> enter exit grace
            exitGraceUntilMs = nowMs + config.exitGraceMs
        }
        if (active && !previouslyActive) {
            // New Polo clip started -> allow fresh route re-assertion if Marco Polo flipped speakerphone
            routeBackoffActive = false
        }
        return evaluate(nowMs)
    }

    @Synchronized
    fun onAudioModeChanged(newMode: Int, nowMs: Long): PolicySnapshot {
        audioMode = newMode
        return evaluate(nowMs)
    }

    @Synchronized
    fun setManualHold(enabled: Boolean, nowMs: Long): PolicySnapshot {
        manualHoldActive = enabled
        if (enabled) {
            suppressedForCurrentVisit = false
            resetReassertionBudget()
        } else if (marcoPoloInForeground) {
            // User explicitly tapped Release while inside Marco Polo -> stay out of the way
            // for the rest of this Marco Polo foreground visit.
            suppressedForCurrentVisit = true
            exitGraceUntilMs = 0L
        }
        return evaluate(nowMs)
    }

    @Synchronized
    fun toggleManualHold(nowMs: Long): PolicySnapshot {
        val currentlyHolding = evaluate(nowMs).shouldHoldBridge
        return setManualHold(!currentlyHolding, nowMs)
    }

    @Synchronized
    fun resetReassertionBudget() {
        reassertionTimestamps.clear()
        routeBackoffActive = false
    }

    /**
     * Selects the best Bluetooth communication device (e.g. Ioniq 5 HFP SCO or BLE Headset)
     * from AudioManager.getAvailableCommunicationDevices().
     */
    @Synchronized
    fun selectTargetCommDevice(candidates: List<CommDeviceCandidate>): CommDeviceCandidate? {
        val btDevices = candidates.filter { it.isBluetoothComm }
        if (btDevices.isEmpty()) return null

        val pref = config.preferredBtIdentifier.trim()
        if (pref.isNotEmpty()) {
            val exactAddr = btDevices.firstOrNull { it.address.equals(pref, ignoreCase = true) }
            if (exactAddr != null) return exactAddr
            val byName = btDevices.firstOrNull { it.name.contains(pref, ignoreCase = true) }
            if (byName != null) return byName
        }

        // Prefer classic car HFP SCO (non-watch) over smartwatches, then BLE headsets, then any BT comm
        return btDevices.sortedWith(
            compareBy<CommDeviceCandidate> { isLikelyWatch(it.name) }
                .thenBy { deviceTypePriority(it.type) }
        ).firstOrNull()
    }

    private fun isLikelyWatch(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.contains("watch") || lower.contains("fitbit") || lower.contains("garmin")
    }

    private fun deviceTypePriority(type: Int): Int = when (type) {
        CommDeviceCandidate.TYPE_BLUETOOTH_SCO -> 0
        CommDeviceCandidate.TYPE_BLE_HEADSET -> 1
        CommDeviceCandidate.TYPE_BLE_SPEAKER -> 2
        else -> 3
    }

    /**
     * Determines whether we should re-assert setMode(MODE_IN_COMMUNICATION) +
     * setCommunicationDevice(targetBt) after Marco Polo attempts to switch back to speakerphone.
     */
    @Synchronized
    fun shouldReassertBtRoute(
        currentActiveDeviceId: Int?,
        targetDevice: CommDeviceCandidate?,
        nowMs: Long
    ): Boolean {
        val snap = evaluate(nowMs)
        if (!snap.shouldEngageBtCommRoute || targetDevice == null) return false
        if (currentActiveDeviceId == targetDevice.id) return false
        if (routeBackoffActive) return false

        while (reassertionTimestamps.isNotEmpty() &&
            nowMs - reassertionTimestamps.first() > REASSERT_WINDOW_MS
        ) {
            reassertionTimestamps.removeFirst()
        }

        val lastAt = reassertionTimestamps.lastOrNull()
        if (lastAt != null && nowMs - lastAt < MIN_REASSERT_GAP_MS) {
            return false
        }

        if (reassertionTimestamps.size >= MAX_REASSERTIONS_IN_WINDOW) {
            routeBackoffActive = true
            return false
        }

        reassertionTimestamps.addLast(nowMs)
        return true
    }

    private fun computeAutoHold(nowMs: Long): Boolean {
        if (!config.autoDetectEnabled) return false
        if (suppressedForCurrentVisit) return false
        if (config.onlyWhenAndroidAutoConnected && !aaConnected) return false

        if (marcoPoloInForeground) {
            if (screenInteractive || externalVoiceOrMediaPlaying) {
                return true
            }
        }
        return nowMs < exitGraceUntilMs
    }

    @Synchronized
    fun evaluate(nowMs: Long): PolicySnapshot {
        val inCall = audioMode == AUDIO_MODE_IN_CALL || audioMode == AUDIO_MODE_RINGTONE
        val inGrace = !marcoPoloInForeground && nowMs < exitGraceUntilMs
        val autoHold = computeAutoHold(nowMs)
        val shouldHold = !inCall && (manualHoldActive || autoHold)

        val shouldBtComm = shouldHold &&
            config.routingMode == RoutingMode.SMART_AUTO &&
            (manualHoldActive ||
                config.scoTiming == ScoTiming.ON_FOREGROUND ||
                externalVoiceOrMediaPlaying)

        val shouldSilentMedia = shouldHold &&
            (config.routingMode == RoutingMode.AA_MEDIA_HOLD_ONLY || !shouldBtComm)

        val reason = when {
            inCall -> "Paused for cellular phone call"
            manualHoldActive -> "Manual hold active"
            autoHold && inGrace -> "Holding briefly after leaving Marco Polo"
            autoHold && !screenInteractive && externalVoiceOrMediaPlaying ->
                "Marco Polo playing with screen off"
            autoHold -> "Marco Polo active"
            marcoPoloInForeground && suppressedForCurrentVisit ->
                "Released manually for current Marco Polo session"
            marcoPoloInForeground && config.onlyWhenAndroidAutoConnected && !aaConnected ->
                "Marco Polo open (waiting for Android Auto)"
            aaConnected -> "Armed — Android Auto connected"
            else -> "Idle"
        }

        return PolicySnapshot(
            shouldHoldBridge = shouldHold,
            shouldEngageBtCommRoute = shouldBtComm,
            shouldPlaySilentMediaKeepalive = shouldSilentMedia,
            marcoPoloForeground = marcoPoloInForeground,
            manualHold = manualHoldActive,
            inExitGrace = inGrace,
            yieldingToPhoneCall = inCall,
            reason = reason
        )
    }
}
