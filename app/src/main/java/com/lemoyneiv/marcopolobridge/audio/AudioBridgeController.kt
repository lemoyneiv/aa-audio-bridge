package com.lemoyneiv.marcopolobridge.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.lemoyneiv.marcopolobridge.core.BridgeConfig
import com.lemoyneiv.marcopolobridge.core.BridgePolicy
import com.lemoyneiv.marcopolobridge.core.BridgePreferences
import com.lemoyneiv.marcopolobridge.core.CommDeviceCandidate
import com.lemoyneiv.marcopolobridge.core.PolicySnapshot
import com.lemoyneiv.marcopolobridge.core.WatchedApp
import com.lemoyneiv.marcopolobridge.service.BridgeMediaBrowserService
import com.lemoyneiv.marcopolobridge.service.BridgeTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BridgeRuntimeStatus(
    val snapshot: PolicySnapshot,
    val config: BridgeConfig,
    val androidAutoConnected: Boolean,
    val accessibilityEnabled: Boolean,
    val activeBtDeviceName: String?,
    val availableBtDevices: List<CommDeviceCandidate>,
    val currentCommDeviceLabel: String?
)

class AudioBridgeController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val preferences = BridgePreferences(appContext)
    private val policy = BridgePolicy()
    private val silentPlayer = SilentTrackPlayer()

    val projectionMonitor = CarProjectionMonitor(appContext) { connected ->
        policy.onAndroidAutoConnectionChanged(connected, now())
        reconcile()
    }

    private var activeFocusRequest: AudioFocusRequest? = null
    private var focusYieldedToApp: WatchedApp? = null
    private var commRouteEngaged: Boolean = false
    private var accessibilityServiceBound: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val graceReconcileRunnable = Runnable { reconcile() }
    private val followUpRouteRunnable = Runnable { verifyAndReassertBtRoute() }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            abandonMediaFocus()
            val snap = policy.evaluate(now())
            when {
                snap.manualHold -> {
                    // User explicitly engaged Manual Hold -> keep holding media focus
                    focusYieldedToApp = null
                    mainHandler.postDelayed({
                        if (policy.evaluate(now()).manualHold) {
                            requestMediaFocus(preferences.config.value)
                        }
                    }, 300L)
                }
                snap.foregroundApp != null -> {
                    // Speechify or Marco Polo is on screen and claimed AUDIOFOCUS_GAIN itself ->
                    // remember this so subsequent reconcile() calls do NOT steal focus back from it,
                    // while keeping our Car Bluetooth communication route active.
                    focusYieldedToApp = snap.foregroundApp
                    Log.i(TAG, "${snap.foregroundApp.displayName} claimed media focus in foreground")
                }
                else -> {
                    // Bridge was only holding for background continuation and user started another
                    // media app (e.g. tapped Play on Spotify on Android Auto) -> yield cleanly!
                    focusYieldedToApp = null
                    policy.onMediaAudioFocusLost(now())
                    reconcile()
                }
            }
        }
    }

    private val commDeviceChangedListener =
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            mainHandler.post {
                val target = resolveTargetBtDevice()
                if (policy.shouldReassertBtRoute(device?.id, target?.first, now())) {
                    Log.i(TAG, "Speakerphone override detected (${device?.type}); re-asserting ${target?.first?.name}")
                    applyBtCommunicationRoute(target?.second)
                    mainHandler.removeCallbacks(followUpRouteRunnable)
                    mainHandler.postDelayed(followUpRouteRunnable, 200L)
                }
                publishStatus()
            }
        }

    private val modeChangedListener = AudioManager.OnModeChangedListener { mode ->
        mainHandler.post {
            policy.onAudioModeChanged(mode, now())
            reconcile()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            val externalActive = hasExternalRelevantPlayback(configs)
            val prevSnap = policy.evaluate(now())
            val nextSnap = policy.onExternalAudioActiveChanged(externalActive, now())
            reconcile()

            // When Marco Polo starts playing a new clip, refresh our MODE_IN_COMMUNICATION
            // timestamp so our SetModeDeathHandler remains newer than Marco Polo's.
            if (externalActive && nextSnap.shouldEngageBtCommRoute) {
                val target = resolveTargetBtDevice()
                if (target != null) {
                    applyBtCommunicationRoute(target.second)
                    mainHandler.removeCallbacks(followUpRouteRunnable)
                    mainHandler.postDelayed(followUpRouteRunnable, 200L)
                }
            } else if (prevSnap.shouldHoldBridge != nextSnap.shouldHoldBridge) {
                publishStatus()
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            reconcile()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            reconcile()
        }
    }

    private val _status = MutableStateFlow(buildRuntimeStatus())
    val status: StateFlow<BridgeRuntimeStatus> = _status.asStateFlow()

    init {
        policy.updateConfig(preferences.config.value, now())
        policy.onScreenInteractiveChanged(powerManager.isInteractive, now())
        policy.onAudioModeChanged(audioManager.mode, now())

        runCatching {
            audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, commDeviceChangedListener)
            audioManager.addOnModeChangedListener(mainExecutor, modeChangedListener)
            audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        }.onFailure { e ->
            Log.w(TAG, "Audio listener registration issue", e)
        }

        projectionMonitor.start()

        scope.launch {
            preferences.config.collect { cfg ->
                policy.updateConfig(cfg, now())
                reconcile()
            }
        }
    }

    fun onAccessibilityBoundChanged(bound: Boolean) {
        mainHandler.post {
            accessibilityServiceBound = bound
            if (!bound) {
                policy.onForegroundPackageChanged("android.launcher", now())
            }
            reconcile()
        }
    }

    fun onForegroundWindowChanged(packageName: String?) {
        mainHandler.post {
            policy.onScreenInteractiveChanged(powerManager.isInteractive, now())
            policy.onForegroundPackageChanged(packageName, now())
            reconcile()
        }
    }

    fun onScreenInteractiveChanged(interactive: Boolean) {
        mainHandler.post {
            policy.onScreenInteractiveChanged(interactive, now())
            reconcile()
        }
    }

    fun setManualHold(enabled: Boolean) {
        mainHandler.post {
            policy.setManualHold(enabled, now())
            reconcile()
        }
    }

    fun toggleManualHold() {
        mainHandler.post {
            policy.toggleManualHold(now())
            reconcile()
        }
    }

    fun refreshState() {
        mainHandler.post {
            policy.onScreenInteractiveChanged(powerManager.isInteractive, now())
            policy.onAudioModeChanged(audioManager.mode, now())
            reconcile()
        }
    }

    private fun reconcile() {
        val t = now()
        val cfg = preferences.config.value
        val snap = policy.evaluate(t)

        mainHandler.removeCallbacks(graceReconcileRunnable)
        if (snap.graceRemainingMs > 0L) {
            mainHandler.postDelayed(graceReconcileRunnable, snap.graceRemainingMs + 60L)
        }

        if (snap.shouldHoldBridge) {
            if (snap.manualHold || (focusYieldedToApp != null && snap.activeApp != focusYieldedToApp)) {
                focusYieldedToApp = null
            }
            acquireWakeLockIfNeeded()
            if (focusYieldedToApp == null) {
                requestMediaFocusIfNeeded(cfg)
            }
        } else {
            focusYieldedToApp = null
            mainHandler.removeCallbacks(followUpRouteRunnable)
            releaseBtCommunicationRoute()
            silentPlayer.stopAll()
            abandonMediaFocus()
            releaseWakeLock()
        }

        if (snap.shouldHoldBridge) {
            silentPlayer.setMediaKeepaliveActive(snap.shouldPlaySilentMediaKeepalive)

            if (snap.shouldEngageBtCommRoute) {
                val target = resolveTargetBtDevice()
                silentPlayer.setVoicePrimerActive(true)
                applyBtCommunicationRoute(target?.second)
                commRouteEngaged = true
            } else {
                releaseBtCommunicationRoute()
            }
        }

        BridgeMediaBrowserService.syncWithBridgeState(
            appContext,
            snap.shouldHoldBridge,
            snap.activeApp?.displayName,
            snap.reason
        )
        BridgeTileService.requestTileUpdate(appContext)
        publishStatus()
    }

    private fun verifyAndReassertBtRoute() {
        val snap = policy.evaluate(now())
        if (!snap.shouldEngageBtCommRoute) return
        val target = resolveTargetBtDevice() ?: return
        val current = runCatching { audioManager.communicationDevice }.getOrNull()
        if (current?.id != target.first.id) {
            if (policy.shouldReassertBtRoute(current?.id, target.first, now())) {
                applyBtCommunicationRoute(target.second)
            }
        }
        publishStatus()
    }

    private fun applyBtCommunicationRoute(targetDevice: AudioDeviceInfo?) {
        runCatching {
            val currentMode = audioManager.mode
            if (currentMode != AudioManager.MODE_IN_CALL && currentMode != AudioManager.MODE_RINGTONE) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            if (targetDevice != null) {
                val currentComm = audioManager.communicationDevice
                if (currentComm?.id != targetDevice.id) {
                    val ok = audioManager.setCommunicationDevice(targetDevice)
                    Log.i(TAG, "setCommunicationDevice(${safeDeviceLabel(targetDevice)}) -> $ok")
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed applying BT communication route", e)
        }
    }

    private fun releaseBtCommunicationRoute() {
        silentPlayer.setVoicePrimerActive(false)
        if (!commRouteEngaged) return
        commRouteEngaged = false
        runCatching {
            audioManager.clearCommunicationDevice()
            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed clearing communication device", e)
        }
    }

    private fun requestMediaFocusIfNeeded(cfg: BridgeConfig) {
        if (activeFocusRequest != null) return
        requestMediaFocus(cfg)
    }

    private fun requestMediaFocus(cfg: BridgeConfig) {
        runCatching {
            val gainType = if (cfg.resumeMediaOnExit) {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            } else {
                AudioManager.AUDIOFOCUS_GAIN
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(gainType)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                .build()
            audioManager.requestAudioFocus(req)
            activeFocusRequest = req
        }.onFailure { e ->
            Log.w(TAG, "Failed requesting media audio focus", e)
        }
    }

    private fun abandonMediaFocus() {
        val req = activeFocusRequest ?: return
        activeFocusRequest = null
        runCatching {
            audioManager.abandonAudioFocusRequest(req)
        }
    }

    private fun hasExternalRelevantPlayback(configs: List<AudioPlaybackConfiguration>): Boolean {
        var speechOrCommCount = 0
        var mediaCount = 0
        for (cfg in configs) {
            val attrs = cfg.audioAttributes
            if (SilentTrackPlayer.isOwnSilentTrack(attrs)) continue
            when (attrs.usage) {
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
                AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
                AudioAttributes.USAGE_ASSISTANT -> speechOrCommCount++
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_UNKNOWN -> mediaCount++
            }
        }
        if (speechOrCommCount > 0) return true
        val snap = policy.evaluate(now())
        val watchingMediaForTarget = snap.targetAppForeground || snap.backgroundLatchedApp != null
        return watchingMediaForTarget && mediaCount > 0
    }

    private fun resolveTargetBtDevice(): Pair<CommDeviceCandidate, AudioDeviceInfo>? {
        val rawDevices = runCatching { audioManager.availableCommunicationDevices }
            .getOrElse { emptyList() }
        val byId = rawDevices.associateBy { it.id }
        val candidates = rawDevices.map { toCandidate(it) }
        val chosen = policy.selectTargetCommDevice(candidates) ?: return null
        val info = byId[chosen.id] ?: return null
        return chosen to info
    }

    fun listBluetoothCommCandidates(): List<CommDeviceCandidate> {
        val rawDevices = runCatching { audioManager.availableCommunicationDevices }
            .getOrElse { emptyList() }
        return rawDevices.map { toCandidate(it) }.filter { it.isBluetoothComm }
    }

    private fun toCandidate(info: AudioDeviceInfo): CommDeviceCandidate {
        val name = safeDeviceLabel(info)
        val addr = if (hasBtConnectPermission()) {
            runCatching { info.address ?: "" }.getOrDefault("")
        } else {
            ""
        }
        return CommDeviceCandidate(
            id = info.id,
            type = info.type,
            name = name,
            address = addr
        )
    }

    private fun safeDeviceLabel(info: AudioDeviceInfo): String {
        val raw = runCatching { info.productName?.toString()?.trim().orEmpty() }.getOrDefault("")
        if (raw.isNotEmpty()) return raw
        return when (info.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Car Bluetooth (HFP)"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE Audio"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone Earpiece"
            else -> "Audio Device #${info.id}"
        }
    }

    fun hasBtConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    private fun acquireWakeLockIfNeeded() {
        val existing = wakeLock
        if (existing != null && existing.isHeld) return
        runCatching {
            val wl = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MarcoPoloBridge:AudioSession"
            ).apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L) // Up to 2 hours for long Speechify audiobooks/articles
            }
            wakeLock = wl
        }
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        wakeLock = null
        runCatching {
            if (wl.isHeld) wl.release()
        }
    }

    private fun publishStatus() {
        _status.value = buildRuntimeStatus()
    }

    private fun buildRuntimeStatus(): BridgeRuntimeStatus {
        val snap = policy.evaluate(now())
        val btDevices = listBluetoothCommCandidates()
        val chosen = policy.selectTargetCommDevice(btDevices)
        val currentComm = runCatching { audioManager.communicationDevice }.getOrNull()
        return BridgeRuntimeStatus(
            snapshot = snap,
            config = preferences.config.value,
            androidAutoConnected = projectionMonitor.isConnected.value,
            accessibilityEnabled = accessibilityServiceBound,
            activeBtDeviceName = chosen?.name,
            availableBtDevices = btDevices,
            currentCommDeviceLabel = currentComm?.let { safeDeviceLabel(it) }
        )
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    companion object {
        private const val TAG = "AudioBridgeController"

        @Volatile
        private var instance: AudioBridgeController? = null

        fun getInstance(context: Context): AudioBridgeController {
            return instance ?: synchronized(this) {
                instance ?: AudioBridgeController(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
