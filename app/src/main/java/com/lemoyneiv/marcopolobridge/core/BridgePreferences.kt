package com.lemoyneiv.marcopolobridge.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BridgePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<BridgeConfig> = _config.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _config.value = readConfig()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun readConfig(): BridgeConfig {
        val modeName = prefs.getString(KEY_ROUTING_MODE, RoutingMode.SMART_AUTO.name)
        val routingMode = runCatching { RoutingMode.valueOf(modeName ?: "") }
            .getOrDefault(RoutingMode.SMART_AUTO)

        val timingName = prefs.getString(KEY_SCO_TIMING, ScoTiming.ON_FOREGROUND.name)
        val scoTiming = runCatching { ScoTiming.valueOf(timingName ?: "") }
            .getOrDefault(ScoTiming.ON_FOREGROUND)

        return BridgeConfig(
            autoDetectEnabled = prefs.getBoolean(KEY_AUTO_DETECT, true),
            enableMarcoPolo = prefs.getBoolean(KEY_ENABLE_MARCO_POLO, true),
            enableSpeechify = prefs.getBoolean(KEY_ENABLE_SPEECHIFY, true),
            keepPlayingInBackground = prefs.getBoolean(KEY_KEEP_BACKGROUND, true),
            onlyWhenAndroidAutoConnected = prefs.getBoolean(KEY_ONLY_WHEN_AA, true),
            routingMode = routingMode,
            scoTiming = scoTiming,
            resumeMediaOnExit = prefs.getBoolean(KEY_RESUME_ON_EXIT, true),
            preferredBtIdentifier = prefs.getString(KEY_PREFERRED_BT, "") ?: "",
            exitGraceMs = prefs.getLong(KEY_EXIT_GRACE_MS, 2_000L)
        )
    }

    fun setAutoDetectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DETECT, enabled).apply()
    }

    fun setEnableMarcoPolo(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLE_MARCO_POLO, enabled).apply()
    }

    fun setEnableSpeechify(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLE_SPEECHIFY, enabled).apply()
    }

    fun setKeepPlayingInBackground(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_BACKGROUND, enabled).apply()
    }

    fun setOnlyWhenAndroidAutoConnected(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONLY_WHEN_AA, enabled).apply()
    }

    fun setRoutingMode(mode: RoutingMode) {
        prefs.edit().putString(KEY_ROUTING_MODE, mode.name).apply()
    }

    fun setScoTiming(timing: ScoTiming) {
        prefs.edit().putString(KEY_SCO_TIMING, timing.name).apply()
    }

    fun setResumeMediaOnExit(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESUME_ON_EXIT, enabled).apply()
    }

    fun setPreferredBtIdentifier(identifier: String) {
        prefs.edit().putString(KEY_PREFERRED_BT, identifier.trim()).apply()
    }

    companion object {
        private const val PREFS_NAME = "marcopolo_bridge_prefs"
        private const val KEY_AUTO_DETECT = "auto_detect_enabled"
        private const val KEY_ENABLE_MARCO_POLO = "enable_marco_polo"
        private const val KEY_ENABLE_SPEECHIFY = "enable_speechify"
        private const val KEY_KEEP_BACKGROUND = "keep_playing_in_background"
        private const val KEY_ONLY_WHEN_AA = "only_when_aa_connected"
        private const val KEY_ROUTING_MODE = "routing_mode"
        private const val KEY_SCO_TIMING = "sco_timing"
        private const val KEY_RESUME_ON_EXIT = "resume_media_on_exit"
        private const val KEY_PREFERRED_BT = "preferred_bt_identifier"
        private const val KEY_EXIT_GRACE_MS = "exit_grace_ms"
    }
}

