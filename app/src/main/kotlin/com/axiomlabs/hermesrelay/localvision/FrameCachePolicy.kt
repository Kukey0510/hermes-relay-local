package com.axiomlabs.hermesrelay.localvision

object FrameCachePolicy {
    fun shouldUseCache(fresh: Boolean, cachedAgeMs: Long?, maxAgeMs: Long = 500): Boolean =
        !fresh && cachedAgeMs != null && cachedAgeMs in 0..maxAgeMs

    fun canFallbackOnTimeout(cachedAgeMs: Long?, maxAgeMs: Long = 1000): Boolean =
        cachedAgeMs != null && cachedAgeMs in 0..maxAgeMs
}
