package com.axiomlabs.hermesrelay.localvision

import com.axiomlabs.hermesrelay.localvision.FrameArchive.ArchiveCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameArchiveTest {
    @Test
    fun evictsOldestEntryWhenFileLimitIsExceeded() {
        val entries = (1L..13L).map { index -> candidate("frame-$index.png", index, KIB) }

        val evictions = FrameArchive.evictionsFor(entries, maxFiles = 12, maxBytes = 20 * MIB)

        assertEquals(listOf(entries.first()), evictions)
    }

    @Test
    fun evictsOldestEntriesUntilByteLimitIsMet() {
        val entries = (1L..4L).map { index -> candidate("frame-$index.png", index, 8 * MIB) }

        val evictions = FrameArchive.evictionsFor(entries, maxFiles = 12, maxBytes = 20 * MIB)

        assertEquals(listOf(entries[0], entries[1]), evictions)
        assertTrue(entries.sumOf { it.sizeBytes } - evictions.sumOf { it.sizeBytes } <= 20 * MIB)
    }

    @Test
    fun returnsNoEvictionsForEmptyOrWithinLimitEntries() {
        assertTrue(FrameArchive.evictionsFor(emptyList()).isEmpty())

        val entries = listOf(
            candidate("first.png", 1, KIB),
            candidate("second.png", 2, KIB),
        )
        assertTrue(FrameArchive.evictionsFor(entries).isEmpty())
    }

    @Test
    fun usesNameToBreakLastModifiedTies() {
        val laterName = candidate("z-frame.png", 1, KIB)
        val earlierName = candidate("a-frame.png", 1, KIB)

        val evictions = FrameArchive.evictionsFor(
            listOf(laterName, earlierName),
            maxFiles = 1,
            maxBytes = 20 * MIB,
        )

        assertEquals(listOf(earlierName), evictions)
    }

    private fun candidate(name: String, lastModifiedMs: Long, sizeBytes: Long) =
        ArchiveCandidate(name, lastModifiedMs, sizeBytes)

    private companion object {
        const val KIB = 1024L
        const val MIB = 1024L * 1024L
    }
}
