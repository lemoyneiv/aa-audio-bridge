package com.lemoyneiv.marcopolobridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePolicyTest {

    @Test
    fun `stays idle when Marco Polo or Speechify opens at home without Android Auto`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(false, 1_000L)

        val mpSnap = policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 1_500L)
        assertFalse(mpSnap.shouldHoldBridge)
        assertFalse(mpSnap.shouldEngageBtCommRoute)
        assertEquals(WatchedApp.MARCO_POLO, mpSnap.foregroundApp)

        val spSnap = policy.onForegroundPackageChanged(BridgePolicy.PKG_SPEECHIFY, 2_000L)
        assertFalse(spSnap.shouldHoldBridge)
        assertEquals(WatchedApp.SPEECHIFY, spSnap.foregroundApp)
    }

    @Test
    fun `Speechify stays passive for native Android Auto media and bridges only non-AA speech streams`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)

        // Opening Speechify while it uses native Android Auto USAGE_MEDIA must NOT steal focus
        // or open Bluetooth HFP SCO (which would pause Speechify and show "unavailable during a call").
        val spOpen = policy.onForegroundPackageChanged(BridgePolicy.PKG_SPEECHIFY, 5_000L)
        assertFalse(spOpen.shouldHoldBridge)
        assertFalse(spOpen.shouldEngageBtCommRoute)
        assertEquals(WatchedApp.SPEECHIFY, spOpen.foregroundApp)
        assertEquals("Speechify playing natively via Android Auto", spOpen.reason)

        // If a non-AA speech/accessibility stream starts (USAGE_ASSISTANCE_ACCESSIBILITY), bridge engages
        val spSpeech = policy.onExternalAudioActiveChanged(true, 6_000L)
        assertTrue(spSpeech.shouldHoldBridge)
        assertTrue(spSpeech.shouldEngageBtCommRoute)
        assertEquals(WatchedApp.SPEECHIFY, spSpeech.activeApp)

        // When non-AA speech ends, holds briefly across paragraph gap then releases completely
        val gap = policy.onExternalAudioActiveChanged(false, 10_000L)
        assertTrue(gap.shouldHoldBridge)
        assertTrue(gap.shouldEngageBtCommRoute)
        assertTrue(gap.graceRemainingMs > 0L)

        val released = policy.evaluate(12_600L)
        assertFalse(released.shouldHoldBridge)
        assertFalse(released.shouldEngageBtCommRoute)
    }

    @Test
    fun `Marco Polo pauses AA music on open without fake call and engages BT HFP only while voice clip plays`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)

        // After 2.5s AA startup settle, opening Marco Polo holds AA media focus WITHOUT opening BT HFP SCO
        val mpOpen = policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 4_000L)
        assertTrue(mpOpen.shouldHoldBridge)
        assertFalse(mpOpen.shouldEngageBtCommRoute)
        assertTrue(mpOpen.shouldPlaySilentMediaKeepalive)
        assertEquals(WatchedApp.MARCO_POLO, mpOpen.activeApp)

        // Video message starts playing (USAGE_VOICE_COMMUNICATION) -> engages BT HFP SCO immediately
        val playing = policy.onExternalAudioActiveChanged(true, 5_000L)
        assertTrue(playing.shouldHoldBridge)
        assertTrue(playing.shouldEngageBtCommRoute)
        assertFalse(playing.shouldPlaySilentMediaKeepalive)

        // Video message finishes while Marco Polo stays on screen -> holds SCO for 2.5s gap grace,
        // then releases BT HFP SCO so the car exits call mode even with Marco Polo still open!
        val clipEnded = policy.onExternalAudioActiveChanged(false, 15_000L)
        assertTrue(clipEnded.shouldHoldBridge)
        assertTrue(clipEnded.shouldEngageBtCommRoute)
        assertEquals(2_500L, clipEnded.graceRemainingMs)

        val afterScoGrace = policy.evaluate(17_600L)
        assertTrue(afterScoGrace.shouldHoldBridge)
        assertFalse(afterScoGrace.shouldEngageBtCommRoute)
        assertTrue(afterScoGrace.shouldPlaySilentMediaKeepalive)
    }

    @Test
    fun `enforces AA startup settle window and clears manual hold on AA disconnect so car reconnect is never blocked`() {
        val policy = BridgePolicy()

        // Tapping Manual Hold before AA connects arms without engaging HFP SCO (so AA handshake isn't blocked)
        val armedBeforeAa = policy.setManualHold(true, 500L)
        assertFalse(armedBeforeAa.shouldHoldBridge)
        assertFalse(armedBeforeAa.shouldEngageBtCommRoute)
        assertTrue(armedBeforeAa.manualHold)

        // AA connects at t=1000ms -> holds media focus, but waits 2500ms settle before opening HFP SCO
        val justConnected = policy.onAndroidAutoConnectionChanged(true, 1_000L)
        assertTrue(justConnected.shouldHoldBridge)
        assertFalse(justConnected.shouldEngageBtCommRoute)
        assertEquals(2_500L, justConnected.graceRemainingMs)

        // At t=3500ms settle completes -> HFP SCO engages cleanly after FocusGuard startup reclaim
        val settled = policy.evaluate(3_500L)
        assertTrue(settled.shouldHoldBridge)
        assertTrue(settled.shouldEngageBtCommRoute)

        // Drive ends (AA disconnects) -> clears Manual Hold so next drive never starts in a fake call!
        val disconnected = policy.onAndroidAutoConnectionChanged(false, 60_000L)
        assertFalse(disconnected.shouldHoldBridge)
        assertFalse(disconnected.shouldEngageBtCommRoute)
        assertFalse(disconnected.manualHold)
    }

    @Test
    fun `ignores keyboard and systemui overlays while Marco Polo is open`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 4_000L)

        val keyboardSnap = policy.onForegroundPackageChanged(
            "com.google.android.inputmethod.latin",
            4_500L
        )
        assertTrue(keyboardSnap.shouldHoldBridge)
        assertEquals(WatchedApp.MARCO_POLO, keyboardSnap.foregroundApp)

        val shadeSnap = policy.onForegroundPackageChanged("com.android.systemui", 5_000L)
        assertTrue(shadeSnap.shouldHoldBridge)
        assertEquals(WatchedApp.MARCO_POLO, shadeSnap.foregroundApp)
    }

    @Test
    fun `holds through exit grace then releases cleanly when leaving Marco Polo with no audio playing`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 4_000L)

        val leaveSnap = policy.onForegroundPackageChanged(
            "com.google.android.apps.nexuslauncher",
            5_000L
        )
        assertTrue(leaveSnap.shouldHoldBridge)
        assertTrue(leaveSnap.inExitGrace)
        assertEquals(WatchedApp.MARCO_POLO, leaveSnap.activeApp)

        val afterGrace = policy.evaluate(7_100L)
        assertFalse(afterGrace.shouldHoldBridge)
        assertFalse(afterGrace.shouldEngageBtCommRoute)
        assertFalse(afterGrace.inExitGrace)
        assertNull(afterGrace.activeApp)
    }

    @Test
    fun `keeps playing long Polo or non-AA TTS stream if screen locks mid-playback and bridges paragraph gaps`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.onForegroundPackageChanged(BridgePolicy.PKG_SPEECHIFY, 4_000L)
        policy.onExternalAudioActiveChanged(true, 4_500L)

        val screenOffSnap = policy.onScreenInteractiveChanged(false, 10_000L)
        assertTrue(screenOffSnap.shouldHoldBridge)
        assertTrue(screenOffSnap.shouldEngageBtCommRoute)

        // Brief 1.2s synthesis gap between paragraphs while screen is off -> stays seamlessly open!
        val gapStart = policy.onExternalAudioActiveChanged(false, 30_000L)
        assertTrue(gapStart.shouldHoldBridge)
        assertTrue(gapStart.shouldEngageBtCommRoute)
        assertTrue(gapStart.inExitGrace)

        val nextParagraph = policy.onExternalAudioActiveChanged(true, 31_200L)
        assertTrue(nextParagraph.shouldHoldBridge)
        assertTrue(nextParagraph.shouldEngageBtCommRoute)
        assertFalse(nextParagraph.inExitGrace)

        // Still playing 5 minutes later with screen off
        assertTrue(policy.evaluate(330_000L).shouldHoldBridge)

        // Stream ends -> enters 2.5s playback gap grace, then releases completely
        val endSnap = policy.onExternalAudioActiveChanged(false, 345_000L)
        assertTrue(endSnap.shouldHoldBridge)
        assertFalse(policy.evaluate(347_600L).shouldHoldBridge)
        assertFalse(policy.evaluate(347_600L).shouldEngageBtCommRoute)
    }

    @Test
    fun `latches non-AA speech in background when swiping Home and yields if user starts AA media`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 4_000L)
        policy.onExternalAudioActiveChanged(true, 4_500L)

        // User swipes Home while Polo keeps playing in background
        val homeSnap = policy.onForegroundPackageChanged(
            "com.google.android.apps.nexuslauncher",
            5_000L
        )
        assertTrue(homeSnap.shouldHoldBridge)
        assertTrue(homeSnap.shouldEngageBtCommRoute)
        assertNull(homeSnap.foregroundApp)
        assertEquals(WatchedApp.MARCO_POLO, homeSnap.backgroundLatchedApp)
        assertEquals(WatchedApp.MARCO_POLO, homeSnap.activeApp)

        // If user taps Play on Speechify/Spotify on Android Auto screen -> yields background latch!
        val yielded = policy.onMediaAudioFocusLost(10_000L)
        assertFalse(yielded.shouldHoldBridge)
        assertFalse(yielded.shouldEngageBtCommRoute)
        assertNull(yielded.backgroundLatchedApp)
    }

    @Test
    fun `yields idle Marco Polo foreground hold if user starts AA media, then re-engages when Polo plays`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 4_000L)
        assertTrue(policy.evaluate(4_100L).shouldHoldBridge)

        // User taps Play on Spotify/Speechify on Android Auto while Marco Polo sits idle on phone
        val yielded = policy.onMediaAudioFocusLost(5_000L)
        assertFalse(yielded.shouldHoldBridge)
        assertFalse(yielded.shouldEngageBtCommRoute)

        // User then taps a Marco Polo video message -> re-engages automatically!
        val playing = policy.onExternalAudioActiveChanged(true, 8_000L)
        assertTrue(playing.shouldHoldBridge)
        assertTrue(playing.shouldEngageBtCommRoute)
    }

    @Test
    fun `respects per-app enable toggles for Marco Polo and Speechify`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.updateConfig(BridgeConfig(enableSpeechify = false, enableMarcoPolo = true), 1_200L)

        val speechifySnap = policy.onForegroundPackageChanged(BridgePolicy.PKG_SPEECHIFY, 4_000L)
        assertFalse(speechifySnap.shouldHoldBridge)
        assertEquals(WatchedApp.SPEECHIFY, speechifySnap.foregroundApp)

        val marcoSnap = policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 5_000L)
        assertTrue(marcoSnap.shouldHoldBridge)
        assertEquals(WatchedApp.MARCO_POLO, marcoSnap.activeApp)
    }

    @Test
    fun `manual release suppresses auto-hold only for current foreground visit`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 4_000L)
        assertTrue(policy.evaluate(4_100L).shouldHoldBridge)

        // User taps "Release / Resume AA" while Marco Polo is still open
        val released = policy.setManualHold(false, 5_000L)
        assertFalse(released.shouldHoldBridge)

        // Leaves Marco Polo and reopens it later -> auto-hold works again
        policy.onForegroundPackageChanged("com.google.android.apps.nexuslauncher", 6_000L)
        val reopened = policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 9_000L)
        assertTrue(reopened.shouldHoldBridge)
    }

    @Test
    fun `yields immediately to cellular phone call and resumes afterward`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 4_000L)
        policy.onExternalAudioActiveChanged(true, 4_500L)

        val ringing = policy.onAudioModeChanged(BridgePolicy.AUDIO_MODE_RINGTONE, 5_000L)
        assertFalse(ringing.shouldHoldBridge)
        assertFalse(ringing.shouldEngageBtCommRoute)
        assertTrue(ringing.yieldingToPhoneCall)

        val inCall = policy.onAudioModeChanged(BridgePolicy.AUDIO_MODE_IN_CALL, 7_000L)
        assertFalse(inCall.shouldHoldBridge)

        val callEnded = policy.onAudioModeChanged(BridgePolicy.AUDIO_MODE_NORMAL, 20_000L)
        assertTrue(callEnded.shouldHoldBridge)
        assertTrue(callEnded.shouldEngageBtCommRoute)
    }

    @Test
    fun `selects car HFP device over smartwatch and honors preferred identifier`() {
        val policy = BridgePolicy()
        val watch = CommDeviceCandidate(
            id = 10,
            type = CommDeviceCandidate.TYPE_BLUETOOTH_SCO,
            name = "Pixel Watch 2",
            address = "AA:BB:CC:11:22:33"
        )
        val speaker = CommDeviceCandidate(
            id = 2,
            type = CommDeviceCandidate.TYPE_BUILTIN_SPEAKER,
            name = "Phone Speaker"
        )
        val ioniq = CommDeviceCandidate(
            id = 25,
            type = CommDeviceCandidate.TYPE_BLUETOOTH_SCO,
            name = "IONIQ 5",
            address = "11:22:33:44:55:66"
        )

        val chosen = policy.selectTargetCommDevice(listOf(speaker, watch, ioniq))
        assertNotNull(chosen)
        assertEquals(25, chosen?.id)

        // Explicit preference overrides default ranking
        policy.updateConfig(BridgeConfig(preferredBtIdentifier = "Watch"), 1_000L)
        val explicit = policy.selectTargetCommDevice(listOf(speaker, watch, ioniq))
        assertEquals(10, explicit?.id)

        // Returns null if no Bluetooth comm device is available
        assertNull(policy.selectTargetCommDevice(listOf(speaker)))
    }

    @Test
    fun `reasserts BT route against speakerphone override with debounce and rate-limit backoff`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 4_000L)
        policy.onExternalAudioActiveChanged(true, 4_050L)

        val ioniq = CommDeviceCandidate(
            id = 25,
            type = CommDeviceCandidate.TYPE_BLUETOOTH_SCO,
            name = "IONIQ 5"
        )

        // Already routed to Ioniq 5 -> no reassertion needed
        assertFalse(policy.shouldReassertBtRoute(25, ioniq, 4_100L))

        // App switches to built-in speaker (id = 2) -> reassert!
        assertTrue(policy.shouldReassertBtRoute(2, ioniq, 4_200L))
        // Duplicate callback 30 ms later is debounced
        assertFalse(policy.shouldReassertBtRoute(2, ioniq, 4_230L))

        // Up to MAX_REASSERTIONS_IN_WINDOW (5 total) allowed, then backs off cleanly
        assertTrue(policy.shouldReassertBtRoute(2, ioniq, 4_500L))
        assertTrue(policy.shouldReassertBtRoute(2, ioniq, 4_800L))
        assertTrue(policy.shouldReassertBtRoute(2, ioniq, 5_100L))
        assertTrue(policy.shouldReassertBtRoute(2, ioniq, 5_400L))
        assertFalse(policy.shouldReassertBtRoute(2, ioniq, 5_700L))

        // Starting a new clip resets backoff
        policy.onExternalAudioActiveChanged(false, 8_000L)
        policy.onExternalAudioActiveChanged(true, 11_000L)
        assertTrue(policy.shouldReassertBtRoute(2, ioniq, 11_200L))
    }

    @Test
    fun `ON_FOREGROUND timing opens BT route immediately for Marco Polo after AA settle`() {
        val policy = BridgePolicy()
        policy.updateConfig(
            BridgeConfig(
                routingMode = RoutingMode.SMART_AUTO,
                scoTiming = ScoTiming.ON_FOREGROUND
            ),
            500L
        )
        policy.onAndroidAutoConnectionChanged(true, 1_000L)

        val opened = policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 4_000L)
        assertTrue(opened.shouldHoldBridge)
        assertTrue(opened.shouldEngageBtCommRoute)
        assertFalse(opened.shouldPlaySilentMediaKeepalive)
    }
}

