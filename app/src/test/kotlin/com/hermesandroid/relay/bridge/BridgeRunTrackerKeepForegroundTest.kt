package com.hermesandroid.relay.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRunTrackerKeepForegroundTest {
    @After
    fun tearDown() = BridgeRunTracker.reset()

    @Test
    fun zeroSecondsImmediatelyClearsSuppression() {
        BridgeRunTracker.keepForeground(60L, nowMs = 1_000L)

        val result = BridgeRunTracker.keepForeground(0L, nowMs = 2_000L)

        assertEquals(0L, result.untilMs)
        assertFalse(BridgeRunTracker.isAutoReturnSuppressed(2_000L))
    }

    @Test
    fun normalValueSuppressesUntilRequestedDeadline() {
        val result = BridgeRunTracker.keepForeground(600L, nowMs = 1_000L)

        assertEquals(601_000L, result.untilMs)
        assertEquals(600L, result.requestedSeconds)
        assertFalse(result.clamped)
        assertTrue(BridgeRunTracker.isAutoReturnSuppressed(600_999L))
        assertFalse(BridgeRunTracker.isAutoReturnSuppressed(601_000L))
    }

    @Test
    fun excessiveValueIsClampedToOneHour() {
        val result = BridgeRunTracker.keepForeground(7_200L, nowMs = 1_000L)

        assertEquals(3_601_000L, result.untilMs)
        assertEquals(7_200L, result.requestedSeconds)
        assertTrue(result.clamped)
    }

    @Test
    fun masterDisabledRejectsWithoutChangingSuppression() {
        val decision = BridgeRunTracker.requestKeepForeground(
            seconds = 600L,
            masterEnabled = false,
            nowMs = 1_000L,
        )

        assertEquals(BridgeRunTracker.KeepForegroundDecision.MasterDisabled, decision)
        assertFalse(BridgeRunTracker.isAutoReturnSuppressed(1_000L))
    }
}
