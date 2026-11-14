package com.hermesandroid.relay.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeScreenPolicyTest {
    @Test
    fun policySwitchesAreDisabledWhenMasterIsOff() {
        assertFalse(bridgePolicySwitchEnabled(masterEnabled = false))
        assertTrue(bridgePolicySwitchEnabled(masterEnabled = true))
    }
}
