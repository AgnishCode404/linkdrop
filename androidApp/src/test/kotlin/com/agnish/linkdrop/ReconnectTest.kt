package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.DiscoveredDesktop
import com.agnish.linkdrop.protocol.PairedDevice
import com.agnish.linkdrop.protocol.ServerAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When the home screen offers "Reconnect", and where it connects to. */
class ReconnectTest {
    private val desk = PairedDevice("desk-1", "archlinux", "a".repeat(64), 0)
    private val other = PairedDevice("desk-2", "laptop", "b".repeat(64), 0)
    private val dropped = PhoneConnection.State.Disconnected("connection lost: timeout")

    @Test
    fun offeredForTheLastDesktopWhenItsConnectionDropped() {
        assertEquals(desk, PhoneConnection.reconnectTarget(dropped, "desk-1", listOf(other, desk)))
        val limit = PhoneConnection.State.Disconnected(ConnectionNotification.TIME_LIMIT_REASON)
        assertEquals(desk, PhoneConnection.reconnectTarget(limit, "desk-1", listOf(desk)))
    }

    @Test
    fun notOfferedAfterTheUsersOwnDisconnect() {
        assertNull(PhoneConnection.reconnectTarget(PhoneConnection.State.Disconnected(null), "desk-1", listOf(desk)))
    }

    @Test
    fun notOfferedForAnUnpairedOrUnknownDesktop() {
        assertNull(PhoneConnection.reconnectTarget(dropped, "desk-3", listOf(desk)), "not paired")
        assertNull(PhoneConnection.reconnectTarget(dropped, null, listOf(desk)), "never connected")
        assertNull(PhoneConnection.reconnectTarget(dropped, "desk-1", emptyList()), "forgotten")
    }

    @Test
    fun notOfferedWhileConnectingOrConnected() {
        val connecting = PhoneConnection.State.Connecting(ServerAddress("192.168.1.5", 47800))
        assertNull(PhoneConnection.reconnectTarget(connecting, "desk-1", listOf(desk)))
    }

    @Test
    fun followsTheDesktopToItsNewAddress() {
        val moved = DiscoveredDesktop("desk-1", "archlinux", ServerAddress("192.168.1.77", 47800))
        val someoneElse = DiscoveredDesktop("desk-9", "archlinux", ServerAddress("192.168.1.66", 47800))
        assertEquals(
            ServerAddress("192.168.1.77", 47800),
            PhoneConnection.reconnectAddress(desk, listOf(someoneElse, moved), "192.168.1.5:47800"),
        )
    }

    @Test
    fun usesTheLastAddressWhenDiscoveryDoesNotSeeIt() {
        assertEquals(ServerAddress("192.168.1.5", 47800), PhoneConnection.reconnectAddress(desk, emptyList(), "192.168.1.5:47800"))
        assertNull(PhoneConnection.reconnectAddress(desk, emptyList(), ""))
    }
}
