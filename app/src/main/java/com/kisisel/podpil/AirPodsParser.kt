package com.kisisel.podpil

/**
 * AirPods "Proximity Pairing" reklamını ayrıştırır.
 *
 * Girdi: ScanRecord.getManufacturerSpecificData(0x004C) — üretici kimliği hariç 27 bayt.
 *   [0]    0x07  mesaj tipi (Proximity Pairing)
 *   [1]    0x19  kalan uzunluk (25)
 *   [3..4]       model kodu (ör. 0E20 = AirPods Pro)
 *   [5]          durum; 0x20 biti 0 ise sol/sağ yer değiştirmiş (flip)
 *   [6]          üst nibble / alt nibble = kulaklık pilleri (0-10, 15 = bilinmiyor)
 *   [7]          üst nibble = şarj bitleri, alt nibble = kutu pili
 * Mantık OpenPods'un PodsStatus.java dosyasıyla aynıdır.
 */
object AirPodsParser {
    const val APPLE_ID = 0x004C
    const val TYPE_PROXIMITY = 0x07
    const val PAYLOAD_LENGTH = 0x19
    const val DATA_LENGTH = 27

    fun parse(data: ByteArray?): PodBattery? {
        if (data == null || data.size != DATA_LENGTH) return null
        if (data[0].u() != TYPE_PROXIMITY || data[1].u() != PAYLOAD_LENGTH) return null

        val flip = (data[5].u() and 0x20) == 0
        val pods = data[6].u()
        val upper = pods shr 4
        val lower = pods and 0x0F
        val chargeBits = data[7].u() shr 4

        return PodBattery(
            model = (data[3].u() shl 8) or data[4].u(),
            left = level(if (flip) upper else lower),
            right = level(if (flip) lower else upper),
            case = level(data[7].u() and 0x0F),
            leftCharging = chargeBits and (if (flip) 0b0010 else 0b0001) != 0,
            rightCharging = chargeBits and (if (flip) 0b0001 else 0b0010) != 0,
            caseCharging = chargeBits and 0b0100 != 0,
        )
    }

    /**
     * AirPods pili 10'luk adımlarla yollar: 5 = %50-59 arası. Kullanıcı tercihi (B): aralığın ortasını
     * göster (OpenPods/AndroPods gibi) → 0-9 için x10+5, 10 = %100. 15 (bilinmiyor) ve diğerleri → null.
     */
    private fun level(nibble: Int): Int? = when (nibble) {
        10 -> 100
        in 0..9 -> nibble * 10 + 5
        else -> null
    }

    private fun Byte.u(): Int = toInt() and 0xFF

    fun toHex(data: ByteArray): String = data.joinToString("") { "%02X".format(it) }

    fun modelName(model: Int): String = when (model) {
        0x0220 -> "AirPods (1. nesil)"
        0x0F20 -> "AirPods (2. nesil)"
        0x1320 -> "AirPods (3. nesil)"
        0x0E20 -> "AirPods Pro"
        0x1420, 0x2420 -> "AirPods Pro 2"
        0x2720 -> "AirPods Pro 3"
        else -> "AirPods (model %04X)".format(model)
    }
}

data class PodBattery(
    val model: Int,
    val left: Int?,
    val right: Int?,
    val case: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
)
