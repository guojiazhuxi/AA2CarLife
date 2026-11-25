package com.hobby.aa2carlife.aa

import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import android.content.res.AssetManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.SystemClock
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.AapSession
import net.prototux.aare.aap.PhoneDescription
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.ServiceChannel
import net.prototux.aare.aap.SessionOptions
import net.prototux.aare.aap.Transport
import net.prototux.aare.aap.SocketTransport
import net.prototux.aare.aap.channels.AudioChannel
import net.prototux.aare.aap.channels.BluetoothChannel
import net.prototux.aare.aap.channels.InputChannel
import net.prototux.aare.aap.channels.MediaPlaybackChannel
import net.prototux.aare.aap.channels.NavigationStatusChannel
import net.prototux.aare.aap.channels.PassiveChannel
import net.prototux.aare.aap.channels.PhoneStatusChannel
import net.prototux.aare.aap.channels.SensorChannel
import net.prototux.aare.aap.channels.VideoChannel
import net.prototux.aare.aap.channels.WifiProjectionChannel
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AA 手机端服务（M2/M3）：
 * - 监听 TCP 5277（dev head-unit server 通道），PC 侧 adb forward 后用 AARE HeadUnitClient / Google DHU 连接；
 * - 视频：优先推 CarLife 实时 H.264，无实时帧用测试图兜底；
 * - 音频：CarLife 44.1k 立体声 PCM → 重采样到车机 MEDIA 通道配置（通常 48k 2ch），
 *   按 2048 帧切块、绝对时钟时间戳、200ms 预缓冲后发送；
 * - 触摸：AA 输入事件转发给 CarLife。
 *
 * 协议实现来自 AARE aap 模块（GPL-3.0）。
 */
/** AA TouchEvent 里的一个触点（坐标是 AA 安全区坐标）。 */
data class AaTouchPoint(val id: Int, val x: Int, val y: Int)

/** 车机显示档案：AA 视频配置 + margin 推导出的安全区。 */
data class AaDisplayProfile(
    val width: Int,
    val height: Int,
    val safeWidth: Int,
    val safeHeight: Int,
    val dpi: Int,
)

