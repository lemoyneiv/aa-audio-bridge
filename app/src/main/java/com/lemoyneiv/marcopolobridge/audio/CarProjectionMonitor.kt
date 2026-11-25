package com.lemoyneiv.marcopolobridge.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors live Android Auto (Wired or Wireless) projection status using Jetpack's
 * authoritative [CarConnection] ContentProvider (`content://androidx.car.app.connection`),
 * which Gearhead updates immediately on session start and teardown.
 */
class CarProjectionMonitor(
    context: Context,
    private val onConnectionChanged: (Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var carConnection: CarConnection? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val observer = Observer<Int> { connectionState ->
        val connected =
            (connectionState == CarConnection.CONNECTION_TYPE_PROJECTION ||
                connectionState == CarConnection.CONNECTION_TYPE_NATIVE)
        if (_isConnected.value != connected) {
            _isConnected.value = connected
            onConnectionChanged(connected)
        }
    }

    fun start() {
        mainHandler.post {
            if (carConnection != null) return@post
            runCatching {
                val conn = CarConnection(appContext)
                carConnection = conn
                conn.type.observeForever(observer)
            }.onFailure { e ->
                Log.w(TAG, "Unable to observe CarConnection provider", e)
            }
        }
    }

    companion object {
        private const val TAG = "CarProjectionMonitor"
    }
}
