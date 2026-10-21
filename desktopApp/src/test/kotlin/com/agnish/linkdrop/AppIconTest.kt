package com.agnish.linkdrop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AppIconTest {
    @Test
    fun everySizeIsBundledAndReadable() {
        val images = AppIcon.images()
        assertEquals(AppIcon.SIZES, images.map { it.width })
        assertEquals(AppIcon.SIZES, images.map { it.height })
        assertNotNull(AppIcon.painter(images))
    }
}
