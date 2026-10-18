package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.DiscoveredDesktop
import com.agnish.linkdrop.protocol.FEATURE_TRANSFER
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.SERVICE_TYPE_FQDN
import com.agnish.linkdrop.protocol.TxtKeys
import org.junit.Assume.assumeTrue
import java.net.InetAddress
import javax.jmdns.JmDNS
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real mDNS on this machine's LAN address: the desktop announces, a second mDNS instance looks
 * for it the way a phone would. Skipped when the machine has no LAN address.
 */
class DesktopAnnouncerTest {

    // A random id per run, so an announcement left over from another run cannot satisfy the test.
    private val desktop = LocalDevice("test-${System.nanoTime()}", "LinkDrop test desktop", features = listOf(FEATURE_TRANSFER))
    private val log = mutableListOf<String>()
    private val announcer = DesktopAnnouncer(desktop, port = 47_999) { synchronized(log) { log += it } }
    private var browser: JmDNS? = null

    @AfterTest
    fun tearDown() {
        announcer.stop()
        browser?.close()
    }

    @Test
    fun phonesCanFindTheAnnouncedDesktop() {
        val address = DesktopDevice.lanAddresses().firstOrNull()
        assumeTrue("no LAN address on this machine", address != null)

        announcer.start(listOf(address!!))
        val status = assertIs<DesktopAnnouncer.Status.Visible>(announcer.status.value, "log: $log")
        assertEquals(listOf(address), status.addresses)

        val found = JmDNS.create(InetAddress.getByName(address), "linkdrop-test-browser").also { browser = it }
            .list(SERVICE_TYPE_FQDN, 8_000)
            .firstOrNull { it.getPropertyString(TxtKeys.DEVICE_ID) == desktop.deviceId }
        assertNotNull(found, "announcement not found; services seen: none matching ${desktop.deviceId}")

        // What the phone does with an announcement (shared validation).
        val discovered = DiscoveredDesktop.fromAnnouncement(
            instanceName = found.name,
            host = found.inet4Addresses.first().hostAddress,
            port = found.port,
            txt = mapOf(
                TxtKeys.VERSION to found.getPropertyString(TxtKeys.VERSION),
                TxtKeys.DEVICE_ID to found.getPropertyString(TxtKeys.DEVICE_ID),
            ),
        )
        assertNotNull(discovered, "the announcement did not pass validation: $found")
        assertEquals(desktop.deviceId, discovered.deviceId)
        assertEquals(address, discovered.address.host)
        assertEquals(47_999, discovered.address.port)
        assertTrue(discovered.name.startsWith("LinkDrop test desktop"), discovered.name)
    }

    @Test
    fun withoutAnyAddressItSaysSo() {
        announcer.start(emptyList())
        assertEquals(DesktopAnnouncer.Status.NotVisible("no Wi-Fi/LAN address"), announcer.status.value)
    }
}
