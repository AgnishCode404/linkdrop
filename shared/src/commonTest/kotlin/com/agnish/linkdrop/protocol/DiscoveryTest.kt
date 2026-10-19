package com.agnish.linkdrop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryTest {

    private val txt = mapOf("v" to "1", "id" to "desk-1")

    private fun announce(
        name: String = "Arch",
        host: String = "192.168.29.75",
        port: Int = DEFAULT_PORT,
        record: Map<String, String?> = txt,
    ) = DiscoveredDesktop.fromAnnouncement(name, host, port, record)

    @Test
    fun validAnnouncementIsAccepted() {
        assertEquals(DiscoveredDesktop("desk-1", "Arch", ServerAddress("192.168.29.75", DEFAULT_PORT)), announce())
    }

    @Test
    fun txtRecordIsWhatTheDesktopAnnounces() {
        val desktop = LocalDevice("desk-1", "Arch")
        assertEquals(mapOf("v" to "1", "id" to "desk-1"), DiscoveredDesktop.txtRecord(desktop))
        // What one side announces, the other accepts.
        assertEquals("desk-1", announce(record = DiscoveredDesktop.txtRecord(desktop))?.deviceId)
    }

    @Test
    fun otherProtocolVersionsAndMissingVersionAreIgnored() {
        assertNull(announce(record = mapOf("v" to "2", "id" to "desk-1")))
        assertNull(announce(record = mapOf("id" to "desk-1")))
        assertNull(announce(record = mapOf("v" to null, "id" to "desk-1")))
    }

    @Test
    fun invalidIdsAreIgnored() {
        for (id in listOf(null, "", " ", "x".repeat(MAX_DEVICE_FIELD_LENGTH + 1), "a\nb")) {
            assertNull(announce(record = mapOf("v" to "1", "id" to id)), "id=$id")
        }
    }

    @Test
    fun invalidPortsAreIgnored() {
        for (port in listOf(0, -1, 65_536)) assertNull(announce(port = port), "port=$port")
    }

    @Test
    fun onlyPrivateNetworkAddressesAreAccepted() {
        for (host in listOf("10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.3.4")) {
            assertEquals(host, announce(host = host)?.address?.host, host)
        }
        // A fake announcement must not send the phone to the internet, a host name, or IPv6.
        for (host in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "100.64.0.1", "127.0.0.1", "evil.example.com",
            "fe80::1", "192.168.1", "192.168.1.256", "", "192.168.1.1:80")) {
            assertNull(announce(host = host), host)
        }
    }

    @Test
    fun namesAreCleaned() {
        assertEquals("Arch", announce(name = "  Arch  ")?.name)
        assertEquals("Archbox", announce(name = "Arch\u202Ebox")?.name)
        assertEquals("Arch box", announce(name = "Arch\u0007 box")?.name)
        assertEquals(MAX_DEVICE_FIELD_LENGTH, announce(name = "x".repeat(200))?.name?.length)
        assertEquals(DiscoveredDesktop.UNNAMED, announce(name = "\u0000\u200B ")?.name)
    }

    @Test
    fun isPrivateIpv4RejectsMalformedInput() {
        assertTrue(DiscoveredDesktop.isPrivateIpv4("192.168.0.1"))
        for (host in listOf("192.168.0.01x", "192.168..1", "192.168.0.1.5", "a.b.c.d", "1922.168.0.1")) {
            assertFalse(DiscoveredDesktop.isPrivateIpv4(host), host)
        }
    }

    private fun desktop(id: String, name: String, host: String = "192.168.1.2") =
        DiscoveredDesktop(id, name, ServerAddress(host, DEFAULT_PORT))

    @Test
    fun listShowsEachDesktopOnceSortedByName() {
        val list = DiscoveredDesktops()
        list.found("Zeta", desktop("z", "Zeta"))
        list.found("arch", desktop("a", "arch"))
        // The same desktop announced on a second interface.
        list.found("arch (2)", desktop("a", "arch", host = "10.0.0.2"))

        assertEquals(listOf("a", "z"), list.list().map { it.deviceId })
        assertEquals("10.0.0.2", list.list().first().address.host)
    }

    @Test
    fun lostAnnouncementsDisappear() {
        val list = DiscoveredDesktops()
        list.found("arch", desktop("a", "arch", host = "192.168.1.2"))
        list.found("arch (2)", desktop("a", "arch", host = "10.0.0.2"))
        list.lost("arch (2)")
        assertEquals("192.168.1.2", list.list().single().address.host)
        list.lost("arch")
        assertTrue(list.list().isEmpty())
    }

    @Test
    fun aRememberedDesktopIsFoundAtItsNewAddress() {
        val list = DiscoveredDesktops()
        list.found("arch", desktop("a", "arch", host = "192.168.1.2"))
        list.lost("arch")
        list.found("arch", desktop("a", "arch", host = "192.168.1.77")) // new IP from the router
        assertEquals("192.168.1.77", list.find("a")?.address?.host)
        assertNull(list.find("unknown"))
    }
}
