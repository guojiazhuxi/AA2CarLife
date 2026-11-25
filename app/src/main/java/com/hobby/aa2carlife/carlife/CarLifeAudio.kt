package com.hobby.aa2carlife.carlife

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.hobby.aa2carlife.diag.RingLog

/**
 * CarLife 音频输出（M1）：媒体（9240）与 TTS/导航播报（9241）各一条 AudioTrack。
 *
 * 协议事实（来自 CarPToCarL 实测）：
 *  - 音频通道是裸字节流：每包 `[u32 len][u32 timestamp][u32 type]` + PCM；
 *  - 媒体：`0x30001` INIT（载荷 CarlifeMusicInit: field1 采样率 / field2 声道配置 /
 *    field3 位深）、`0x30006` NORMAL（PCM）、`0x30003/0x30002` PAUSE/STOP；
 *  - TTS：`0x40001` INIT、`0x40003` NORMAL（PCM）、`0x40002` STOP；
 *  - channelConfig==1 为单声道，其余按立体声处理。
 *
 * 音频路由说明：本 App 用 `USAGE_MEDIA` 播放，Android 会按系统策略输出到当前媒体设备
 * （蓝牙音箱已连接时就走蓝牙，不会走手机扬声器）。
 */
class CarLifeAudio(
    private val audioManager: AudioManager? = null,
) {

    private var mediaTrack: AudioTrack? = null
    private var mediaRate = DEFAULT_RATE
    private var mediaChannels = 2

    private var ttsTrack: AudioTrack? = null
    private var ttsRate = TTS_RATE
    private var ttsChannels = 1

    /** 创建后先不 play，等第一包 PCM 写进去再启动，避免启动 underrun/爆音。 */
    private var mediaPlayPending = false
    private var ttsPlayPending = false

    /** release 后置 true；防止回调线程拿到旧引用又创建新的 AudioTrack。 */
    private var released = false

    // 媒体音频统计（每 ~2 秒打一行，用于确认 PCM 是否在持续流动）
    private var mediaPackets = 0L
    private var mediaBytes = 0L
    private var mediaShortWrites = 0L
    private var lastMediaStatMs = 0L

    @Volatile
    private var mediaPcmLogged = false

    @Volatile
    private var ttsPcmLogged = false

    @Synchronized
    fun onMediaFormat(sampleRate: Int, channels: Int) {
        if (released) return
        val rate = if (sampleRate > 0) sampleRate else DEFAULT_RATE
        val ch = if (channels in 1..2) channels else 2
        if (mediaTrack != null && rate == mediaRate && ch == mediaChannels) return
        releaseMediaTrack()
        mediaRate = rate
        mediaChannels = ch
        mediaTrack = createTrack(
            rate,
            ch,
            usage = AudioAttributes.USAGE_MEDIA,
            contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
        )
        mediaPlayPending = mediaTrack != null
        mediaPackets = 0
        mediaBytes = 0
        mediaShortWrites = 0
        lastMediaStatMs = 0
        RingLog.add("AUDIO: 媒体音频格式 ${rate}Hz ${ch}ch（已创建 AudioTrack）")
    }

    @Synchronized
    fun onMediaPcm(data: ByteArray, length: Int) {
        if (released) return
        if (length <= 0) return
        val track = mediaTrack ?: run {
            onMediaFormat(DEFAULT_RATE, 2)
            return
        }
        if (!mediaPcmLogged) {
            mediaPcmLogged = true
            RingLog.add("AUDIO: ✓ 收到媒体音频首包（${length}B）")
        }
        mediaPackets++
        mediaBytes += length
        write(track, data, length)
        if (mediaPlayPending) {
            mediaPlayPending = false
            runCatching { track.play() }
            RingLog.add("AUDIO: 媒体首包已写入，开始播放（避免启动爆音）")
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (lastMediaStatMs == 0L) lastMediaStatMs = now
        if (now - lastMediaStatMs >= 2000L) {
            val pos = runCatching { track.playbackHeadPosition }.getOrDefault(-1)
            RingLog.add(
                "AUDIO: 媒体 PCM 累计 $mediaPackets 包 / ${mediaBytes / 1024} KB，" +
                    "短写 $mediaShortWrites 次，播放头 $pos，状态 ${track.playState}",
            )
            lastMediaStatMs = now
        }
    }

    @Synchronized
    fun onMediaPause() {
        if (released) return
        runCatching { mediaTrack?.pause() }
        RingLog.add("AUDIO: 媒体暂停（本地 AudioTrack）")
    }

    @Synchronized
    fun onMediaResume() {
        if (released) return
        val track = mediaTrack ?: return
        if (mediaPlayPending) return // 首包还没到，等 onMediaPcm 启动
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { track.play() }.onFailure {
                RingLog.add("AUDIO: 媒体恢复失败 - ${it.message}")
            }
            RingLog.add("AUDIO: 媒体恢复（本地 AudioTrack）")
        }
    }

    @Synchronized
    fun onMediaStop() {
        if (released) return
        val track = mediaTrack ?: return
        runCatching { track.pause() }
        runCatching { track.flush() }
        // 下一包 PCM 到达时重新启动；避免 CarLife STOP 后本地还残留旧声音。
        mediaPlayPending = true
        RingLog.add("AUDIO: 媒体停止（本地 AudioTrack flush）")
    }

    @Synchronized
    fun onTtsStop() {
        if (released) return
        val track = ttsTrack ?: return
        runCatching { track.pause() }
        runCatching { track.flush() }
        ttsPlayPending = true
        RingLog.add("AUDIO: TTS 停止（本地 AudioTrack flush）")
    }

    @Synchronized
    fun onTtsFormat(sampleRate: Int, channels: Int) {
        if (released) return
        val rate = if (sampleRate > 0) sampleRate else TTS_RATE
        val ch = if (channels in 1..2) channels else 1
        if (ttsTrack != null && rate == ttsRate && ch == ttsChannels) return
        releaseTtsTrack()
        ttsRate = rate
        ttsChannels = ch
        ttsTrack = createTrack(
            rate,
            ch,
            usage = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
        )
        ttsPlayPending = ttsTrack != null
        RingLog.add("AUDIO: TTS 音频格式 ${rate}Hz ${ch}ch（已创建 AudioTrack）")
    }

    @Synchronized
    fun onTtsPcm(data: ByteArray, length: Int) {
        if (released) return
        if (length <= 0) return
        val track = ttsTrack ?: run {
            onTtsFormat(TTS_RATE, 1)
            return
        }
        if (!ttsPcmLogged) {
            ttsPcmLogged = true
            RingLog.add("AUDIO: ✓ 收到导航播报音频首包（${length}B，${ttsRate}Hz ${ttsChannels}ch）")
        }
        write(track, data, length)
        if (ttsPlayPending) {
            ttsPlayPending = false
            runCatching { track.play() }
            RingLog.add("AUDIO: TTS 首包已写入，开始播放")
        }
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        releaseMediaTrack()
        releaseTtsTrack()
    }

    private fun write(track: AudioTrack, data: ByteArray, length: Int) {
        var offset = 0
        val deadline = android.os.SystemClock.elapsedRealtime() + WRITE_RETRY_TIMEOUT_MS
        try {
            while (offset < length) {
                val written = track.write(data, offset, length - offset)
                if (written < 0) {
                    RingLog.add("AUDIO: AudioTrack.write 返回 $written")
                    return
                }
                if (written == 0) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) break
                    Thread.sleep(1)
                    continue
                }
                offset += written
                if (offset < length && android.os.SystemClock.elapsedRealtime() >= deadline) break
            }
            if (offset < length) {
                mediaShortWrites++
                RingLog.add("AUDIO: AudioTrack 写入超时，丢弃 ${length - offset}B")
            }
        } catch (e: Exception) {
            RingLog.add("AUDIO: 写 AudioTrack 失败 - ${e.message}")
        }
    }

    private fun createTrack(
        rate: Int,
        channels: Int,
        usage: Int,
        contentType: Int,
    ): AudioTrack? {
        return try {
            val channelMask =
                if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuffer = AudioTrack.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            // 低延迟：只留约 20ms 的 PCM，不再用 0.5~1 秒的大缓冲。
            val pcmBytesPerSec = rate * channels * 2
            val target = (pcmBytesPerSec / 50).coerceAtLeast(1024)
            val bufferSize = if (minBuffer > 0) minBuffer.coerceAtLeast(target) else target
            val attributes = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(contentType)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(channelMask)
                .build()
            fun build(useLowLatency: Boolean): AudioTrack {
                val b = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                if (useLowLatency) {
                    b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                return b.build()
            }
            val track = try {
                build(true)
            } catch (e: Exception) {
                RingLog.add("AUDIO: 低延迟 AudioTrack 失败，回退普通模式 - ${e.message}")
                build(false)
            }
            track.also {
                    val state = it.state
                    RingLog.add("AUDIO: AudioTrack 状态=$state 采样率=$rate 声道=$channels 低延迟缓冲=$bufferSize")
                    preferBuiltinSpeaker(it)
                    // 先不 play：等第一包 PCM 写入后再启动，避免启动 underrun/爆音。
                }
        } catch (e: Exception) {
            RingLog.add("AUDIO: 创建 AudioTrack 失败 - ${e.message}")
            null
        }
    }

    /**
     * 把 AudioTrack 强制路由到内置扬声器。
     *
     * 荣耀系统在某些模式下会把 App 媒体音频路由到 AUDIO_DEVICE_OUT_PROXY 之类的虚拟设备，
     * 导致手机/车机都听不到。这里显式指定内置扬声器后，M1 预览一定能听到声音。
     */
    private fun preferBuiltinSpeaker(track: AudioTrack) {
        val am = audioManager ?: return
        val speaker = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speaker == null) {
            RingLog.add("AUDIO: 未找到内置扬声器，保持系统默认路由")
            return
        }
        val ok = runCatching { track.setPreferredDevice(speaker) }.getOrDefault(false)
        RingLog.add("AUDIO: 指定输出设备 ${speaker.productName}(type=${speaker.type}) 结果=$ok")
    }

    /** 当前所有输出设备的类型列表（会话启动时记录一次，便于排查路由）。 */
    fun logOutputDevices() {
        val am = audioManager ?: return
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .joinToString(", ") { "${it.productName}(type=${it.type})" }
        RingLog.add("AUDIO: 输出设备列表=[$devices]")
    }

    private fun releaseMediaTrack() {
        runCatching { mediaTrack?.stop() }
        runCatching { mediaTrack?.release() }
        mediaTrack = null
        mediaPcmLogged = false
        mediaPlayPending = false
    }

    private fun releaseTtsTrack() {
        runCatching { ttsTrack?.stop() }
        runCatching { ttsTrack?.release() }
        ttsTrack = null
        ttsPcmLogged = false
        ttsPlayPending = false
    }

    companion object {
        private const val DEFAULT_RATE = 48_000
        private const val TTS_RATE = 16_000
        private const val WRITE_RETRY_TIMEOUT_MS = 250L
    }
}
