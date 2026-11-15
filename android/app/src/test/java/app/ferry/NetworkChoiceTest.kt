package app.ferry

import app.ferry.net.BroadcastAddress
import app.ferry.net.NetworkCandidate
import app.ferry.net.chooseNetwork
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkChoiceTest {
    private fun n(id: Long, wifi: Boolean = false, eth: Boolean = false, cell: Boolean = false, active: Boolean = false) =
        NetworkCandidate(id, wifi, eth, cell, active)

    @Test fun wifiBeatsEthernetBeatsOther() {
        assertEquals(1L, chooseNetwork(listOf(n(2, cell = true, active = true), n(1, wifi = true)))?.id)
        assertEquals(3L, chooseNetwork(listOf(n(2, cell = true, active = true), n(3, eth = true)))?.id)
        assertEquals(4L, chooseNetwork(listOf(n(4, active = true), n(5, cell = true)))?.id)
        assertEquals(6L, chooseNetwork(listOf(n(5, wifi = true), n(6, wifi = true, active = true)))?.id)
    }

    @Test fun cellularOnlyOrNothingIsNotOnWifi() {
        assertNull(chooseNetwork(listOf(n(1, cell = true, active = true))))
        assertNull(chooseNetwork(emptyList()))
    }

    @Test fun debugOverrideTakesTheActiveNetwork() {
        assertEquals(1L, chooseNetwork(listOf(n(1, cell = true, active = true)), anyNetworkOverride = true)?.id)
        assertEquals(1L, chooseNetwork(listOf(n(1, cell = true)), anyNetworkOverride = true)?.id)
        assertNull(chooseNetwork(emptyList(), anyNetworkOverride = true))
    }
}

class BroadcastAddressTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun prefixToDirectedBroadcast() {
        assertArrayEquals(b(192, 168, 1, 255), BroadcastAddress.directed(b(192, 168, 1, 23), 24))
        assertArrayEquals(b(10, 0, 2, 255), BroadcastAddress.directed(b(10, 0, 2, 16), 24))
        assertArrayEquals(b(172, 31, 255, 255), BroadcastAddress.directed(b(172, 16, 4, 1), 12))
        assertArrayEquals(b(192, 168, 1, 127), BroadcastAddress.directed(b(192, 168, 1, 5), 25))
    }

    @Test fun noBroadcastForOddInput() {
        assertNull(BroadcastAddress.directed(b(192, 168, 1, 5), 31))
        assertNull(BroadcastAddress.directed(b(192, 168, 1, 5), 0))
        assertNull(BroadcastAddress.directed(ByteArray(16), 64))
    }

    @Test fun firstThreeOctets() {
        assertEquals("192.168.1.", BroadcastAddress.firstThreeOctets("192.168.1.23"))
        assertEquals("", BroadcastAddress.firstThreeOctets(null))
        assertEquals("", BroadcastAddress.firstThreeOctets("fe80::1"))
    }
}
