package com.hermesandroid.relay.script.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class PickIndexTest {
    @Test fun minSelectsSmallestOfThreeSingleRegions() {
        assertEquals(1, pickIndex(listOf(listOf(300), listOf(100), listOf(200)), "min", 0, 3))
    }

    @Test fun sumsEveryRegionBeforeComparing() {
        assertEquals(2, pickIndex(listOf(listOf(100, 200), listOf(50, 60), listOf(10, 10)), "min", 0, 3))
    }

    @Test fun missingRegionInvalidatesTheWholeCard() {
        assertEquals(2, pickIndex(listOf(listOf(1, null), listOf(50, 60), listOf(10, 10)), "min", 0, 3))
    }

    @Test fun allInvalidUsesFallback() {
        assertEquals(1, pickIndex(listOf(listOf(null), listOf(null), listOf(null)), "min", 1, 3))
    }

    @Test fun maxSelectsLargestSum() {
        assertEquals(0, pickIndex(listOf(listOf(100, 200), listOf(50, 60), listOf(10, 10)), "max", 2, 3))
    }

    @Test fun fallbackAboveRangeIsClamped() {
        assertEquals(2, pickIndex(List(3) { listOf(null) }, "min", 99, 3))
    }

    @Test fun fallbackBelowRangeIsClamped() {
        assertEquals(0, pickIndex(List(3) { listOf(null) }, "min", -99, 3))
    }

    @Test fun emptyGroupsAreInvalidRatherThanZero() {
        assertEquals(1, pickIndex(listOf(emptyList(), listOf(10)), "min", 0, 2))
    }

    @Test fun missingCardsAreInvalid() {
        assertEquals(2, pickIndex(emptyList(), "max", 2, 3))
    }

    @Test fun totalsDoNotOverflowInt() {
        val readings = listOf(listOf(Int.MAX_VALUE, Int.MAX_VALUE), listOf(10, 10))
        assertEquals(1, pickIndex(readings, "min", 0, 2))
        assertEquals(0, pickIndex(readings, "max", 1, 2))
    }

    @Test fun tiesChooseFirstValidCard() {
        val readings = listOf(listOf(null), listOf(10, 20), listOf(30))
        assertEquals(1, pickIndex(readings, "min", 2, 3))
        assertEquals(1, pickIndex(readings, "max", 2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun noCardsIsRejected() {
        pickIndex(emptyList(), "min", 0, 0)
    }
}
