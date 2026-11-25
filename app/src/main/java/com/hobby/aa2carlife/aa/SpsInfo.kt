package com.hobby.aa2carlife.aa

/** 从 Annex-B 码流的 SPS 里读 profile_idc / level_idc，用于让占位画面模仿 CarLife 的编码参数。 */
object SpsInfo {

    /** 返回 (profile_idc, level_idc)；解析失败返回 null。 */
    fun parseProfileLevel(config: ByteArray): Pair<Int, Int>? {
        var i = 0
        while (i + 4 < config.size) {
            val three = config[i] == 0.toByte() && config[i + 1] == 0.toByte() && config[i + 2] == 1.toByte()
            val four = i + 5 < config.size &&
                config[i] == 0.toByte() && config[i + 1] == 0.toByte() &&
                config[i + 2] == 0.toByte() && config[i + 3] == 1.toByte()
            val header: Int
            if (four) {
                header = i + 4
            } else if (three) {
                header = i + 3
            } else {
                i++
                continue
            }
            if (header + 4 >= config.size) return null
            val nalType = config[header].toInt() and 0x1F
            if (nalType == 7) {
                val profile = config[header + 1].toInt() and 0xFF
                val level = config[header + 3].toInt() and 0xFF
                return profile to level
            }
            i = header + 4
        }
        return null
    }

    /** H.264 profile_idc → MediaCodecInfo.CodecProfileLevel 常量。 */
    fun toCodecProfile(profileIdc: Int): Int? = when (profileIdc) {
        66 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        77 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileMain
        88 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileExtended
        100 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        110 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
        122 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422
        244 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444
        else -> null
    }

    /** H.264 level_idc → MediaCodecInfo.CodecProfileLevel 常量。 */
    fun toCodecLevel(levelIdc: Int): Int? = when (levelIdc) {
        10 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel1
        11 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel11
        12 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel12
        13 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel13
        20 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel2
        21 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel21
        22 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel22
        30 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel3
        31 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel31
        32 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel32
        40 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel4
        41 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel41
        42 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel42
        50 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel5
        51 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel51
        52 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel52
        else -> null
    }
}
