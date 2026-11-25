package com.hobby.aa2carlife.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CarLifeProtocolTest {

    @Test
    fun packCommandWritesBigEndianHeaderAndPayload() {
        val payload = byteArrayOf(1, 2, 3)
        val out = CarLifeProtocol.packCommand(98_305, payload)
        assertArrayEquals(
            byteArrayOf(0, 3, 0, 0, 0, 1, 0x80.toByte(), 1, 1, 2, 3),
            out,
        )
    }

    @Test
    fun readU32AndU16AreBigEndian() {
        val data = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        assertEquals(0x12345678, CarLifeProtocol.readU32(data, 0))
        assertEquals(0x1234, CarLifeProtocol.readU16(data, 0))
    }

    @Test
    fun touchPointUsesProtobufFields1And2() {
        assertArrayEquals(
            byteArrayOf(0x08, 1, 0x10, 2),
            CarLifeProtocol.touchPoint(1, 2),
        )
    }

    @Test
    fun touchPointDualAddsPointerFields() {
        assertArrayEquals(
            byteArrayOf(0x08, 1, 0x10, 2, 0x18, 3, 0x20, 4),
            CarLifeProtocol.touchPointDual(1, 2, 3, 4),
        )
    }
}