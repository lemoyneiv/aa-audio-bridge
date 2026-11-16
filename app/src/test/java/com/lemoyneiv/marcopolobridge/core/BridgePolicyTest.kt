package com.lemoyneiv.marcopolobridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePolicyTest {

    @Test
    fun `stays idle when Marco Polo opens at home without Android Auto`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(false, 1_000L)

        val snap = policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 1_500L)
        assertFalse(snap.shouldHoldBridge)
        assertFalse(snap.shouldEngageBtCommRoute)
        assertTrue(snap.marcoPoloForeground)
    }

    @Test
    fun `activates bridge and BT route when Marco Polo opens during Wireless Android Auto`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)

        val snap = policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 2_000L)
        assertTrue(snap.shouldHoldBridge)
        assertTrue(snap.shouldEngageBtCommRoute)
        assertFalse(snap.shouldPlaySilentMediaKeepalive)
    }

    @Test
    fun `ignores keyboard and systemui overlays while Marco Polo is open`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 2_000L)

        val keyboardSnap = policy.onForegroundPackageChanged(
            "com.google.android.inputmethod.latin",
            2_500L
        )
        assertTrue(keyboardSnap.shouldHoldBridge)
        assertTrue(keyboardSnap.marcoPoloForeground)

        val shadeSnap = policy.onForegroundPackageChanged("com.android.systemui", 3_000L)
        assertTrue(shadeSnap.shouldHoldBridge)
        assertTrue(shadeSnap.marcoPoloForeground)
    }

    @Test
    fun `holds through exit grace then releases cleanly when leaving Marco Polo`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 2_000L)

        val leaveSnap = policy.onForegroundPackageChanged(
            "com.google.android.apps.nexuslauncher",
            5_000L
        )
        assertTrue(leaveSnap.shouldHoldBridge)
        assertTrue(leaveSnap.inExitGrace)

        val afterGrace = policy.evaluate(7_100L)
        assertFalse(afterGrace.shouldHoldBridge)
        assertFalse(afterGrace.shouldEngageBtCommRoute)
        assertFalse(afterGrace.inExitGrace)
    }

    @Test
    fun `keeps playing long Polo if screen locks mid-playback and releases after finish`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 2_000L)
        policy.onExternalAudioActiveChanged(true, 2_500L)

        val screenOffSnap = policy.onScreenInteractiveChanged(false, 10_000L)
        assertTrue(screenOffSnap.shouldHoldBridge)
        assertTrue(screenOffSnap.shouldEngageBtCommRoute)

        // Still playing 30 seconds later with screen off
        assertTrue(policy.evaluate(40_000L).shouldHoldBridge)

        // Message ends -> enters 2s exit grace, then releases
        val endSnap = policy.onExternalAudioActiveChanged(false, 45_000L)
        assertTrue(endSnap.shouldHoldBridge)
        assertFalse(policy.evaluate(47_500L).shouldHoldBridge)
    }

    @Test
    fun `manual release suppresses auto-hold only for current Marco Polo visit`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 2_000L)
        assertTrue(policy.evaluate(2_100L).shouldHoldBridge)

        // User taps "Release / Resume AA" while Marco Polo is still open
        val released = policy.setManualHold(false, 3_000L)
        assertFalse(released.shouldHoldBridge)

        // Leaves Marco Polo and reopens it later -> auto-hold works again
        policy.onForegroundPackageChanged("com.google.android.apps.nexuslauncher", 4_000L)
        val reopened = policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 8_000L)
        assertTrue(reopened.shouldHoldBridge)
    }

    @Test
    fun `yields immediately to cellular phone call and resumes afterward`() {
        val policy = BridgePolicy()
        policy.onAndroidAutoConnectionChanged(true, 1_000L)
        policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 2_000L)

        val ringing = policy.onAudioModeChanged(BridgePolicy.AUDIO_MODE_RINGTONE, 3_000L)
        assertFalse(ringing.shouldHoldBridge)
        assertFalse(ringing.shouldEngageBtCommRoute)
        assertTrue(ringing.yieldingToPhoneCall)

        val inCall = policy.onAudioModeChanged(BridgePolicy.AUDIO_MODE_IN_CALL, 5_000L)
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
        policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 2_000L)

        val ioniq = CommDeviceCandidate(
            id = 25,
            type = CommDeviceCandidate.TYPE_BLUETOOTH_SCO,
            name = "IONIQ 5"
        )

        // Already routed to Ioniq 5 -> no reassertion needed
        assertFalse(policy.shouldReassertBtRoute(25, ioniq, 2_100L))

        // Marco Polo switches to built-in speaker (id = 2) -> reassert!
        assertTrue(policy.shouldReassertBtRoute(2, ioniq, 2_200L))
        // Duplicate callback 30 ms later is debounced
        assertFalse(policy.shouldReassertBtRoute(2, ioniq, 2_230L))

        // Up to MAX_REASSERTIONS_IN_WINDOW (5 total) allowed, then backs off cleanly
        assertTrue(policy.shouldReassertBtRoute(2, ioniq, 2_500L))
        assertTrue(policy.shouldReassertBtRoute(2, ioniq, 2_800L))
        assertTrue(policy.shouldReassertBtRoute(2, ioniq, 3_100L))
        assertTrue(policy.shouldReassertBtRoute(2, ioniq, 3_400L))
        assertFalse(policy.shouldReassertBtRoute(2, ioniq, 3_700L))

        // Starting a new Polo clip resets backoff
        policy.onExternalAudioActiveChanged(true, 9_000L)
        assertTrue(policy.shouldReassertBtRoute(2, ioniq, 9_200L))
    }

    @Test
    fun `ON_AUDIO_PLAYBACK timing uses silent media keepalive until Polo starts playing`() {
        val policy = BridgePolicy()
        policy.updateConfig(
            BridgeConfig(
                routingMode = RoutingMode.SMART_AUTO,
                scoTiming = ScoTiming.ON_AUDIO_PLAYBACK
            ),
            500L
        )
        policy.onAndroidAutoConnectionChanged(true, 1_000L)

        val opened = policy.onForegroundPackageChanged(BridgePolicy.PKG_MARCO_POLO, 2_000L)
        assertTrue(opened.shouldHoldBridge)
        assertFalse(opened.shouldEngageBtCommRoute)
        assertTrue(opened.shouldPlaySilentMediaKeepalive)

        val playing = policy.onExternalAudioActiveChanged(true, 3_000L)
        assertTrue(playing.shouldHoldBridge)
        assertTrue(playing.shouldEngageBtCommRoute)
        assertFalse(playing.shouldPlaySilentMediaKeepalive)
    }
}
