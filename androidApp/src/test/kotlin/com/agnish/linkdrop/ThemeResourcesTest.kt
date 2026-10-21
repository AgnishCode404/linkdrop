package com.agnish.linkdrop

import com.agnish.linkdrop.ui.Palette
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeResourcesTest {
    @Test
    fun launchWindowColorMatchesThePalette() {
        // Android resources cannot read Kotlin constants, so the value is written twice.
        val xml = File("src/main/res/values/colors.xml").readText()
        val value = Regex("""<color name="linkdrop_background">#([0-9A-Fa-f]{8})</color>""").find(xml)!!.groupValues[1]
        assertEquals(Palette.BACKGROUND, value.toLong(16))
    }
}
