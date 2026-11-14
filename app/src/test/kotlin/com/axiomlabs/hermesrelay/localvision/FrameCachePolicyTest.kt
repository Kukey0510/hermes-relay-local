package com.axiomlabs.hermesrelay.localvision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCachePolicyTest {
    @Test
    fun freshRequestsNeverUseCache() {
        listOf(null, -1L, 0L, 499L, 500L, 501L).forEach { age ->
            assertFalse(FrameCachePolicy.shouldUseCache(fresh = true, cachedAgeMs = age))
        }
    }

    @Test
    fun missingCacheCannotBeUsed() {
        assertFalse(FrameCachePolicy.shouldUseCache(fresh = false, cachedAgeMs = null))
        assertFalse(FrameCachePolicy.canFallbackOnTimeout(null))
    }

    @Test
    fun cacheAgeIncludesFiveHundredMilliseconds() {
        listOf(0L, 499L, 500L).forEach { age ->
            assertTrue(FrameCachePolicy.shouldUseCache(fresh = false, cachedAgeMs = age))
        }
        assertFalse(FrameCachePolicy.shouldUseCache(fresh = false, cachedAgeMs = 501L))
        assertFalse(FrameCachePolicy.shouldUseCache(fresh = false, cachedAgeMs = -1L))
    }

    @Test
    fun timeoutFallbackIncludesOneThousandMilliseconds() {
        assertTrue(FrameCachePolicy.canFallbackOnTimeout(999L))
        assertTrue(FrameCachePolicy.canFallbackOnTimeout(1000L))
        assertFalse(FrameCachePolicy.canFallbackOnTimeout(1001L))
        assertFalse(FrameCachePolicy.canFallbackOnTimeout(-1L))
    }

    @Test
    fun customAgeLimitsAreRespected() {
        assertTrue(FrameCachePolicy.shouldUseCache(false, 10L, maxAgeMs = 10L))
        assertFalse(FrameCachePolicy.shouldUseCache(false, 11L, maxAgeMs = 10L))
        assertTrue(FrameCachePolicy.canFallbackOnTimeout(10L, maxAgeMs = 10L))
        assertFalse(FrameCachePolicy.canFallbackOnTimeout(11L, maxAgeMs = 10L))
    }
}
