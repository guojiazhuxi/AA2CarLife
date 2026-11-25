package com.hobby.aa2carlife.aa

/**
 * 把 Annex-B H.264 流拆成 SPS/PPS 配置 + 访问单元（一个访问单元 = 一帧）。
 * 逻辑取自 AARE 的 PhoneSim 测试工具（GPL-3.0），用于 M2 台架推测试图。
 */
object AaAnnexB {

    class Frame(val data: ByteArray, val key: Boolean)

    class Units(val config: ByteArray, val frames: List<Frame>)

    fun accessUnits(bytes: ByteArray): Units {
        val nals = split(bytes)
        val config = java.io.ByteArrayOutputStream()
        val frames = ArrayList<Frame>()
        var pending = java.io.ByteArrayOutputStream()
        for (nal in nals) {
            if (nal.size < 4) continue
            val type = nal[nal.indexOfStartEnd()].toInt() and 0x1f
            when (type) {
                7, 8 -> if (frames.isEmpty()) config.write(nal) else pending.write(nal)
                1, 5 -> {
                    pending.write(nal)
                    frames += Frame(pending.toByteArray(), type == 5)
                    pending = java.io.ByteArrayOutputStream()
                }
                else -> pending.write(nal)
            }
        }
        return Units(config.toByteArray(), frames)
    }

    /** 从一帧里提取 SPS(7)+PPS(8) 的 Annex-B 字节（用于 AA 视频通道的 codec config）。 */
    fun extractCodecConfig(frame: ByteArray): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var found = false
        for (nal in split(frame)) {
            if (nal.size < 4) continue
            val type = nal[nal.indexOfStartEnd()].toInt() and 0x1f
            if (type == 7 || type == 8) {
                out.write(nal)
                found = true
            }
        }
        return if (found) out.toByteArray() else null
    }

    /** 是否包含 IDR(5) 或 SPS/PPS —— 可当作关键帧发给车机。 */
    fun isKeyFrame(frame: ByteArray): Boolean {
        for (nal in split(frame)) {
            if (nal.size < 4) continue
            when (nal[nal.indexOfStartEnd()].toInt() and 0x1f) {
                5, 7, 8 -> return true
            }
        }
        return false
    }

    private fun ByteArray.indexOfStartEnd(): Int = if (this[2].toInt() == 1) 3 else 4

    private fun split(b: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 <= b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                starts += if (i > 0 && b[i - 1].toInt() == 0) i - 1 else i
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexed { k, s ->
            b.copyOfRange(s, if (k + 1 < starts.size) starts[k + 1] else b.size)
        }
    }
}
