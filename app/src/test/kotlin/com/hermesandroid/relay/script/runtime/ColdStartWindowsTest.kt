package com.hermesandroid.relay.script.runtime

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hermesandroid.relay.accessibility.HermesAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColdStartWindowsTest {
    private fun service() = mockk<HermesAccessibilityService> {
        every { foregroundPackageFromWindows() } answers { callOriginal() }
        every { rootInActiveWindow } returns null
        every { currentApp } returns "com.android.systemui"
    }

    private fun window(layer: Int, type: Int, active: Boolean, pkg: String): Pair<AccessibilityWindowInfo, AccessibilityNodeInfo> {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true) { every { packageName } returns pkg }
        val window = mockk<AccessibilityWindowInfo> {
            every { getLayer() } returns layer
            every { getType() } returns type
            every { isActive() } returns active
            every { root } returns node
        }
        return window to node
    }

    @Test fun systemWindowDoesNotUseStaleSystemUiEvent() {
        val service = service()
        val (system, node) = window(99, AccessibilityWindowInfo.TYPE_SYSTEM, true, "com.android.systemui")
        every { service.windows } returns listOf(system)
        assertNull(service.foregroundPackageFromWindows())
        verify(exactly = 0) { system.root }
        verify(exactly = 0) { node.recycle() }
        verify(exactly = 0) { service.currentApp }
    }

    @Test fun highestActiveApplicationWinsAndRootIsRecycled() {
        val service = service()
        val (lower, _) = window(1, AccessibilityWindowInfo.TYPE_APPLICATION, true, "lower")
        val (higher, node) = window(2, AccessibilityWindowInfo.TYPE_APPLICATION, true, "game")
        val (inactive, _) = window(3, AccessibilityWindowInfo.TYPE_APPLICATION, false, "inactive")
        every { service.windows } returns listOf(lower, inactive, higher)
        assertEquals("game", service.foregroundPackageFromWindows())
        verify(exactly = 1) { node.recycle() }
        verify(exactly = 0) { lower.root }
        verify(exactly = 0) { inactive.root }
    }

    @Test fun emptyOrFailedWindowsFallBackToActiveRootAndRecycleIt() {
        for (fail in listOf(false, true)) {
            val service = service()
            if (fail) every { service.windows } throws IllegalStateException("unavailable")
            else every { service.windows } returns emptyList()
            val node = mockk<AccessibilityNodeInfo>(relaxed = true) { every { packageName } returns "game" }
            every { service.rootInActiveWindow } returns node
            assertEquals("game", service.foregroundPackageFromWindows())
            verify(exactly = 1) { node.recycle() }
        }
    }
}
