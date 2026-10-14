package com.agnish.linkdrop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerAddressTest {

    @Test
    fun ipWithoutPortUsesDefaultPort() {
        assertEquals(ServerAddress("192.168.29.75", DEFAULT_PORT), ServerAddress.parse("192.168.29.75"))
    }

    @Test
    fun ipWithPort() {
        assertEquals(ServerAddress("10.0.0.2", 8080), ServerAddress.parse("10.0.0.2:8080"))
    }

    @Test
    fun surroundingSpacesAreIgnored() {
        assertEquals(ServerAddress("10.0.0.2", DEFAULT_PORT), ServerAddress.parse("  10.0.0.2 "))
    }

    @Test
    fun hostNames() {
        assertEquals(ServerAddress("archbox.local", DEFAULT_PORT), ServerAddress.parse("archbox.local"))
        assertEquals(ServerAddress("my-pc", 1), ServerAddress.parse("my-pc:1"))
    }

    @Test
    fun invalidInputIsRejected() {
        for (input in listOf(
            "", " ", ":47800", "192.168.1.256", "1.2.3.999", "192.168.1", "192.168.1.1:", "192.168.1.1:0",
            "192.168.1.1:65536", "192.168.1.1:abc", "192.168.1.1:80:90", "ws://192.168.1.1",
            "192.168.1.1/v1", "-bad.local", "bad-.local", "a..b", "fe80::1", "host name",
        )) {
            assertNull(ServerAddress.parse(input), "should reject \"$input\"")
        }
    }
}
