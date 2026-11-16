package com.lemoyneiv.marcopolobridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.lemoyneiv.marcopolobridge.R
import com.lemoyneiv.marcopolobridge.audio.AudioBridgeController
import com.lemoyneiv.marcopolobridge.core.RoutingMode
import com.lemoyneiv.marcopolobridge.ui.MainActivity

/**
 * Exposes a distraction-safe Android Auto media source (`MediaBrowserServiceCompat` +
 * `MediaSessionCompat`) and manages the `mediaPlayback` foreground notification while
 * the bridge is actively holding car audio for Marco Polo.
 */
class BridgeMediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var controller: AudioBridgeController
    private var inForeground = false

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            controller.setManualHold(true)
        }

        override fun onPause() {
            controller.setManualHold(false)
        }

        override fun onStop() {
            controller.setManualHold(false)
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            val q = query.orEmpty()
            if (q.contains("release", ignoreCase = true) ||
                q.contains("stop", ignoreCase = true) ||
                q.contains("resume", ignoreCase = true)
            ) {
                controller.setManualHold(false)
            } else {
                controller.setManualHold(true)
            }
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            when (mediaId) {
                MEDIA_ID_HOLD -> controller.setManualHold(true)
                MEDIA_ID_RELEASE -> controller.setManualHold(false)
                MEDIA_ID_MODE_SMART -> {
                    controller.preferences.setRoutingMode(RoutingMode.SMART_AUTO)
                    notifyChildrenChanged(ROOT_ID)
                }
                MEDIA_ID_MODE_AA_ONLY -> {
                    controller.preferences.setRoutingMode(RoutingMode.AA_MEDIA_HOLD_ONLY)
                    notifyChildrenChanged(ROOT_ID)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        controller = AudioBridgeController.getInstance(this)
        ensureNotificationChannel()

        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(this, "MarcoPoloBridgeSession").apply {
            setSessionActivity(launchIntent)
            setCallback(sessionCallback)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        val st = controller.status.value
        updateMediaSession(
            st.snapshot.shouldHoldBridge,
            st.snapshot.activeApp?.displayName,
            st.snapshot.reason
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            ACTION_RELEASE_HOLD -> {
                controller.setManualHold(false)
            }
            ACTION_ACTIVATE_HOLD -> {
                controller.setManualHold(true)
            }
            ACTION_SYNC_STATE -> {
                val active = intent.getBooleanExtra(EXTRA_ACTIVE, false)
                val appName = intent.getStringExtra(EXTRA_APP_NAME)
                val reason = intent.getStringExtra(EXTRA_REASON) ?: "Car Audio Bridge"
                applyForegroundAndSession(active, appName, reason)
            }
        }
        return START_STICKY
    }

    private fun applyForegroundAndSession(active: Boolean, appName: String?, reason: String) {
        updateMediaSession(active, appName, reason)
        notifyChildrenChanged(ROOT_ID)
        if (active) {
            val notification = buildNotification(appName, reason)
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                inForeground = true
            }.onFailure { e ->
                Log.w(TAG, "Unable to enter foreground mediaPlayback state", e)
            }
        } else {
            if (inForeground) {
                inForeground = false
                runCatching {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
            }
            stopSelf()
        }
    }

    private fun updateMediaSession(active: Boolean, appName: String?, subtitle: String) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
            )
            .setState(
                if (active) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                if (active) 1.0f else 0.0f
            )
            .build()
        mediaSession.setPlaybackState(playbackState)

        val btName = controller.status.value.activeBtDeviceName ?: "Car Bluetooth"
        val activeTitle = if (!appName.isNullOrBlank()) {
            "$appName Audio Active"
        } else {
            "Car Audio Hold Active"
        }
        val metadata = MediaMetadataCompat.Builder()
            .putString(
                MediaMetadataCompat.METADATA_KEY_TITLE,
                if (active) activeTitle else "Car Audio Bridge (Idle)"
            )
            .putString(
                MediaMetadataCompat.METADATA_KEY_ARTIST,
                if (active) "Routing to $btName • AA Music Paused" else subtitle
            )
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Marco Polo & Speechify Bridge")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
            .build()
        mediaSession.setMetadata(metadata)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        if (clientPackageName == "com.google.android.projection.gearhead" ||
            clientPackageName.contains("car", ignoreCase = true)
        ) {
            controller.projectionMonitor.setMediaBrowserCarBound(true)
        }
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        val st = controller.status.value
        val active = st.snapshot.shouldHoldBridge
        val appLabel = st.snapshot.activeApp?.displayName ?: "Marco Polo / Speechify"
        val mode = st.config.routingMode

        val items = mutableListOf(
            buildPlayableItem(
                id = MEDIA_ID_HOLD,
                title = if (active) "Audio Hold Active ($appLabel)" else "Hold Car Audio Now",
                subtitle = "Pauses Android Auto music & routes Marco Polo / Speechify to car speakers"
            ),
            buildPlayableItem(
                id = MEDIA_ID_RELEASE,
                title = "Release Audio & Resume Music",
                subtitle = "Hands audio focus back to your previous Android Auto app"
            ),
            buildPlayableItem(
                id = MEDIA_ID_MODE_SMART,
                title = if (mode == RoutingMode.SMART_AUTO) {
                    "✓ Mode: Car Bluetooth + Pause AA"
                } else {
                    "Switch to: Car Bluetooth + Pause AA"
                },
                subtitle = "Recommended for Pixel + Wireless Android Auto"
            ),
            buildPlayableItem(
                id = MEDIA_ID_MODE_AA_ONLY,
                title = if (mode == RoutingMode.AA_MEDIA_HOLD_ONLY) {
                    "✓ Mode: Pause AA Media Only"
                } else {
                    "Switch to: Pause AA Media Only"
                },
                subtitle = "Holds AA media focus without forcing Bluetooth HFP"
            )
        )
        result.sendResult(items)
    }

    private fun buildPlayableItem(
        id: String,
        title: String,
        subtitle: String
    ): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    private fun buildNotification(appName: String?, reason: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val releaseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BridgeMediaBrowserService::class.java).apply {
                action = ACTION_RELEASE_HOLD
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val btLabel = controller.status.value.activeBtDeviceName ?: "Car Speakers"
        val title = if (!appName.isNullOrBlank()) {
            "$appName Car Audio Active"
        } else {
            "Car Audio Bridge Active"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bridge_status)
            .setContentTitle(title)
            .setContentText("$btLabel • $reason")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_media_play,
                "Release / Resume AA",
                releaseIntent
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Car Audio Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when car audio is being held for Marco Polo or Speechify"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        controller.projectionMonitor.setMediaBrowserCarBound(false)
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BridgeMediaService"
        private const val CHANNEL_ID = "marcopolo_bridge_active"
        private const val NOTIFICATION_ID = 4105

        private const val ROOT_ID = "root"
        private const val MEDIA_ID_HOLD = "action_hold"
        private const val MEDIA_ID_RELEASE = "action_release"
        private const val MEDIA_ID_MODE_SMART = "mode_smart"
        private const val MEDIA_ID_MODE_AA_ONLY = "mode_aa_only"

        const val ACTION_SYNC_STATE = "com.lemoyneiv.marcopolobridge.SYNC_STATE"
        const val ACTION_RELEASE_HOLD = "com.lemoyneiv.marcopolobridge.RELEASE_HOLD"
        const val ACTION_ACTIVATE_HOLD = "com.lemoyneiv.marcopolobridge.ACTIVATE_HOLD"
        private const val EXTRA_ACTIVE = "extra_active"
        private const val EXTRA_APP_NAME = "extra_app_name"
        private const val EXTRA_REASON = "extra_reason"

        private var lastSyncedActive: Boolean = false
        private var lastSyncedAppName: String? = null
        private var lastSyncedReason: String = ""

        @Synchronized
        fun syncWithBridgeState(
            context: Context,
            active: Boolean,
            appName: String?,
            reason: String
        ) {
            if (!active && !lastSyncedActive) return
            if (active && lastSyncedActive && appName == lastSyncedAppName && reason == lastSyncedReason) {
                return
            }
            lastSyncedActive = active
            lastSyncedAppName = appName
            lastSyncedReason = reason

            val intent = Intent(context, BridgeMediaBrowserService::class.java).apply {
                action = ACTION_SYNC_STATE
                putExtra(EXTRA_ACTIVE, active)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_REASON, reason)
            }
            runCatching {
                if (active) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { e ->
                Log.w(TAG, "Service sync skipped: ${e.message}")
            }
        }
    }
}

