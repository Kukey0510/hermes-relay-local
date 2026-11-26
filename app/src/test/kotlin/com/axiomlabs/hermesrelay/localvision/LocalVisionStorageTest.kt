package com.axiomlabs.hermesrelay.localvision

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalVisionStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory get() = temporary.root
    private fun put(path: String): File = directory.resolve(path).apply {
        parentFile!!.mkdirs(); writeText("fixture")
    }

    @Test fun templateUsesGameBeforeFlatLegacyFile() {
        put("templates/x.png")
        val game = put("templates/jc/x.png")
        assertEquals(game, localVisionTemplateFile(directory, "x.png", "jc"))
    }
    @Test fun templateFallsBackToFlatLegacyFile() {
        val legacy = put("templates/x.png")
        put("templates/blhx/x.png")
        assertEquals(legacy, localVisionTemplateFile(directory, "x.png", "jc"))
    }
    @Test fun missingTemplateReturnsNullForAssetAdapter() {
        put("templates/blhx/x.png")
        assertNull(localVisionTemplateFile(directory, "x.png", "jc"))
    }
    @Test fun omittedGameOnlyReadsLegacyTemplates() {
        put("templates/jc/x.png")
        assertNull(localVisionTemplateFile(directory, "x.png", null))
        val legacy = put("templates/x.png")
        assertEquals(legacy, localVisionTemplateFile(directory, "x.png", null))
    }
    @Test fun blankGameKeepsLegacyTemplateSelection() {
        put("templates/jc/x.png")
        val legacy = put("templates/x.png")
        assertEquals(legacy, localVisionTemplateFile(directory, "x.png", ""))
    }
    @Test fun templatesNeverCrossGameDirectories() {
        val jc = put("templates/jc/x.png")
        val blhx = put("templates/blhx/x.png")
        assertEquals(jc, localVisionTemplateFile(directory, "x.png", "jc"))
        assertEquals(blhx, localVisionTemplateFile(directory, "x.png", "blhx"))
    }
    @Test fun digitsUseGameBeforeFlatLegacyFile() {
        put("digits/field/0.png")
        val game = put("digits/jc/field/0.png")
        assertEquals(game, digitTemplateFile(directory, "jc/field", "0.png"))
    }
    @Test fun digitsFallBackPerCharacterRatherThanPerDirectory() {
        put("digits/jc/field/1.png")
        val legacy = put("digits/field/0.png")
        assertEquals(legacy, digitTemplateFile(directory, "jc/field", "0.png"))
    }
    @Test fun missingDigitReturnsNullForAssetAdapter() {
        put("digits/blhx/field/0.png")
        assertNull(digitTemplateFile(directory, "jc/field", "0.png"))
    }
    @Test fun unqualifiedFieldKeepsLegacyDigitPath() {
        put("digits/jc/field/0.png")
        assertNull(digitTemplateFile(directory, "field", "0.png"))
        val legacy = put("digits/field/0.png")
        assertEquals(legacy, digitTemplateFile(directory, "field", "0.png"))
    }
    @Test fun explicitGameAndQualifiedFieldUseSameDigitPathWithoutRepeatingGame() {
        val game = put("digits/jc/jc_sim_times/slash.png")
        assertEquals(game, digitTemplateFile(directory, "jc_sim_times", "slash.png", "jc"))
        assertEquals(game, digitTemplateFile(directory, "jc/jc_sim_times", "slash.png"))
        assertEquals(listOf("digits/jc/jc_sim_times/slash.png", "digits/jc_sim_times/slash.png"),
            digitTemplatePaths("jc/jc_sim_times", "slash.png"))
    }
    @Test fun digitsNeverCrossGameDirectories() {
        val jc = put("digits/jc/field/0.png")
        val blhx = put("digits/blhx/field/0.png")
        assertEquals(jc, digitTemplateFile(directory, "jc/field", "0.png"))
        assertEquals(blhx, digitTemplateFile(directory, "blhx/field", "0.png"))
    }
    @Test fun invalidGameAndFieldCannotTraverseDirectories() {
        assertTrue(runCatching { localVisionTemplateFile(directory, "x.png", "../jc") }.isFailure)
        assertTrue(runCatching { digitTemplateFile(directory, "../field", "0.png") }.isFailure)
        assertTrue(runCatching { digitTemplateFile(directory, "jc/../field", "0.png") }.isFailure)
    }
}