class AaHeadUnitServer(
    private val assets: AssetManager,
    private val port: Int = 5277,
    onLog: (String) -> Unit,
    onTouch: ((action: Int, x: Int, y: Int, pointerId: Int) -> Unit)? = null,
    onMultiTouch: ((List<AaTouchPoint>) -> Unit)? = null,
) {

    /** 回调可换绑：Activity 销毁/重建时更新，避免因界面销毁而丢掉 AA 会话。 */
    @Volatile
    private var onLog: (String) -> Unit = onLog

    @Volatile
    private var onTouch: ((action: Int, x: Int, y: Int, pointerId: Int) -> Unit)? = onTouch

    /** 双指 MOVE：一次事件里的两根手指一起交给上层。 */
    @Volatile
    private var onMultiTouch: ((List<AaTouchPoint>) -> Unit)? = onMultiTouch

    /** 界面重建后重新绑定回调（AA 服务器可能比 Activity 活得久）。 */
    fun updateHandlers(
        log: (String) -> Unit,
        touch: ((action: Int, x: Int, y: Int, pointerId: Int) -> Unit)?,
        multiTouch: ((List<AaTouchPoint>) -> Unit)? = null,
    ) {
        onLog = log
        onTouch = touch
        onMultiTouch = multiTouch
    }

    companion object {
        /** 当前进程里的 AA 服务器：CarLifeService 在 Activity 不在前台时也能启动会话。 */
        @Volatile
        var current: AaHeadUnitServer? = null
            private set
    }

    init {
        current = this
    }

    /** 车机选定的视频配置尺寸回调（宽, 高），用于让 CarLife 按车机分辨率编码。 */
    var onVideoConfigSelected: ((width: Int, height: Int) -> Unit)? = null

    /** 当前 CarLife 会话实际的编码分辨率；未运行时返回 null（供选择 AA 视频配置时优先匹配）。 */
    var currentStreamSizeProvider: (() -> Pair<Int, Int>?)? = null

    /** 车机支持的最大视频分辨率（用于提示"可以切原生分辨率"）。 */
    @Volatile
    var carPreferredVideoSize: Pair<Int, Int>? = null
        private set

    /**
     * 本机蓝牙地址（大写冒号格式）。
     *
     * 车机的蓝牙服务通道要用它确认"投屏的是哪台手机"：AARE 的注释写明，有些车机
     * 在通过这个通道确认手机身份之前会一直不给投屏画面（HMI 停在"检查手机连接请求"）。
     * Honor/MagicOS 把 BT MAC 全面脱敏，只能由用户填一次。
     */
    @Volatile
    var phoneBluetoothAddress: String? = null

    /**
     * 上报给车机的设备名（原生 AA 报的是手机蓝牙名，如"我的手机"）。
     *
     * 车机的 AA 设备列表会按名字/身份区分手机；一直报 "AA2CarLife" 会多出一台
     * "没有蓝牙的新手机"，HMI 就一直停在"检查手机连接请求"。
     */
    @Volatile
    var phoneDisplayName: String = "AA2CarLife"

    /** 当前会话的蓝牙服务通道（认证数据回调要用它回结果）。 */
    @Volatile
    private var bluetooth: BluetoothChannel? = null

    /**
     * 调试开关：USB 模式下用 App 自己生成的动态测试图（TestPatternVideo）代替 CarLife 画面。
     * 用来把 AA 视频链路和 CarLife/转码链路彻底分开验证。
     */
    @Volatile
    var useTestPattern = false

    @Volatile
    private var testPatternVideo: TestPatternVideo? = null

    /** 当前车机选中的视频配置尺寸（测试图按这个尺寸编码）。 */
    @Volatile
    private var chosenVideoW = 1920
    @Volatile
    private var chosenVideoH = 1080

    /** 车机安全区 [宽, 高, x, y]（配置分辨率减去 margin），用于 98311 的 saveArea。 */
    @Volatile
    var carSafeArea: IntArray? = null
        private set

    @Volatile
    private var running = false

    private var serverSocket: ServerSocket? = null
    private val sessions = CopyOnWriteArrayList<AapSession>()

    @Volatile
    private var video: VideoChannel? = null

    @Volatile
    private var mediaAudio: AudioChannel? = null

    /** 测试图兜底用的帧数据。 */
    @Volatile
    private var units: AaAnnexB.Units? = null

    private val streaming = AtomicBoolean(false)

    @Volatile
    private var realVideoActive = false

    @Volatile
    private var codecConfig: ByteArray? = null

    @Volatile
    private var waitingCodecConfig = false

    /** 已经发给当前车机会话的 SPS/PPS（用于检测 CarLife 分辨率变化后重发）。 */
    private var sentCodecConfig: ByteArray? = null

    /** 最近一个关键帧（Annex-B，含参数集）：车机重连/要关键帧时补发，避免解码器空等 IDR。 */
    @Volatile
    private var lastKeyFrame: ByteArray? = null

    /** 上次补发关键帧的时间，避免车机连续要关键帧时刷屏。 */
    private var lastPrimedAtMs = 0L

    /** AA 视频帧计数（诊断用）：每秒/每 150 帧打一条，确认码流真的在往车机发。 */
    private var framesSent = 0L
    private var keyFramesSent = 0L
    private var framesLogAtMs = SystemClock.elapsedRealtime()
    private var framesLogCount = 0L

    /** CarLife 被用户暂停、但 AAP 会话仍保留（车机侧已收到 STOP）。 */
    @Volatile
    private var videoPausedForCarLife = false

    /** 启动阶段的关键帧看守线程：车机可能只发一次视频焦点，定期补 IDR 让它无论何时就绪都能解码。 */
    private var primeThread: Thread? = null

    /** 占位画面（设备编码的 1280x720 深色 IDR）：CarLife 还没出帧时先让车机正常解码显示。 */
    @Volatile
    private var placeholderConfig: ByteArray? = null

    @Volatile
    private var placeholderIdr: ByteArray? = null

    @Volatile
    private var placeholderActive = false

    fun hasPlaceholder(): Boolean = placeholderConfig != null && placeholderIdr != null

    /** 车机配置尺寸变化时清掉旧占位帧，避免把不匹配尺寸的画面发给车机。 */
    fun clearPlaceholder() {
        placeholderConfig = null
        placeholderIdr = null
        placeholderActive = false
    }

    /** 由界面在后台线程生成后注入（MediaCodec 编码器需要设备环境，不能用假的测试码流）。 */
    fun setPlaceholder(config: ByteArray?, idr: ByteArray?) {
        if (config == null || idr == null) {
            onLog("AA: 占位画面不可用（车机在 CarLife 出帧前仍会短暂黑屏）")
            return
        }
        placeholderConfig = config
        placeholderIdr = idr
        onLog("AA: 占位画面已就绪（SPS/PPS ${config.size}B，关键帧 ${idr.size}B）")
    }

    /** 把占位画面发给车机：先参数集（可选）再 IDR，让车机解码器一直"有画面可解"。 */
    private fun sendPlaceholder(v: VideoChannel, sendConfig: Boolean) {
        val cfg = placeholderConfig ?: return
        val idr = placeholderIdr ?: return
        if (sendConfig) {
            sentCodecConfig = cfg
            runCatching { v.sendCodecConfig(cfg, 0, cfg.size) }
        }
        val buf = ByteArray(10 + idr.size)
        System.arraycopy(idr, 0, buf, 10, idr.size)
        runCatching {
            v.sendFrame(buf, 10, idr.size, (System.nanoTime() - streamStartNs) / 1000, true) {}
        }
        if (!placeholderActive) {
            placeholderActive = true
            onLog("AA: 已发送占位画面（车机不会黑屏等待，CarLife 首帧到达后自动切换）")
        }
    }

    /** USB accessory 会话是否在运行（车机重连会重复派发 attach，用于去重）。 */
    @Volatile
    private var usbSessionRunning = false

    /** 车机 accessory 是否已接入（Activity 不在前台时由 Service 更新）。 */
    @Volatile
    private var accessoryAttached = false

    /** 当前 accessory 的 serial：区分“同一句柄重复上报”和“车机重新接入”。 */
    @Volatile
    private var currentAccessorySerial: String? = null

    /** 当前是否是真实车机（USB）会话：真车不放测试图，避免车机解码器被无效码流卡死。 */
    @Volatile
    private var usbMode = false

    /**
     * 把 AARE 的 AAP 协议日志接进 App 日志。
     *
     * 荣耀机 logcat 是加密的（run-as 也只能看 App 自己的文件），所以 TLS/服务发现/
     * 焦点/未处理控制消息这些协议细节以前完全看不到；实车"车机一直等确认"这类问题
     * 必须靠它定位。
     */
    private val aapLog = object : AapLog {
        override fun d(msg: String) = onLog("AAP·D: $msg")
        override fun i(msg: String) = onLog("AAP: $msg")
        override fun w(msg: String, t: Throwable?) =
            onLog("AAP: WARN " + msg + (t?.let { " - ${it.message}" } ?: ""))

        override fun e(msg: String, t: Throwable?) =
            onLog("AAP: ERROR " + msg + (t?.let { " - ${it.message}" } ?: ""))
    }

    private var streamStartNs = 0L
    private var testThread: Thread? = null
    private val testRunning = AtomicBoolean(false)

    // ---- 音频 ----
    private val audioLock = Any()
    private val audioPending = ArrayDeque<ByteArray>()
    private var audioPendingBytes = 0
    private val audioRunning = AtomicBoolean(false)
    private var audioThread: Thread? = null
    private var audioRate = 48_000
    private var audioChannels = 2

    // CarLife 源 PCM 格式（由 onMediaFormat 宣告）。之前写死 44.1k/立体声，
    // 引擎一旦改出 48k 或单声道，就会变调/串音。
    @Volatile
    private var sourceAudioRate = 44_100
    @Volatile
    private var sourceAudioChannels = 2

    /** CarLife 媒体 PCM 的源格式（CarLifeService 的 onMediaFormat 回调）。 */
    fun setSourceAudioFormat(rate: Int, channels: Int) {
        if (rate > 0) sourceAudioRate = rate
        if (channels in 1..2) sourceAudioChannels = channels
        onLog("AA: CarLife 源音频 " + sourceAudioRate + "Hz " + sourceAudioChannels + "ch → 车机 " + audioRate + "Hz " + audioChannels + "ch")
    }
    private var audioBaseUs = 0L
    private var audioFrames = 0L
    private var audioNextSendNs = 0L
    private var audioPrimed = false

    /** 欠载后的一段时间内加大预缓冲（自适应抖动缓冲）。 */
    @Volatile
    private var audioHoldUntilMs = 0L
    private var audioInCount = 0L
    private var audioNotReadyLogged = false

    @Volatile
    private var lastAudioDataMs = 0L


    fun isRunning(): Boolean = running

    fun start() {
        if (running) return
        val identity = try {
            loadIdentity()
        } catch (e: Exception) {
            onLog("AA: 加载证书失败 - ${e.message}")
            return
        }
        units = try {
            AaAnnexB.accessUnits(assets.open("aap/test.264").readBytes())
        } catch (e: Exception) {
            onLog("AA: 加载测试视频失败 - ${e.message}")
            null
        }
        running = true
        thread(name = "aa-hu-accept", isDaemon = true) {
            try {
                val ss = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                onLog("AA: 台架服务已启动：127.0.0.1:$port（PC 侧先 adb forward tcp:5277 tcp:5277）")
                while (running) {
                    val socket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    onLog("AA: 车机连接来自 ${socket.remoteSocketAddress}")
                    handleSocket(socket, identity)
                }
            } catch (e: Exception) {
                if (running) onLog("AA: 台架服务异常 - ${e.message}")
            }
        }
    }

    fun stop() {
        val caller = Thread.currentThread().stackTrace.getOrNull(3)
            ?.let { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber } ?: "?"
        onLog("AA: stop() 调用来源=$caller")
        running = false
        stopTestPattern()
        streaming.set(false)
        testRunning.set(false)
        stopAudioPump()
        testThread?.interrupt()
        testThread = null
        sessions.forEach { runCatching { it.stop() } }
        sessions.clear()
        video = null
        mediaAudio = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        onLog("AA: 台架服务已停止")
    }

    // ---- CarLife -> AA 视频 ----

    fun sendVideoFrame(frame: ByteArray) {
        // 即使车机还没连上，也要持续缓存 SPS/PPS
        val cfg = AaAnnexB.extractCodecConfig(frame)
        if (cfg != null) codecConfig = cfg

        // 关键帧缓存必须在推流开关检查之前：车机焦点晚到/推流未开时到达的关键帧也要缓存，
        // 否则之后想补发时缓存是空的（之前只有 codecConfig 被缓存，lastKeyFrame 丢了）。
        val key = AaAnnexB.isKeyFrame(frame)
        if (key) lastKeyFrame = frame.copyOf()

        val v = video ?: return
        if (!streaming.get()) return

        if (waitingCodecConfig) {
            val c = codecConfig
            if (c != null) {
                waitingCodecConfig = false
                sentCodecConfig = c
                v.sendCodecConfig(c, 0, c.size)
                onLog("AA: 收到 CarLife 首帧，已发送 codec config（${c.size}B）")
                if (key) sendCachedKeyFrame(v, force = true)
            }
        } else if (cfg != null && !cfg.contentEquals(sentCodecConfig)) {
            // CarLife 重启/分辨率变化时 SPS/PPS 会变，必须给车机重发 codec config，否则解码器按旧尺寸解码
            sentCodecConfig = cfg
            v.sendCodecConfig(cfg, 0, cfg.size)
            onLog("AA: 检测到新的 SPS/PPS（CarLife 分辨率可能变化），已重发 codec config")
            if (key) sendCachedKeyFrame(v, force = true)
        }

        if (!realVideoActive) {
            realVideoActive = true
            if (placeholderActive) {
                placeholderActive = false
                onLog("AA: CarLife 实时画面到达，停止占位画面")
            }
            testRunning.set(false)
            testThread?.interrupt()
            testThread = null
            onLog(if (testPatternVideo != null) "AA: 测试图画面到达（不依赖 CarLife）" else "AA: 切换到 CarLife 实时画面")
        }

        val buf = ByteArray(10 + frame.size)
        System.arraycopy(frame, 0, buf, 10, frame.size)
        v.sendFrame(buf, 10, frame.size, (System.nanoTime() - streamStartNs) / 1000, key) {}
        framesSent++
        framesLogCount++
        if (key) keyFramesSent++
        val now = SystemClock.elapsedRealtime()
        if (now - framesLogAtMs >= 1000L) {
            onLog(
                "AA: 向车机发送 ${framesSent} 帧（本秒 ${framesLogCount} 帧，关键帧累计 $keyFramesSent，" +
                    "缓存参数集=${codecConfig != null}）| 车机回执: ${runCatching { v.report() }.getOrDefault("?")}",
            )
            framesLogAtMs = now
            framesLogCount = 0
        }
    }

    /**
     * 用户点「断开连接」时调用：只暂停视频流（车机收到 STOP），**保留 AAP 会话**。
     * 这样之后重新连接 CarLife 还能在同一个车机会话里继续投屏。
     */
    fun pauseVideoForCarLife() {
        if (!usbMode) return
        val v = video ?: return
        videoPausedForCarLife = true
        streaming.set(false)
        realVideoActive = false
        runCatching { v.pauseVideoForCarLife() }
        onLog("AA: CarLife 已断开，已通知车机暂停视频（AA 会话保持，可重新连接继续投屏）")
    }

    /** CarLife 重新连上后调用：在同一个 AAP 会话里恢复视频流。 */
    fun resumeVideoForCarLifeIfPaused() {
        if (!videoPausedForCarLife) return
        val v = video ?: return
        videoPausedForCarLife = false
        sentCodecConfig = null
        waitingCodecConfig = false
        realVideoActive = false
        streaming.set(true)
        runCatching { v.resumeVideoForCarLife() }
        onLog("AA: CarLife 重新连接，已恢复车机视频流（STOP→START + 重发参数集）")
    }

    /**
     * CarLife 会话结束/失败时清空视频缓存：绝不能把上一个（或不同分辨率）会话的
     * SPS/PPS、关键帧继续发给车机，否则车机会显示旧画面/错位画面。
     */
    fun invalidateVideoCache() {
        codecConfig = null
        lastKeyFrame = null
        sentCodecConfig = null
        waitingCodecConfig = true
        realVideoActive = false
        onLog("AA: 已清空视频缓存（等新的 CarLife 码流）")
    }

    /**
     * 占位/关键帧看守线程：只要真实画面还没到，就每秒补发一次占位 IDR，
     * 让车机始终"有合法视频可解"，不会因为没数据而黑屏或超时断开。
     * 真实画面到达后线程自动退出。
     */
    /** 启动独立测试图（完全不依赖 CarLife）：设备编码器生成 30fps 动态画面送 AA。 */
    private fun startTestPattern() {
        stopTestPattern()
        val w = chosenVideoW.takeIf { it > 0 } ?: 1920
        val h = chosenVideoH.takeIf { it > 0 } ?: 1080
        val pattern = TestPatternVideo(w, h) { frame -> sendVideoFrame(frame) }
        testPatternVideo = pattern
        pattern.start()
        onLog("AA: USB 测试图启动 ${w}x$h@30fps（不依赖 CarLife）")
    }

    private fun stopTestPattern() {
        val p = testPatternVideo ?: return
        testPatternVideo = null
        p.stop()
        onLog("AA: USB 测试图已停止")
    }

    private fun startPrimeWatchdog(v: VideoChannel) {
        if (primeThread?.isAlive == true) return
        primeThread = thread(name = "aa-prime-watchdog", isDaemon = true) {
            while (streaming.get() && usbMode) {
                Thread.sleep(1_000)
                if (!streaming.get() || !usbMode) break
                if (realVideoActive) break
                if (hasPlaceholder()) {
                    sendPlaceholder(v, sendConfig = false)
                } else if (lastKeyFrame != null) {
                    sendCachedKeyFrame(v, force = true)
                }
            }
            onLog("AA: 关键帧看守线程结束（真实画面=${realVideoActive}）")
        }
    }

    /**
     * 给车机补发缓存的关键帧（IDR+参数集）。
     *
     * 车机重连/请求关键帧时，CarLife 的实时流可能只有 P 帧，解码器会一直等 IDR 直到超时断开。
     * 缓存最近一个关键帧，在这种时刻先补发它，让车机立刻有画面。
     */
    private fun sendCachedKeyFrame(v: VideoChannel, force: Boolean) {
        val kf = lastKeyFrame ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPrimedAtMs < 1_200L) return
        lastPrimedAtMs = now
        val buf = ByteArray(10 + kf.size)
        System.arraycopy(kf, 0, buf, 10, kf.size)
        runCatching {
            v.sendFrame(buf, 10, kf.size, (System.nanoTime() - streamStartNs) / 1000, true) {}
        }
        onLog("AA: 已向车机补发缓存关键帧（${kf.size}B，force=$force）")
    }

    // ---- CarLife -> AA 音频 ----

    fun sendAudioPcm(data: ByteArray, length: Int) {
        if (mediaAudio == null) {
            if (!audioNotReadyLogged) {
                audioNotReadyLogged = true
                onLog("AA: 收到 CarLife 音频但媒体通道未就绪（等车机连接后自动开始）")
            }
            return
        }
        audioInCount++
        if (audioInCount == 1L) onLog("AA: 收到第一条 CarLife 音频（${length}B）")

        val converted = try {
            convertPcm(data, length, sourceAudioRate, sourceAudioChannels, audioRate, audioChannels)
        } catch (e: Exception) {
            onLog("AA: 音频重采样异常 - ${e.javaClass.simpleName}: ${e.message}")
            null
        } ?: return
        if (converted.isEmpty()) return

        synchronized(audioLock) {
            audioPending.addLast(converted)
            audioPendingBytes += converted.size
            val cap = chunkBytes(audioRate, audioChannels) * 12
            while (audioPendingBytes > cap && audioPending.isNotEmpty()) {
                audioPendingBytes -= audioPending.removeFirst().size
            }
        }
        lastAudioDataMs = SystemClock.elapsedRealtime()
        ensureAudioPump()
    }

    private fun ensureAudioPump() {
        if (!audioRunning.compareAndSet(false, true)) return
        audioThread = thread(name = "aa-hu-audio", isDaemon = true) {
            onLog("AA: 音频发送线程启动 rate=$audioRate ch=$audioChannels")
            audioBaseUs = SystemClock.elapsedRealtimeNanos() / 1_000
            audioFrames = 0
            audioNextSendNs = 0
            audioPrimed = false
            var acc = ByteArrayOutputStream()
            var loopErrorLogged = false
            while (audioRunning.get()) {
                try {
                    val a = mediaAudio
                    if (a == null) {
                        Thread.sleep(20)
                        continue
                    }
                    val chunkSize = chunkBytes(audioRate, audioChannels)
                    val hold = SystemClock.elapsedRealtime() < audioHoldUntilMs
                    val primeChunks = if (hold) 8 else 4
                    val need = chunkSize * primeChunks
                    val gather = if (!audioPrimed || hold) need else chunkSize
                    synchronized(audioLock) {
                        while (acc.size() < gather && audioPending.isNotEmpty()) {
                            val b = audioPending.removeFirst()
                            audioPendingBytes -= b.size
                            acc.write(b)
                        }
                    }
                    if (!audioPrimed) {
                        if (acc.size() < need &&
                            SystemClock.elapsedRealtime() - lastAudioDataMs < 800
                        ) {
                            Thread.sleep(5)
                            continue
                        }
                        audioPrimed = true
                    }
                    if (hold && audioPrimed && acc.size() < need) {
                        Thread.sleep(5)
                        continue
                    }
                    if (acc.size() < chunkSize) {
                        if (SystemClock.elapsedRealtime() - lastAudioDataMs > 2_000L && a.started) {
                            runCatching { a.stopPlayback() }
                            audioPrimed = false
                            audioNextSendNs = 0
                        }
                        Thread.sleep(10)
                        continue
                    }
                    val data = acc.toByteArray()
                    val chunk = data.copyOfRange(0, chunkSize)
                    acc = ByteArrayOutputStream()
                    if (data.size > chunkSize) acc.write(data, chunkSize, data.size - chunkSize)

                    if (!a.started) {
                        runCatching { a.startPlayback() }
                            .onFailure { onLog("AA: 音频 startPlayback 失败 - ${it.message}") }
                        if (!a.started) {
                            Thread.sleep(20)
                            continue
                        }
                    }
                    var waitCount = 0
                    while (!a.hasRoom && audioRunning.get()) {
                        if (waitCount++ % 200 == 0) onLog("AA: 等待车机音频窗口（hasRoom=false）")
                        Thread.sleep(3)
                    }

                    val frames = chunkSize / (audioChannels * 2)
                    val ts = audioBaseUs + audioFrames * 1_000_000L / audioRate
                    val buf = ByteArray(10 + chunkSize)
                    System.arraycopy(chunk, 0, buf, 10, chunkSize)
                    try {
                        a.sendPcm(buf, 10, chunkSize, ts)
                    } catch (e: Exception) {
                        onLog("AA: 音频 sendPcm 异常 - ${e.javaClass.simpleName}: ${e.message}")
                        Thread.sleep(20)
                        continue
                    }
                    if (audioFrames == 0L) onLog("AA: 已发送第一个音频块（${chunkSize}B）")
                    audioFrames += frames
                    if (audioFrames % (frames * 100) == 0L) onLog("AA: 已发送 $audioFrames 帧音频")
                    val nowNs = System.nanoTime()
                    if (audioNextSendNs == 0L) audioNextSendNs = nowNs
                    audioNextSendNs += frames * 1_000_000_000L / audioRate
                    val waitNs = audioNextSendNs - nowNs
                    if (waitNs > 0) {
                        val ms = (waitNs / 1_000_000L).coerceAtMost(50L)
                        if (ms > 0) Thread.sleep(ms)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (!loopErrorLogged) {
                        loopErrorLogged = true
                        onLog("AA: 音频发送线程异常 - ${e.javaClass.simpleName}: ${e.message}")
                    }
                    Thread.sleep(20)
                }
            }
        }
    }



    private fun stopAudioPump() {
        audioRunning.set(false)
        audioThread?.interrupt()
        audioThread = null
        synchronized(audioLock) {
            audioPending.clear()
            audioPendingBytes = 0
        }
        audioPrimed = false
        audioNextSendNs = 0
    }

    /** Android Auto 音频消息块大小：44.1/48kHz 为 2048 帧，其它 1024 帧。 */
    private fun chunkBytes(rate: Int, channels: Int): Int =
        (if (rate == 44_100 || rate == 48_000) 2048 else 1024) * channels * 2

    /**
     * 16-bit PCM 线性重采样：CarLife 源格式（srcRate/srcCh）→ 车机 AA 配置格式。
     * 源格式来自 onMediaFormat，不再假设 44.1k 立体声。
     */
    private fun convertPcm(
        data: ByteArray,
        length: Int,
        srcRate: Int,
        srcCh: Int,
        targetRate: Int,
        targetCh: Int,
    ): ByteArray? {
        val chIn = srcCh.coerceIn(1, 2)
        val frameBytes = chIn * 2
        val srcFrames = length / frameBytes
        if (srcFrames <= 0 || srcRate <= 0 || targetRate <= 0) return null
        fun sample(frameIdx: Int, ch: Int): Int {
            val off = frameIdx * frameBytes + ch * 2
            if (off + 1 >= length) return 0
            // 先按无符号拼出 16 位，再转成有符号（之前对有符号字节直接 shl 8 后又减 0x10000，导致全部变成 -32768）
            val lo = data[off].toInt() and 0xff
            val hi = data[off + 1].toInt() and 0xff
            val u = lo or (hi shl 8)
            return if (u and 0x8000 != 0) u - 0x10000 else u
        }
        val ratio = targetRate.toDouble() / srcRate
        val dstFrames = (srcFrames * ratio).toInt().coerceAtLeast(1)
        val outCh = targetCh.coerceIn(1, 2)
        val out = ByteArray(dstFrames * outCh * 2)
        var oi = 0
        for (df in 0 until dstFrames) {
            val pos = df / ratio
            val i0 = pos.toInt().coerceIn(0, srcFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(srcFrames - 1)
            val frac = pos - i0
            for (oc in 0 until outCh) {
                val sc = if (chIn == 1) 0 else oc.coerceAtMost(chIn - 1)
                val v = (sample(i0, sc) * (1 - frac) + sample(i1, sc) * frac).toInt()
                val s = v.coerceIn(-32768, 32767)
                out[oi++] = (s and 0xff).toByte()
                out[oi++] = ((s shr 8) and 0xff).toByte()
            }
        }
        return out
    }

    // ---- AAP 会话 ----

    private fun handleSocket(socket: Socket, identity: PhoneIdentity) {
        thread(name = "aa-hu-session", isDaemon = true) {
            try {
                usbMode = false
                startSession(SocketTransport(socket), identity)
            } catch (e: Exception) {
                runCatching { socket.close() }
            }
        }
    }

    /** 用任意传输层（TCP 台架 / USB accessory）启动一路 AAP 会话。 */
    private fun startSession(transport: Transport, identity: PhoneIdentity) {
        try {
            onLog("AA: 上报设备名=" + phoneDisplayName + " 蓝牙地址=" + (phoneBluetoothAddress ?: "未知"))
            val session = AapSession(
                transport = transport,
                identity = identity,
                phone = PhoneDescription(
                    phoneDisplayName,
                    phoneDisplayName,
                    instanceId = phoneBluetoothAddress?.replace(":", "")
                        ?.let { "aa2carlife-$it" } ?: "aa2carlife-bench",
                ),
                callbacks = object : AapSession.Callbacks {
                    override fun onServiceDiscovery(
                        session: AapSession,
                        response: ServiceDiscoveryResponse,
                    ): List<ServiceChannel> {
                        response.channelsList.forEachIndexed { index, s ->
                            val media = if (s.hasMediaSinkService()) {
                                "media(video=" + s.mediaSinkService.videoConfigsCount +
                                    ", audio=" + s.mediaSinkService.audioType + ")"
                            } else {
                                "media=no"
                            }
                            onLog(
                                "AA: 服务[" + index + "] id=" + s.id + " " + media +
                                    " input=" + s.hasInputSourceService() +
                                    " sensor=" + s.hasSensorSourceService() +
                                    " bt=" + s.hasBluetoothService(),
                            )
                            if (s.hasMediaSinkService() &&
                                s.mediaSinkService.videoConfigsCount > 0 &&
                                s.mediaSinkService.audioType != AudioStreamType.AUDIO_STREAM_TELEPHONY
                            ) {
                                onLog("AA: ⚠ 该服务同时带视频和音频，当前实现只开视频通道（audio=" + s.mediaSinkService.audioType + "）")
                            }
                        }
                        val opened = response.channelsList.mapNotNull { s ->
                            when {
                            s.hasMediaSinkService() && s.mediaSinkService.videoConfigsCount > 0 ->
                                VideoChannel(s, videoListener()).also { video = it }

                            s.hasMediaSinkService() &&
                                s.mediaSinkService.audioType != AudioStreamType.AUDIO_STREAM_TELEPHONY -> {
                                val type = s.mediaSinkService.audioType
                                AudioChannel(s, audioListener(type)).also { ch ->
                                    if (type == AudioStreamType.AUDIO_STREAM_MEDIA) {
                                        mediaAudio = ch
                                    }
                                }
                            }

                            s.hasBluetoothService() -> {
                                // 车机的蓝牙服务通道：官方手机在这里用"本机蓝牙地址 + 配对请求"
                                // 自报身份。没开这个通道时，MIB3 会一直停在"请注意您的移动设备上
                                // 的连接请求"而不切投屏（AARE 源码的原话）。
                                val addr = phoneBluetoothAddress?.takeIf { it.isNotBlank() }
                                if (addr == null) {
                                    onLog("AA: ⚠ 已打开蓝牙服务通道，但本机蓝牙地址未知——车机可能不投屏")
                                } else {
                                    onLog("AA: 蓝牙身份上报使用本机地址 $addr")
                                }
                                BluetoothChannel(s, addr, bluetoothListener()).also { bluetooth = it }
                            }

                            s.hasInputSourceService() -> InputChannel(s, inputListener())

                            s.hasSensorSourceService() -> SensorChannel(
                                s,
                                object : SensorChannel.Listener {
                                    override fun onSensors(batch: SensorBatch) = Unit
                                },
                            )

                            // 原生 AA 会把车机列出的服务全部打开；AARE 只开自己用的那几个。
                            // vehicles.json 的 openServices 就是为"某个通道没开→车机不投屏"
                            // 准备的口子。这里把其余服务也打开（不在这上面收发业务数据）。
                            s.hasMediaPlaybackService() -> MediaPlaybackChannel(s)

                            s.hasPhoneStatusService() -> PhoneStatusChannel(s)

                            s.hasNavigationStatusService() -> NavigationStatusChannel(s)

                            s.hasMediaBrowserService() -> PassiveChannel(s, "mediabrowser")

                            s.hasGenericNotificationService() -> PassiveChannel(s, "notifications")

                            s.hasWifiProjectionService() -> WifiProjectionChannel(s)

                            s.hasVendorExtensionService() -> PassiveChannel(s, "vendor")

                                else -> null
                            }
                        }
                        if (mediaAudio == null) {
                            onLog("AA: ⚠ 未找到 AUDIO_STREAM_MEDIA 通道；音乐不会发到 AA，车机音量可能只能控制导航")
                        }
                        return opened
                    }

                    override fun onRunning(session: AapSession) {
                        onLog("AA: 会话就绪，车机版本=${session.headUnitVersion}")
                        runCatching {
                            session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN)
                        }
                    }

                    override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
                        onLog("AA: 会话结束（${session.closedDuring}）- $reason ${error ?: ""}")
                        stopTestPattern()
                        streaming.set(false)
                        testRunning.set(false)
                        stopAudioPump()
                        mediaAudio = null
                        sentCodecConfig = null
                        usbSessionRunning = false
                        videoPausedForCarLife = false
                        realVideoActive = false
                        placeholderActive = false
                        sessions.remove(session)
                    }
                },
                log = aapLog,
                // USB 下官方手机不主动发 ping，只回应车机的；协议版本按车机自己的版本回。
                options = SessionOptions(sendPings = false, echoHeadUnitVersion = true),
            )
            sessions.add(session)
            session.start()
        } catch (e: Exception) {
            onLog("AA: 会话建立失败 - ${e.message}")
            runCatching { transport.close() }
        }
    }

    /** M4：车机通过 USB AOA 接上手机后，用 accessory 通道启动 AAP 会话。 */
    fun startAccessorySession(manager: UsbManager, accessory: UsbAccessory) {
        val serial = accessory.serial
        // Activity 的 intent 和 Service 的广播可能同时到达：同一个 accessory 句柄只启动一次。
        if (usbSessionRunning && serial != null && serial == currentAccessorySerial) {
            onLog("USB: 同一 accessory 重复接入，忽略（会话已在跑）")
            accessoryAttached = true
            return
        }
        // 车机在 AOA 切换后经常会重复派发 attach（先旧句柄、再新句柄），
        // 句柄不同则必须先关掉旧会话，否则两个会话抢同一个 accessory 会导致 EIO/ENODEV。
        if (usbSessionRunning) {
            onLog("USB: 收到新的 accessory 句柄，先关闭旧会话再重连")
            stopSessions()
        }
        val identity = try {
            loadIdentity()
        } catch (e: Exception) {
            onLog("AA: 加载证书失败，无法启动 USB 会话 - ${e.message}")
            return
        }
        try {
            val transport = UsbAccessoryTransport(manager, accessory)
            usbSessionRunning = true
            usbMode = true
            accessoryAttached = true
            currentAccessorySerial = serial
            onLog("AA: USB accessory 已连接：${transport.name}（开始 AAP 会话）")
            thread(name = "aa-hu-usb", isDaemon = true) {
                startSession(transport, identity)
            }
        } catch (e: Exception) {
            usbSessionRunning = false
            onLog("AA: USB accessory 打开失败 - ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 停止所有 AAP 会话（USB accessory 断开或用户主动停止时调用）。 */
    fun stopSessions() {
        val caller = Thread.currentThread().stackTrace.getOrNull(3)
            ?.let { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber } ?: "?"
        onLog("AA: stopSessions() 调用来源=$caller")
        stopTestPattern()
        streaming.set(false)
        testRunning.set(false)
        stopAudioPump()
        usbSessionRunning = false
        accessoryAttached = false
        currentAccessorySerial = null
        sessions.forEach { runCatching { it.stop() } }
        sessions.clear()
        video = null
        mediaAudio = null
        onLog("AA: 所有 AAP 会话已停止")
    }

    fun isUsbSessionRunning(): Boolean = usbSessionRunning

    fun isAccessoryAttached(): Boolean = accessoryAttached

    /** 上次记住的车机显示档案（由 App 侧持久化，服务器不依赖 Android Context）。 */
    @Volatile
    var loadDisplayProfile: (() -> AaDisplayProfile?)? = null

    /** 记住本次车机显示档案（App 侧写盘，值未变时可忽略）。 */
    @Volatile
    var saveDisplayProfile: ((AaDisplayProfile) -> Unit)? = null

    /** 分辨率首次学习/与记忆不一致时，提示用户重新连接一次。 */
    @Volatile
    var onReconnectSuggested: ((String) -> Unit)? = null

    /** 本次车机显示档案的 key（由视频配置列表+margin 签名得到，用于按车区分记忆）。 */
    @Volatile
    var displayProfileKey: String = ""

    private fun videoListener(): VideoChannel.Listener = object : VideoChannel.Listener {
        override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>): Int {
            configs.forEachIndexed { i, c ->
                onLog(
                    "AA: 视频配置 $i: ${c.codecResolution} ${c.frameRate}fps dpi=${c.density} " +
                        "margin=${c.widthMargin}x${c.heightMargin} " +
                        "aspect=${c.pixelAspectRatioE4} uiConfig=${c.hasUiConfig()}",
                )
            }
            val sizes = configs.map { configSize(it) }
            // 用本次车机提供的配置列表 + margin + dpi 做一个稳定签名，按车区分记忆。
            displayProfileKey = configs.joinToString("|") { c ->
                val (cw, ch) = configSize(c)
                "$cw" + "x" + ch + "-" + c.widthMargin + "x" + c.heightMargin + "-" + c.density
            }.hashCode().toUInt().toString(16)
            onLog("AA: 车机显示档案 key=$displayProfileKey")
            carPreferredVideoSize = sizes.maxByOrNull { it.first.toLong() * it.second }
            // 1) CarLife 已在跑：选与当前码流一致的配置（否则码流/配置不符）
            // 2) CarLife 未跑：优先 1280x720 —— CarLife 的 1080p 实际编码为 1920x1088（多 8 行），
            //    与车机 1920x1080 配置不一致会被车机拒绝（实测几秒内 EIO）；720 正好 16 对齐。
            val current = runCatching { currentStreamSizeProvider?.invoke() }.getOrNull()
            val learned = runCatching { loadDisplayProfile?.invoke() }.getOrNull()
            var index = if (current != null) configs.indices.firstOrNull { sizes[it] == current } ?: -1 else -1
            // 2) 用上次记住的车机分辨率：本次配置列表里能对上就直接复用（和 CarPToCarL 的
            //    CarCanvasMemory 同一思路；首次连一台新车只能自动选，第二次起才能命中）。
            var learnedHit = false
            if (index < 0 && learned != null) {
                index = configs.indices.firstOrNull { sizes[it] == (learned.width to learned.height) } ?: -1
                learnedHit = index >= 0
                if (learnedHit) {
                    onLog("AA: 命中已记住的车机分辨率 ${learned.width}x${learned.height}（安全区 ${learned.safeWidth}x${learned.safeHeight}）")
                }
            }
            // 首次连接没有记忆：优先车机 preferred，再按 1080/720/480 回落。
            if (index < 0) index = preferred.firstOrNull()?.takeIf { it in configs.indices } ?: -1
            if (index < 0) index = configs.indices.firstOrNull { sizes[it] == (1920 to 1080) } ?: -1
            if (index < 0) index = configs.indices.firstOrNull { sizes[it] == (1280 to 720) } ?: -1
            if (index < 0) index = configs.indices.firstOrNull { sizes[it] == (800 to 480) } ?: -1
            if (index < 0) index = preferred.firstOrNull() ?: 0
            if (!learnedHit && learned != null) {
                onLog("AA: 上次记住的 ${learned.width}x${learned.height} 不在本次配置列表，重新学习")
            }
            index = index.coerceIn(0, configs.size - 1)
            val (w, h) = sizes[index]
            val chosen = configs[index]
            val safeW = (w - chosen.widthMargin).takeIf { it in 1 until w } ?: w
            val safeH = (h - chosen.heightMargin).takeIf { it in 1 until h } ?: h
            carSafeArea = intArrayOf(safeW, safeH, 0, 0)
            chosenVideoW = w
            chosenVideoH = h
            onLog("AA: 车机安全区 ${safeW}x$safeH（margin=${chosen.widthMargin}x${chosen.heightMargin}）")
            onLog(
                "AA: 选择视频配置 $index: ${configs[index].codecResolution}（$w x $h，" +
                    if (current != null) "匹配当前 CarLife ${current.first}x${current.second}）" else "CarLife 未启动，按车机优先）",
            )
            val profile = AaDisplayProfile(w, h, safeW, safeH, chosen.density)
            runCatching { saveDisplayProfile?.invoke(profile) }
            onVideoConfigSelected?.invoke(w, h)
            // 首次学习或与上次不一致：当前这一轮可能已经按旧尺寸建流，提示重新连接一次
            if (current == null && !learnedHit) {
                val msg = if (learned == null) {
                    "已学习车机分辨率 " + w + "x" + h + "（安全区 " + safeW + "x" + safeH +
                        "）。请拔插一次 USB 重新连接，让投屏按该分辨率建立。"
                } else {
                    "车机分辨率与上次不同，已更新为 " + w + "x" + h + "（安全区 " + safeW + "x" + safeH +
                        "）。请拔插一次 USB 重新连接以生效。"
                }
                onLog("AA: " + msg)
                runCatching { onReconnectSuggested?.invoke(msg) }
            }
            return index
        }

        override fun onVideoStart(index: Int, config: VideoConfiguration) {
            onLog("AA: 视频开始 index=$index ${config.codecResolution}")
            streamStartNs = System.nanoTime()
            sentCodecConfig = null
            lastPrimedAtMs = 0L
            videoPausedForCarLife = false
            if (usbMode && useTestPattern) {
                // 测试图模式：绝不把上一次 CarLife 会话缓存的参数集/关键帧发给车机
                codecConfig = null
                lastKeyFrame = null
            }
            val cfg = codecConfig
            if (cfg != null) {
                sentCodecConfig = cfg
                video?.sendCodecConfig(cfg, 0, cfg.size)
                onLog("AA: 已发送缓存的 CarLife codec config（${cfg.size}B）")
                // 立刻补一个缓存关键帧：车机重连时实时流可能只有 P 帧，会一直等 IDR 到超时断开
                video?.let { sendCachedKeyFrame(it, force = true) }
            } else {
                waitingCodecConfig = true
                if (usbMode && useTestPattern) {
                    onLog("AA: USB 测试图模式（视频通道就绪，焦点后开始送动态测试图）")
                } else if (usbMode) {
                    val v = video
                    if (v != null && hasPlaceholder()) {
                        // 先发占位画面：车机认为投屏正常，不会黑屏等到超时断开
                        waitingCodecConfig = false
                        sendPlaceholder(v, sendConfig = true)
                    } else {
                        onLog("AA: USB 真车会话：等待 CarLife 首帧（先黑屏，收到后自动出画面，不推测试图）")
                    }
                } else {
                    // 台架：用手上测试码流的 SPS/PPS 先初始化车机，让测试图是合法 H.264
                    val testCfg = units?.config
                    if (testCfg != null && testCfg.isNotEmpty()) {
                        sentCodecConfig = testCfg
                        video?.sendCodecConfig(testCfg, 0, testCfg.size)
                        onLog("AA: 用测试码流的 SPS/PPS 初始化车机（等 CarLife 首帧）")
                    } else {
                        onLog("AA: 等待 CarLife 的 SPS/PPS（先用测试图）")
                    }
                }
            }
            if (usbMode && !useTestPattern) video?.let { startPrimeWatchdog(it) }
        }

        override fun onVideoFocus(projected: Boolean) {
            onLog("AA: 视频焦点 ${if (projected) "PROJECTED（开始推流）" else "NATIVE（停止推流）"}")
            if (!projected) {
                streaming.set(false)
                stopTestPattern()
                stopStream()
                return
            }
            if (usbMode) {
                realVideoActive = false
                streaming.set(true)
                if (useTestPattern) {
                    // 调试：完全不碰 CarLife，用 App 自己编码的动态测试图验证 AA 视频链路。
                    startTestPattern()
                    return
                }
                // 真车：只打开推流开关，等 CarLife 真实首帧（含 SPS/PPS/IDR）到达后直接出画面。
                onLog("AA: USB 真车：推流开关已打开，等待 CarLife 实时帧")
                video?.let {
                    if (!realVideoActive && hasPlaceholder() && lastKeyFrame == null) {
                        sendPlaceholder(it, sendConfig = true)
                    } else {
                        sendCachedKeyFrame(it, force = true)
                    }
                    startPrimeWatchdog(it)
                }
            } else {
                startStream()
            }
        }

        override fun onUiConfig(config: UiConfig, applied: () -> Unit) {
            onLog(
                "AA: 车机 UI 配置更新（显示区/边距）= " +
                    config.toString().replace('\n', ' ').take(400),
            )
            applied()
        }

        override fun onKeyFrameNeeded() {
            onLog("AA: 车机要求关键帧，补发缓存关键帧")
            video?.let {
                if (!realVideoActive && hasPlaceholder() && lastKeyFrame == null) {
                    sendPlaceholder(it, sendConfig = false)
                } else {
                    sendCachedKeyFrame(it, force = false)
                }
            }
        }
    }

    /** 把 AA 的视频配置枚举换算成实际像素尺寸（解析不出来就当 1280x720）。 */
    private fun configSize(config: VideoConfiguration): Pair<Int, Int> {
        val name = runCatching { config.codecResolution.name }.getOrDefault("")
        val m = Regex("VIDEO_(\\d+)x(\\d+)").find(name) ?: return 1280 to 720
        return (m.groupValues[1].toIntOrNull() ?: 1280) to (m.groupValues[2].toIntOrNull() ?: 720)
    }

    private fun audioListener(type: AudioStreamType): AudioChannel.Listener =
        object : AudioChannel.Listener {
            override fun onAudioReady(channel: AudioChannel, config: AudioConfiguration) {
                onLog("AA: 音频通道就绪 type=$type ${config.samplingRate}Hz ${config.numberOfChannels}ch")
                runCatching { channel.startPlayback() }
                    .onFailure { onLog("AA: 通道 startPlayback 失败（$type）- ${it.message}") }
                if (type == AudioStreamType.AUDIO_STREAM_MEDIA) {
                    audioRate = if (config.samplingRate > 0) config.samplingRate else 48_000
                    audioChannels = if (config.numberOfChannels in 1..2) config.numberOfChannels else 2
                }
            }

            override fun onUnderflow(channel: AudioChannel) {
                if (type == AudioStreamType.AUDIO_STREAM_MEDIA) {
                    val now = SystemClock.elapsedRealtime()
                    if (now > audioHoldUntilMs) {
                        audioHoldUntilMs = now + 30_000L
                        audioPrimed = false
                        onLog("AA: 检测到音频欠载，预缓冲临时加大到 8 块（约 341ms，保持 30 秒）")
                    }
                } else {
                    onLog("AA: 音频欠载 type=$type")
                }
            }
        }

    /** 蓝牙服务通道的回调：车机对"手机身份上报"的回答、以及配对验证码。 */
    private fun bluetoothListener(): BluetoothChannel.Listener = object : BluetoothChannel.Listener {
        override fun onPairingResponse(alreadyPaired: Boolean, ok: Boolean) {
            onLog("AA: 蓝牙身份上报结果 alreadyPaired=$alreadyPaired ok=$ok")
        }

        override fun onAuthenticationData(authData: String) {
            // 手机已经和车机配对过；这里把车机给出的验证码回成功，让车机继续起投屏。
            // 真正的 BT 配对由系统蓝牙完成，这个回包只影响车机是否继续。
            onLog("AA: 车机蓝牙配对验证码=$authData，回 STATUS_SUCCESS")
            bluetooth?.sendAuthenticationResult(MessageStatus.STATUS_SUCCESS)
        }
    }

    private fun inputListener(): InputChannel.Listener = object : InputChannel.Listener {
        override fun onInput(report: InputReport) {
            if (report.hasTouchEvent()) {
                val t = report.touchEvent
                val action = t.action.number
                val pointers = t.pointerDataList
                if (pointers.isNotEmpty()) {
                    // 双指 MOVE：一次事件里可能有 2 个触点，整包交给上层，
                    // 由桥接按你预览里的 CPTCL 状态机发一条 0x68004（而不是两根手指各发一条）。
                    if (action == 2 && pointers.size >= 2 && onMultiTouch != null) {
                        onMultiTouch?.invoke(
                            pointers.take(2).map { pd ->
                                AaTouchPoint(pd.pointerId.toInt(), pd.x.toInt(), pd.y.toInt())
                            },
                        )
                        return
                    }
                    // 按下/抬起优先用 actionIndex（AA 的 TouchEvent 自带），
                    // 否则无法判断抬起的是第一指还是第二指。
                    val actionIndex = if (t.hasActionIndex()) t.actionIndex else -1
                    val p = when {
                        actionIndex in 0 until pointers.size -> pointers[actionIndex]
                        action == 5 || action == 6 ->
                            pointers.firstOrNull { it.pointerId != 0 } ?: pointers[0]
                        else -> pointers.firstOrNull { it.pointerId == 0 } ?: pointers[0]
                    }
                    val x = p.x.toInt()
                    val y = p.y.toInt()
                    val id = p.pointerId.toInt()
                    onLog("AA: 触摸 action=$action x=$x y=$y id=$id")
                    onTouch?.invoke(action, x, y, id)
                }
            }
            if (report.hasKeyEvent()) {
                val keys = report.keyEvent.keysList.joinToString(",") { "${it.keycode}:${it.down}" }
                onLog("AA: 按键 $keys")
            }
        }
    }

    private fun startStream() {
        if (!streaming.compareAndSet(false, true)) return
        realVideoActive = false
        val u = units ?: return
        if (u.frames.isEmpty()) return
        testRunning.set(true)
        testThread = thread(name = "aa-hu-testvideo", isDaemon = true) {
            var i = 0
            val start = System.nanoTime()
            while (testRunning.get() && streaming.get()) {
                val v = video ?: break
                val f = u.frames[i % u.frames.size]
                val buf = ByteArray(10 + f.data.size)
                System.arraycopy(f.data, 0, buf, 10, f.data.size)
                v.sendFrame(buf, 10, f.data.size, (System.nanoTime() - start) / 1000, f.key) {}
                i++
                if (i % 150 == 0) onLog("AA: 测试图已发送 $i 帧（等 CarLife 实时帧）")
                try {
                    Thread.sleep(33)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun stopStream() {
        streaming.set(false)
        testRunning.set(false)
        testThread?.interrupt()
        testThread = null
    }

    private fun loadIdentity(): PhoneIdentity {
        val cf = CertificateFactory.getInstance("X.509")
        val certs = assets.open("aap/phone_chain.pem").use {
            cf.generateCertificates(it)
        }.map { it as X509Certificate }
        val pem = assets.open("aap/phone_key.pem").bufferedReader().use { it.readText() }
        val der = Base64.getMimeDecoder().decode(
            pem.lines().filter { !it.startsWith("-----") }.joinToString(""),
        )
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        return PhoneIdentity(certs.first(), key, certs.drop(1))
    }
}
