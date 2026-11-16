package com.lemoyneiv.marcopolobridge.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.lemoyneiv.marcopolobridge.audio.AudioBridgeController

/**
 * Privacy-minimal foreground app detector (`canRetrieveWindowContent="false"`).
 * Observes `TYPE_WINDOW_STATE_CHANGED` solely to detect when Marco Polo
 * (`co.happybits.marcopolo`) enters or leaves the foreground while driving.
 */
class MarcoPoloAccessibilityService : AccessibilityService() {

    private lateinit var controller: AudioBridgeController
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> controller.onScreenInteractiveChanged(true)
                Intent.ACTION_SCREEN_OFF -> controller.onScreenInteractiveChanged(false)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        controller = AudioBridgeController.getInstance(this)
        controller.onAccessibilityBoundChanged(true)

        if (!screenReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiverRegistered = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()

        // Ignore background Toasts or PopupWindows from other apps (e.g. Spotify/Maps toast)
        // so they never falsely look like the user switched away from Marco Polo.
        if (cls.contains("Toast", ignoreCase = true) ||
            cls.contains("PopupWindow", ignoreCase = true)
        ) {
            return
        }

        // Ignore our own notification action trampolines if Marco Polo is open underneath
        if (pkg == packageName && cls.isEmpty()) return

        controller.onForegroundWindowChanged(pkg)
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (screenReceiverRegistered) {
            screenReceiverRegistered = false
            runCatching { unregisterReceiver(screenReceiver) }
        }
        if (::controller.isInitialized) {
            controller.onAccessibilityBoundChanged(false)
        }
    }

    companion object {
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, MarcoPoloAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.split(':')
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any { it == expected }
        }
    }
}
