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
    fun gameAndScenarioRowsNarrowTogether() {
        val other = ScriptEntry("three", "Three", "Other", listOf("daily"), "now", "", emptyList(), "")

        assertEquals(listOf("one", "two", "three"), filterScripts(listOf(first, second, other), emptySet(), emptySet()).map { it.id })
        assertEquals(listOf("three"), filterScripts(listOf(first, second, other), setOf("Other"), emptySet()).map { it.id })
        assertEquals(listOf("one", "three"), filterScripts(listOf(first, second, other), emptySet(), setOf("daily")).map { it.id })
        assertEquals(listOf("two"), filterScripts(listOf(first, second, other), setOf("Game"), setOf("hard")).map { it.id })
    }

    @Test
    fun scenarioRowDropsGameNamesAndKeepsUserTags() {
        val tags = scenarioTagsOf(listOf(first, second), setOf("闲置标签"))

        assertEquals(listOf("daily", "event", "hard", "闲置标签"), tags)
    }

    @Test
    fun localTagsReplaceCatalogDefaults() {
        val merged = applyScriptTagOverrides(listOf(first, second), mapOf("one" to listOf("custom", "new")))

        assertEquals(listOf("custom", "new"), merged.first().tags)
        assertEquals(second.tags, merged.last().tags)
    }
}
