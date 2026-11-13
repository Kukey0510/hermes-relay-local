package com.hermesandroid.relay.ui.scripts

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptCatalogTest {
    private val first = ScriptEntry("one", "One", "Game", listOf("daily"), "now", "", emptyList(), "")
    private val second = ScriptEntry("two", "Two", "Game", listOf("event", "hard"), "now", "", emptyList(), "")

    @Test
    fun legacySingleTagJsonIsWrappedInList() {
        val json = """[{"id":"one","name":"One","game":"Game","tag":"daily","updatedAt":"now","description":"","steps":[],"sourceNote":""}]"""

        assertEquals(listOf("daily"), ScriptCatalog.decode(json).single().tags)
    }

    @Test
    fun multipleSelectedTagsUseOrMatching() {
        assertEquals(listOf("one", "two"), filterScripts(listOf(first, second), setOf("daily", "hard")).map { it.id })
        assertEquals(listOf("two"), filterScripts(listOf(first, second), setOf("event")).map { it.id })
    }

    @Test
    fun localTagsReplaceCatalogDefaults() {
        val merged = applyScriptTagOverrides(listOf(first, second), mapOf("one" to listOf("custom", "new")))

        assertEquals(listOf("custom", "new"), merged.first().tags)
        assertEquals(second.tags, merged.last().tags)
    }
}
