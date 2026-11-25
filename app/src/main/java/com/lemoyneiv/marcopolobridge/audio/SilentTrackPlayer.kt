package com.lemoyneiv.marcopolobridge.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Generates zero-CPU, zero-amplitude PCM16 silent tracks using [AudioTrack.MODE_STATIC] looping:
 *
 * 1. Voice Communication Primer (`USAGE_VOICE_COMMUNICATION`, 16 kHz mono):
 *    Keeps our app's `SetModeDeathHandler` marked `mPlaybackActive = true` in AOSP `AudioService`
 *    across periodic `MSG_CHECK_MODE_FOR_UID` sweeps so `setCommunicationDevice(carBtDevice)`
 *    remains authoritative while Marco Polo is active.
 *
 * 2. Media Keepalive (`USAGE_MEDIA`, 48 kHz stereo):
 *    Holds Android Auto's media sink open when Bluetooth SCO is not active so AA media apps
 *    (Spotify, YouTube Music, Audible) do not hijack focus between video messages.
 */
class SilentTrackPlayer {
    private var voicePrimerTrack: AudioTrack? = null
    private var mediaKeepaliveTrack: AudioTrack? = null

    @Volatile
    var isVoicePrimerPlaying: Boolean = false
        private set

    @Volatile
    var isMediaKeepalivePlaying: Boolean = false
        private set

    @Synchronized
    fun setVoicePrimerActive(active: Boolean) {
        if (active) startVoicePrimer() else stopVoicePrimer()
    }

    @Synchronized
    fun setMediaKeepaliveActive(active: Boolean) {
        if (active) startMediaKeepalive() else stopMediaKeepalive()
    }

    @Synchronized
    private fun startVoicePrimer() {
        if (isVoicePrimerPlaying && voicePrimerTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            return
        }
        stopVoicePrimer()
        runCatching {
            val sampleRate = 16_000
            val frameCount = sampleRate // 1 second of 16-bit mono silence (32 KB)
            val bufferBytes = frameCount * 2

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            val silence = ByteArray(bufferBytes)
            track.write(silence, 0, silence.size)
            track.setLoopPoints(0, frameCount, -1)
            track.play()

            voicePrimerTrack = track
            isVoicePrimerPlaying = true
        }.onFailure { e ->
            Log.w(TAG, "Failed to start voice primer track", e)
            isVoicePrimerPlaying = false
        }
    }

    @Synchronized
    private fun stopVoicePrimer() {
        isVoicePrimerPlaying = false
        val track = voicePrimerTrack ?: return
        voicePrimerTrack = null
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
        }
        runCatching { track.release() }
    }

    @Synchronized
    private fun startMediaKeepalive() {
        if (isMediaKeepalivePlaying && mediaKeepaliveTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            return
        }
        stopMediaKeepalive()
        runCatching {
            val sampleRate = 48_000
            val frameCount = sampleRate / 2 // 0.5 seconds of 16-bit stereo silence (96 KB)
            val bufferBytes = frameCount * 4

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            val silence = ByteArray(bufferBytes)
            track.write(silence, 0, silence.size)
            track.setLoopPoints(0, frameCount, -1)
            track.play()

            mediaKeepaliveTrack = track
            isMediaKeepalivePlaying = true
        }.onFailure { e ->
            Log.w(TAG, "Failed to start media keepalive track", e)
            isMediaKeepalivePlaying = false
        }
    }

    @Synchronized
    private fun stopMediaKeepalive() {
        isMediaKeepalivePlaying = false
        val track = mediaKeepaliveTrack ?: return
        mediaKeepaliveTrack = null
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
        }
        runCatching { track.release() }
    }

    @Synchronized
    fun stopAll() {
        stopVoicePrimer()
        stopMediaKeepalive()
    }

    companion object {
        private const val TAG = "SilentTrackPlayer"

        /**
         * Note: AOSP `PlaybackActivityMonitor.anonymizeForPublicConsumption()` masks `mFlags`
         * to `AudioAttributes.FLAG_PUBLIC` for non-system apps, stripping `ALLOW_CAPTURE_BY_NONE`
         * while preserving `usage` and `contentType`. Matching `CONTENT_TYPE_SONIFICATION`
         * prevents our own `voicePrimerTrack` / `mediaKeepaliveTrack` (and UI click sounds)
         * from ever creating a self-sustaining playback feedback loop.
         */
        fun isOwnSilentTrack(attrs: AudioAttributes): Boolean {
            return attrs.contentType == AudioAttributes.CONTENT_TYPE_SONIFICATION &&
                (attrs.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION ||
                    attrs.usage == AudioAttributes.USAGE_MEDIA)
        }
    }
}

