package com.axiomlabs.hermesrelay.localvision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalVisionSpecStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedReloadLogsFilenameAndSummaryAndPreservesExactSpec() {
        val directory = temporary.root
        val file = directory.resolve("pages/blhx.json")
        file.parentFile!!.mkdirs()
        file.writeText("""{"pages":{"home":{"checks":[]}}}""")
        val warnings = mutableListOf<String>()
        val store = LocalVisionSpecStore { warnings += it }
        val first = store.load(directory, "blhx") { error("unexpected asset fallback") }
        file.writeText("{broken")
        val stale = store.load(directory, "blhx") { error("unexpected asset fallback") }
        assertTrue(first.pages === stale.pages)
        assertEquals("stale", stale.spec)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("pages/blhx.json"))
        assertTrue(warnings.single().contains("IllegalArgumentException"))
        assertTrue(warnings.single().contains("using stale spec"))
    }
}
