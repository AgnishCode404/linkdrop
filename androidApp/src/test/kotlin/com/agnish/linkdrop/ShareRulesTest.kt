package com.agnish.linkdrop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareRulesTest {

    @Test
    fun contentUrisFromOtherAppsAreAccepted() {
        assertTrue(isShareableUri("content://media/external/images/media/42"))
        assertTrue(isShareableUri("content://com.android.providers.downloads.documents/document/7"))
        assertTrue(isShareableUri("CONTENT://media/external/file/1"))
    }

    @Test
    fun fileUrisAreRefusedSoLinkDropCannotBeTrickedIntoSendingItsOwnFiles() {
        assertFalse(isShareableUri("file:///data/data/com.agnish.linkdrop/shared_prefs/linkdrop.xml"))
        assertFalse(isShareableUri("file:///sdcard/Download/a.jpg"))
    }

    @Test
    fun otherSchemesAreRefused() {
        for (uri in listOf("https://example.com/a.jpg", "android.resource://pkg/1", "", "contentx://a", " content://a")) {
            assertFalse(isShareableUri(uri), uri)
        }
    }
}
