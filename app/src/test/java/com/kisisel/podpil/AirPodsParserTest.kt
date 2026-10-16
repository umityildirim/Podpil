package com.kisisel.podpil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPodsParserTest {

    /** 07 19 01 [model 2B] [durum] [pod pilleri] [şarj|kutu] + 19 bayt dolgu = 27 bayt. */
    private fun packet(model: Int, status: Int, pods: Int, chargeCase: Int): ByteArray {
        val head = byteArrayOf(0x07, 0x19, 0x01, (model shr 8).toByte(), model.toByte(),
            status.toByte(), pods.toByte(), chargeCase.toByte())
        return head + ByteArray(27 - head.size)
    }

    @Test
    fun notFlipped_leftIsLowNibble() {
        // durum 0x20 biti açık → flip yok: sol = alt nibble (8), sağ = üst nibble (9)
        val b = AirPodsParser.parse(packet(0x0E20, 0x2B, 0x98, 0x15))!!
        assertEquals(85, b.left)
        assertEquals(95, b.right)
        assertEquals(55, b.case)
        assertTrue(b.leftCharging)   // şarj nibble 0b0001 → sol
        assertFalse(b.rightCharging)
        assertFalse(b.caseCharging)
        assertEquals("AirPods Pro", AirPodsParser.modelName(b.model))
    }

    @Test
    fun flipped_swapsSidesAndChargeBits() {
        // durum 0x20 biti kapalı → flip: sol = üst nibble (9), sağ = alt nibble (8)
        val b = AirPodsParser.parse(packet(0x1420, 0x0B, 0x98, 0x6A))!!
        assertEquals(95, b.left)
        assertEquals(85, b.right)
        assertEquals(100, b.case)
        assertTrue(b.leftCharging)   // şarj nibble 0b0110: flip'te 0b0010 → sol
        assertFalse(b.rightCharging)
        assertTrue(b.caseCharging)
    }

    @Test
    fun nibble15_isUnknown() {
        val b = AirPodsParser.parse(packet(0x0E20, 0x20, 0xF7, 0x0F))!!
        assertEquals(75, b.left)
        assertNull(b.right)
        assertNull(b.case)
    }

    @Test
    fun rejectsWrongTypeOrLength() {
        assertNull(AirPodsParser.parse(ByteArray(27).also { it[0] = 0x10; it[1] = 0x19 }))
        assertNull(AirPodsParser.parse(byteArrayOf(0x07, 0x19, 0x01)))
        assertNull(AirPodsParser.parse(null))
    }
}
