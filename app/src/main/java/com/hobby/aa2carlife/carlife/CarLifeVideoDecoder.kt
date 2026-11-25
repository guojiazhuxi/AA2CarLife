package com.hobby.aa2carlife.carlife

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.hobby.aa2carlife.diag.RingLog
import java.nio.ByteBuffer

/**
 * CarLife H.264 → SurfaceView 解码器（M1）。
 *
 * CarLife 的视频通道（8240）每包是「12 字节头 + Annex-B 的 H.264 访问单元」，
 * 其中第一个关键帧携带 SPS/PPS。这里在收到 SPS+PPS 后创建 MediaCodec，
 * 之后把每个访问单元原样喂给解码器，渲染到 SurfaceView。
 *
 * 固定横向解码，不再做 KEY_ROTATION。
 */
class CarLifeVideoDecoder(
    private val surface: Surface,
    /** 缓存的 SPS/PPS（Annex-B）：切后台再回来时，后续帧不带参数集也能立即重建解码器。 */
    private val cachedConfig: ByteArray? = null,
) {

    private var codec: MediaCodec? = null

    /** 解码器真实输出尺寸（编码尺寸，不代表显示方向）。 */
    @Volatile
    var width = 0
        private set

    @Volatile
    var height = 0
        private set

    /** 配置/解码失败后停止重试，避免每帧重建成死循环。 */
    @Volatile
    private var failed = false

    private var waitingSpsLogged = false

    @Synchronized
    fun submit(frame: ByteArray, ptsUs: Long) {
        if (failed) return
        try {
            if (codec == null && !configure(frame)) return
            val c = codec ?: return
            val inputIndex = c.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val buffer = c.getInputBuffer(inputIndex) ?: return
                buffer.clear()
                buffer.put(frame)
                c.queueInputBuffer(inputIndex, 0, frame.size, ptsUs, 0)
            }
            drain(c)
        } catch (e: Exception) {
            logException("解码异常", e)
            failed = true
            runCatching { codec?.release() }
            codec = null
        }
    }

    private fun logException(what: String, e: Exception) {
        val msg = e.message?.takeIf { it.isNotBlank() } ?: "(无 message)"
        RingLog.add("VIDEO: $what - ${e.javaClass.simpleName}: $msg")
    }

    private fun configure(frame: ByteArray): Boolean {
        val sps = findNal(frame, 7) ?: cachedConfig?.let { findNal(it, 7) }
        val pps = findNal(frame, 8) ?: cachedConfig?.let { findNal(it, 8) }
        if (sps == null || pps == null) {
            if (!waitingSpsLogged) {
                waitingSpsLogged = true
                RingLog.add("VIDEO: 等待 SPS/PPS（当前帧没有参数集，继续等关键帧）")
            }
            return false
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))

        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        RingLog.add("VIDEO: 找到 SPS(${sps.size}B)/PPS(${pps.size}B)，初始化解码器")
        return true
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = c.dequeueOutputBuffer(info, 0)
            when {
                outIndex >= 0 -> c.releaseOutputBuffer(outIndex, true)
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    width = f.getInteger(MediaFormat.KEY_WIDTH)
                    height = f.getInteger(MediaFormat.KEY_HEIGHT)
                    RingLog.add("VIDEO: 解码输出 ${width}x$height")
                }

                else -> return
            }
        }
    }

    @Synchronized
    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    /** 从 Annex-B 帧里取出指定 NAL 类型（7=SPS / 8=PPS）的完整字节（含起始码）。 */
    private fun findNal(frame: ByteArray, targetType: Int): ByteArray? {
        var i = 0
        while (i + 3 < frame.size) {
            val startLen = startCodeLength(frame, i)
            if (startLen == 0) {
                i++
                continue
            }
            val headerIndex = i + startLen
            if (headerIndex >= frame.size) return null
            val type = frame[headerIndex].toInt() and 0x1F
            var j = headerIndex + 1
            while (j + 2 < frame.size) {
                if (startCodeLength(frame, j) != 0) break
                j++
            }
            val end = if (j + 2 >= frame.size) frame.size else j
            if (type == targetType) return frame.copyOfRange(i, end)
            i = end
        }
        return null
    }

    /** 返回起始码长度（3 或 4），不是起始码返回 0。 */
    private fun startCodeLength(frame: ByteArray, offset: Int): Int {
        if (offset + 3 < frame.size &&
            frame[offset].toInt() == 0 && frame[offset + 1].toInt() == 0 &&
            frame[offset + 2].toInt() == 0 && frame[offset + 3].toInt() == 1
        ) {
            return 4
        }
        if (offset + 2 < frame.size &&
            frame[offset].toInt() == 0 && frame[offset + 1].toInt() == 0 &&
            frame[offset + 2].toInt() == 1
        ) {
            return 3
        }
        return 0
    }
}
