package com.lemoyneiv.marcopolobridge.core

import java.util.Locale

/**
 * Pure Kotlin policy & state machine deciding when to hold Android Auto media focus
 * and when to route speech/communication audio to the car's Bluetooth hands-free channel
 * for Marco Polo and Speechify.
 *
 * Free of Android framework imports so all foreground transitions, transient overlays,
 * screen-off & background TTS reading continuity, cellular call yields, and speakerphone
 * anti-hijack rate limits are deterministically unit-tested on the host JVM.
 */
class BridgePolicy {
    companion object {
        const val PKG_MARCO_POLO = "co.happybits.marcopolo"
        const val PKG_SPEECHIFY = "com.cliffweitzman.speechify2"

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
    private var foregroundApp: WatchedApp? = null
    private var backgroundLatchedApp: WatchedApp? = null
    private var graceApp: WatchedApp? = null
    private var manualHoldActive: Boolean = false
    private var suppressedForCurrentVisit: Boolean = false
    private var suppressedUntilPlayback: Boolean = false
    private var aaConnected: Boolean = false
    private var aaConnectedAtMs: Long = 0L
    private var screenInteractive: Boolean = true
    private var externalAudioPlaying: Boolean = false
    private var audioMode: Int = AUDIO_MODE_NORMAL
    private var exitGraceUntilMs: Long = 0L
    private var scoHoldUntilMs: Long = 0L

    private val reassertionTimestamps = ArrayDeque<Long>()
    private var routeBackoffActive: Boolean = false

    @Synchronized
    fun updateConfig(newConfig: BridgeConfig, nowMs: Long): PolicySnapshot {
        config = newConfig
        val bg = backgroundLatchedApp
        if (bg != null && (!newConfig.keepPlayingInBackground || !newConfig.isAppEnabled(bg))) {
            backgroundLatchedApp = null
        }
        val ga = graceApp
        if (ga != null && !newConfig.isAppEnabled(ga)) {
            graceApp = null
            exitGraceUntilMs = 0L
        }
        return evaluate(nowMs)
    }

    @Synchronized
    fun onAndroidAutoConnectionChanged(connected: Boolean, nowMs: Long): PolicySnapshot {
        val wasConnected = aaConnected
        aaConnected = connected
        if (connected && !wasConnected) {
            aaConnectedAtMs = nowMs
        } else if (!connected) {
            aaConnectedAtMs = 0L
            scoHoldUntilMs = 0L
            if (wasConnected) {
                // End of Android Auto session -> never carry Manual Hold into next car start
                manualHoldActive = false
            }
            if (config.onlyWhenAndroidAutoConnected && !manualHoldActive) {
                backgroundLatchedApp = null
                graceApp = null
                exitGraceUntilMs = 0L
            }
        }
        return evaluate(nowMs)
    }

    @Synchronized
    fun onScreenInteractiveChanged(interactive: Boolean, nowMs: Long): PolicySnapshot {
        val wasHolding = computeAutoHold(nowMs)
        screenInteractive = interactive
        val fg = foregroundApp
        if (!interactive && fg != null && wasHolding && !externalAudioPlaying) {
            // Give a grace period when locking screen if nothing is actively playing;
            // if a Polo or non-AA TTS stream IS actively playing when screen turns off,
            // externalAudioPlaying keeps the session alive until playback finishes!
            graceApp = fg
            exitGraceUntilMs = maxOf(exitGraceUntilMs, nowMs + config.exitGraceMs)
        }
        return evaluate(nowMs)
    }

    @Synchronized
    fun onForegroundPackageChanged(packageName: String?, nowMs: Long): PolicySnapshot {
        if (isTransientOverlay(packageName)) {
            return evaluate(nowMs)
        }
        val matchedApp = WatchedApp.fromPackage(packageName)
        if (matchedApp != null) {
            if (foregroundApp != matchedApp) {
                foregroundApp = matchedApp
                backgroundLatchedApp = null
                graceApp = null
                suppressedForCurrentVisit = false
                suppressedUntilPlayback = false
                exitGraceUntilMs = 0L
                resetReassertionBudget()
            }
        } else {
            val prevFg = foregroundApp
            if (prevFg != null) {
                val wasAutoHolding = computeAutoHold(nowMs)
                foregroundApp = null
                suppressedForCurrentVisit = false
                suppressedUntilPlayback = false
                resetReassertionBudget()
                if (wasAutoHolding) {
                    if (externalAudioPlaying && config.keepPlayingInBackground) {
                        // Non-AA speech/Polo stream is actively playing when the user swipes
                        // Home or switches to Maps -> latch in background until audio stops!
                        backgroundLatchedApp = prevFg
                        graceApp = prevFg
                        exitGraceUntilMs = 0L
                    } else if (config.exitGraceMs > 0L) {
                        backgroundLatchedApp = null
                        graceApp = prevFg
                        exitGraceUntilMs = nowMs + config.exitGraceMs
                    } else {
                        backgroundLatchedApp = null
                        graceApp = null
                        exitGraceUntilMs = 0L
                    }
                } else {
                    backgroundLatchedApp = null
                    graceApp = null
                    exitGraceUntilMs = 0L
                }
            }
        }
        return evaluate(nowMs)
    }

    @Synchronized
    fun onExternalAudioActiveChanged(active: Boolean, nowMs: Long): PolicySnapshot {
        val previouslyActive = externalAudioPlaying
        externalAudioPlaying = active

        if (active) {
            suppressedUntilPlayback = false
            scoHoldUntilMs = 0L
            if (!previouslyActive) {
                // New clip or TTS paragraph started -> allow fresh route re-assertion
                routeBackoffActive = false
            }
            if (backgroundLatchedApp != null || foregroundApp != null) {
                // Active audio resumed within any paragraph gap -> clear pending grace expiry
                exitGraceUntilMs = 0L
            }
        } else if (previouslyActive) {
            val fg = foregroundApp
            val bg = backgroundLatchedApp
            if (config.playbackGapGraceMs > 0L && (fg != null || bg != null)) {
                // Hold BT HFP route briefly across inter-clip / inter-paragraph gaps,
                // then release HFP SCO cleanly so the car never stays in a fake call!
                scoHoldUntilMs = nowMs + config.playbackGapGraceMs
            }
            if (bg != null) {
                graceApp = bg
                exitGraceUntilMs = nowMs + config.playbackGapGraceMs
            } else if (fg != null && (!screenInteractive || fg.usesNativeAaMedia) && config.isAppEnabled(fg)) {
                graceApp = fg
                exitGraceUntilMs = nowMs + config.playbackGapGraceMs
            }
        }
        return evaluate(nowMs)
    }

    /**
     * Called when another media app (e.g. user tapping Play on Spotify or Speechify on the
     * Android Auto screen) claims permanent `AUDIOFOCUS_GAIN`. Yields immediately when we are
     * only holding for background continuation or sitting idle with no active voice message.
     */
    @Synchronized
    fun onMediaAudioFocusLost(nowMs: Long): PolicySnapshot {
        if (!manualHoldActive) {
            if (foregroundApp == null) {
                backgroundLatchedApp = null
                graceApp = null
                exitGraceUntilMs = 0L
                scoHoldUntilMs = 0L
            } else if (!externalAudioPlaying) {
                suppressedUntilPlayback = true
                scoHoldUntilMs = 0L
            }
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
            suppressedUntilPlayback = false
            resetReassertionBudget()
        } else {
            backgroundLatchedApp = null
            graceApp = null
            exitGraceUntilMs = 0L
            scoHoldUntilMs = 0L
            if (foregroundApp != null) {
                // User explicitly tapped Release while inside Marco Polo / Speechify ->
                // stay out of the way for the rest of this foreground visit.
                suppressedForCurrentVisit = true
            }
        }
        return evaluate(nowMs)
    }

    @Synchronized
    fun toggleManualHold(nowMs: Long): PolicySnapshot {
        val snap = evaluate(nowMs)
        val currentlyHoldingOrArmed = snap.shouldHoldBridge || manualHoldActive
        return setManualHold(!currentlyHoldingOrArmed, nowMs)
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
     * setCommunicationDevice(targetBt) after an app attempts to switch back to speakerphone.
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

    private fun pruneExpiredBackgroundState(nowMs: Long) {
        if (scoHoldUntilMs > 0L && nowMs >= scoHoldUntilMs) {
            scoHoldUntilMs = 0L
        }
        if (backgroundLatchedApp != null && !externalAudioPlaying && nowMs >= exitGraceUntilMs) {
            backgroundLatchedApp = null
        }
        if (exitGraceUntilMs > 0L && nowMs >= exitGraceUntilMs) {
            exitGraceUntilMs = 0L
            if (foregroundApp == null && backgroundLatchedApp == null) {
                graceApp = null
            }
        }
    }

    private fun computeAutoHold(nowMs: Long): Boolean {
        pruneExpiredBackgroundState(nowMs)
        if (!config.autoDetectEnabled) return false
        if (suppressedForCurrentVisit) return false
        if (suppressedUntilPlayback && !externalAudioPlaying) return false
        if (config.onlyWhenAndroidAutoConnected && !aaConnected) return false

        val fg = foregroundApp
        if (fg != null && config.isAppEnabled(fg)) {
            if (fg.usesNativeAaMedia) {
                // Speechify exposes a native Android Auto MediaSession over USAGE_MEDIA, which
                // Wireless Android Auto already plays directly through car speakers over Wi-Fi.
                // Stay passive unless a non-AA speech/accessibility stream actually plays!
                if (externalAudioPlaying || nowMs < exitGraceUntilMs) {
                    return true
                }
            } else if (screenInteractive || externalAudioPlaying) {
                return true
            }
        }

        val bg = backgroundLatchedApp
        if (bg != null && config.keepPlayingInBackground && config.isAppEnabled(bg)) {
            if (externalAudioPlaying || nowMs < exitGraceUntilMs) {
                return true
            }
        }

        return nowMs < exitGraceUntilMs
    }

    @Synchronized
    fun evaluate(nowMs: Long): PolicySnapshot {
        val inCall = audioMode == AUDIO_MODE_IN_CALL || audioMode == AUDIO_MODE_RINGTONE
        val autoHold = computeAutoHold(nowMs)
        val fg = foregroundApp?.takeIf { config.isAppEnabled(it) }
        val bg = backgroundLatchedApp?.takeIf { config.isAppEnabled(it) }
        val exitGraceRemaining = maxOf(0L, exitGraceUntilMs - nowMs)
        val scoGraceRemaining = maxOf(0L, scoHoldUntilMs - nowMs)
        val inGrace = exitGraceRemaining > 0L

        val manualAllowed = manualHoldActive && (!config.onlyWhenAndroidAutoConnected || aaConnected)
        val shouldHold = !inCall && (manualAllowed || autoHold)

        val settleRemaining = if (shouldHold && config.onlyWhenAndroidAutoConnected && aaConnected) {
            maxOf(0L, (aaConnectedAtMs + config.aaConnectSettleMs) - nowMs)
        } else {
            0L
        }
        val aaSettledForSco = !config.onlyWhenAndroidAutoConnected ||
            (aaConnected && settleRemaining == 0L)

        val activeApp = when {
            !shouldHold -> null
            fg != null -> fg
            bg != null -> bg
            inGrace -> graceApp
            else -> null
        }

        val fgImmediateSco = config.scoTiming == ScoTiming.ON_FOREGROUND &&
            fg != null && !fg.usesNativeAaMedia
        val scoActiveOrGrace = externalAudioPlaying || scoGraceRemaining > 0L

        val shouldBtComm = shouldHold &&
            aaSettledForSco &&
            config.routingMode == RoutingMode.SMART_AUTO &&
            (manualAllowed || fgImmediateSco || scoActiveOrGrace)

        val shouldSilentMedia = shouldHold &&
            (config.routingMode == RoutingMode.AA_MEDIA_HOLD_ONLY || !shouldBtComm)

        val nextTimerMs = listOf(exitGraceRemaining, scoGraceRemaining, settleRemaining)
            .filter { it > 0L }
            .minOrNull() ?: 0L

        val appLabel = activeApp?.displayName ?: foregroundApp?.displayName ?: "App"
        val reason = when {
            inCall -> "Paused for cellular phone call"
            manualHoldActive && !manualAllowed ->
                "Waiting for Android Auto before holding audio"
            manualAllowed && !aaSettledForSco ->
                "Manual hold armed — finishing Android Auto startup"
            manualAllowed -> "Manual hold active"
            autoHold && !aaSettledForSco ->
                "$appLabel ready — finishing Android Auto startup"
            autoHold && bg != null && externalAudioPlaying ->
                "${bg.displayName} playing in background"
            autoHold && bg != null && inGrace ->
                "Holding briefly for ${bg.displayName}"
            autoHold && fg == null && inGrace ->
                "Holding briefly after leaving ${graceApp?.displayName ?: "app"}"
            autoHold && !screenInteractive && externalAudioPlaying ->
                "$appLabel playing with screen off"
            autoHold && inGrace && !screenInteractive ->
                "Holding briefly after $appLabel finished"
            autoHold && shouldBtComm ->
                "$appLabel voice audio routed to car"
            autoHold ->
                "$appLabel open (waiting for voice playback)"
            fg != null && fg.usesNativeAaMedia && aaConnected ->
                "${fg.displayName} playing natively via Android Auto"
            foregroundApp != null && !config.isAppEnabled(foregroundApp!!) ->
                "${foregroundApp!!.displayName} open (auto-detect off for this app)"
            foregroundApp != null && suppressedForCurrentVisit ->
                "Released manually for current $appLabel session"
            foregroundApp != null && suppressedUntilPlayback ->
                "$appLabel open (yielded to Android Auto media)"
            foregroundApp != null && config.onlyWhenAndroidAutoConnected && !aaConnected ->
                "$appLabel open (waiting for Android Auto)"
            aaConnected -> "Armed — Android Auto connected"
            else -> "Idle"
        }

        return PolicySnapshot(
            shouldHoldBridge = shouldHold,
            shouldEngageBtCommRoute = shouldBtComm,
            shouldPlaySilentMediaKeepalive = shouldSilentMedia,
            activeApp = activeApp,
            foregroundApp = foregroundApp,
            backgroundLatchedApp = backgroundLatchedApp,
            manualHold = manualHoldActive,
            inExitGrace = inGrace,
            graceRemainingMs = nextTimerMs,
            yieldingToPhoneCall = inCall,
            reason = reason
        )
    }
}

