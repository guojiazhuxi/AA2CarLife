package com.hobby.aa2carlife.aa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * 占位画面：用设备硬编码器生成一帧 1280x720 的 H.264 关键帧，中间白色大字 "AA2CARLIFE"。
 *
 * 用途：AA 会话刚建立、CarLife 还没出帧时，先把这一帧（SPS/PPS + IDR）发给车机，
 * 让车机解码器有合法输入、认为投屏正常，不会黑屏等待超时断开。
 * 等 CarLife 真实首帧到达后，再切到真实码流（重发参数集 + 关键帧）。
 */
class PlaceholderVideo(
    private val width: Int = 1280,
    private val height: Int = 720,
    /** 模仿 CarLife 的编码参数（SPS 里的 profile_idc / level_idc，可为 null=编码器默认）。 */
    private val profileIdc: Int? = null,
    private val levelIdc: Int? = null,
) {

    @Volatile
    var config: ByteArray? = null
        private set

    @Volatile
    var idr: ByteArray? = null
        private set

    @Volatile
    var error: String? = null
        private set

    /** 预览 PNG（调试用：导出占位画面给 PC 查看，不影响发送的码流）。 */
    @Volatile
    var previewPng: ByteArray? = null
        private set

    /** 实际用于生成占位帧的参数（回填后写入日志，便于确认是否模仿成功）。 */
    @Volatile
    var usedProfileIdc: Int = -1
        private set

    @Volatile
    var usedLevelIdc: Int = -1
        private set

    /** 生成占位帧（阻塞，建议在后台线程调用一次）。失败时 error 非空、config/idr 为 null。 */
    fun generate() {
        if (config != null && idr != null) return
        var codec: MediaCodec? = null
        try {
            val type = MediaFormat.MIMETYPE_VIDEO_AVC
            val colorFormat = pickByteBufferColorFormat(type)
            if (colorFormat < 0) {
                error = "编码器不支持 ByteBuffer YUV 输入"
                return
            }
            val semiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            val fmt = MediaFormat.createVideoFormat(type, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, 600_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 1)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                runCatching {
                    profileIdc?.let { p ->
                        SpsInfo.toCodecProfile(p)?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
                    }
                    levelIdc?.let { l ->
                        SpsInfo.toCodecLevel(l)?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
                    }
                }
            }
            codec = MediaCodec.createEncoderByType(type)
            usedProfileIdc = profileIdc ?: -1
            usedLevelIdc = levelIdc ?: -1
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val frame = renderFrameYuv(semiPlanar)
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 5_000
            var inputDone = false
            var outputDone = false
            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        buf.put(frame)
                        codec.queueInputBuffer(inIdx, 0, frame.size, 0, 0)
                        inputDone = true
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        val csd0 = outFmt.getByteBuffer("csd-0")?.let { copy(it) }
                        val csd1 = outFmt.getByteBuffer("csd-1")?.let { copy(it) }
                        val c = ByteArray((csd0?.size ?: 0) + (csd1?.size ?: 0))
                        csd0?.copyInto(c, 0, 0, csd0.size)
                        csd1?.copyInto(c, csd0?.size ?: 0, 0, csd1.size)
                        config = c
                    }
                    outIdx >= 0 -> {
                        val out = codec.getOutputBuffer(outIdx)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        out.get(data)
                        codec.releaseOutputBuffer(outIdx, false)
                        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (isKey && data.isNotEmpty()) {
                            idr = data
                            outputDone = true
                        }
                    }
                }
            }
            if (config == null || idr == null) {
                error = "编码器没有输出完整参数集/关键帧"
            }
        } catch (e: Exception) {
            error = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    /** 画一帧“AA2CARLIFE”深色画面并转成 YUV420（semiPlanar=NV12，否则 I420）。 */
    private fun renderFrameYuv(semiPlanar: Boolean): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(18, 26, 36))

        val main = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = height / 6.5f
        }
        val cx = width / 2f
        val cy = height / 2f - (main.descent() + main.ascent()) / 2f
        canvas.drawText("AA2CARLIFE", cx, cy, main)

        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 160, 172)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
            textSize = height / 24f
        }
        canvas.drawText("正在等待 CarLife 连接…", cx, cy + main.textSize * 0.9f + sub.textSize, sub)

        runCatching {
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 90, baos)
            previewPng = baos.toByteArray()
        }

        val ySize = width * height
        val out = ByteArray(ySize * 3 / 2)
        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)

        // Y 平面
        val yOut = out
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
            yOut[i] = yy.coerceIn(0, 255).toByte()
        }

        // 色度平面（2x2 一组）
        val groups = (width / 2) * (height / 2)
        var uIndex = ySize
        var vIndex = ySize + groups
        var group = 0
        var row = 0
        while (row < height) {
            var col = 0
            while (col < width) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val p = pixels[(row + dy) * width + (col + dx)]
                        rSum += (p shr 16) and 0xFF
                        gSum += (p shr 8) and 0xFF
                        bSum += p and 0xFF
                    }
                }
                val r = rSum / 4
                val g = gSum / 4
                val b = bSum / 4
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                val ub = u.coerceIn(0, 255).toByte()
                val vb = v.coerceIn(0, 255).toByte()
                if (semiPlanar) {
                    out[ySize + 2 * group] = ub
                    out[ySize + 2 * group + 1] = vb
                } else {
                    out[uIndex] = ub
                    out[vIndex] = vb
                    uIndex++
                    vIndex++
                }
                group++
                col += 2
            }
            row += 2
        }
        bmp.recycle()
        return out
    }

    private fun pickByteBufferColorFormat(mime: String): Int {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val info = list.codecInfos.firstOrNull { c ->
            c.isEncoder && c.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        } ?: return -1
        val supported = info.getCapabilitiesForType(mime).colorFormats.toSet()
        return when {
            supported.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            supported.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            else -> -1
        }
    }

    private fun copy(b: ByteBuffer): ByteArray {
        val dup = b.duplicate()
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out
    }
}
