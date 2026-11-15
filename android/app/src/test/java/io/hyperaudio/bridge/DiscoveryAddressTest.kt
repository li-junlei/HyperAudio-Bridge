package io.hyperaudio.bridge

import org.junit.Assert.*
import org.junit.Test

class DiscoveryAddressTest {
    @Test fun subnetBroadcastsUseActualPrefix() {
        assertArrayEquals(byteArrayOf(192.toByte(),168.toByte(),5,255.toByte()), broadcastAddress(byteArrayOf(192.toByte(),168.toByte(),5,106),24))
        assertArrayEquals(byteArrayOf(10,4,255.toByte(),255.toByte()), broadcastAddress(byteArrayOf(10,4,6,8),16))
        assertArrayEquals(byteArrayOf(172.toByte(),31,255.toByte(),255.toByte()), broadcastAddress(byteArrayOf(172.toByte(),20,1,2),12))
    }
    @Test fun unsupportedNetworksDoNotGetBroadcast() {
        assertNull(broadcastAddress(ByteArray(16),64))
        assertNull(broadcastAddress(ByteArray(4),32))
        assertNull(broadcastAddress(ByteArray(4),0))
    }
}
