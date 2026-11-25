package com.hobby.aa2carlife.carlife

import com.hobby.aa2carlife.diag.RingLog
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarLife 车机端（HU）最小客户端（M1）。
 *
 * 流程（来自 CarPToCarL 的实测配方）：
 *  1. 连接本机 7 个端口（7240 主 / 8240 视频 / 9240 媒体 / 9241 TTS / 9242 VR / 9340 触控 / 9440 data）；
 *  2. 反复发 98305（协议 3.0）直到收到 65538 matchStatus==1；
 *  3. 收到 65540 手机信息 → 回 98307 HU_INFO → 主动推 98386 特征配置 → 主动推 98311 视频初始化；
 *  4. 收到 65544 → 发 98313 START；8240 开始推 H.264；
 *  5. 1 秒一次心跳 131074；
 *  6. 触控走 9340（控制帧格式 8B 头），双指必须用 0x68001 复合动作。
 *
 * 注意：M1 只做画面 + 触控；音频/焦点/重连策略后续里程碑再补。
 */
class CarLifeHuClient(
    private val host: String = "127.0.0.1",
    private var displayWidth: Int = 1280,
    private var displayHeight: Int = 720,
    private val fps: Int = 30,
    /** 98311 字段 9：请引擎按车机比例等比适配（不拉伸）。 */
    private var needAdapterScreen: Boolean = true,
    /** 98311 字段 4~7：车机安全区 [宽, 高, x, y]。 */
    @Volatile
    private var saveArea: IntArray? = null,
    private val onVideoFrame: (frame: ByteArray, ptsUs: Long) -> Unit,
    private val onState: (String) -> Unit = {},
    private val onMediaFormat: (sampleRate: Int, channels: Int) -> Unit = { _, _ -> },
    private val onMediaPcm: (data: ByteArray, length: Int) -> Unit = { _, _ -> },
    private val onTtsFormat: (sampleRate: Int, channels: Int) -> Unit = { _, _ -> },
    private val onTtsPcm: (data: ByteArray, length: Int) -> Unit = { _, _ -> },
    private val onMediaPause: () -> Unit = {},
    private val onMediaResume: () -> Unit = {},
    private val onMediaStop: () -> Unit = {},
    private val onTtsStop: () -> Unit = {},
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 800
        private const val MAX_VIDEO_FRAME = 8 * 1024 * 1024
        private const val MAX_AUDIO_PACKET = 4 * 1024 * 1024
        private const val MAX_CMD_PACKET = 16 * 1024
        private const val VERSION_WAIT_MS = 15_000L
        private const val FEATURE_ACK_WAIT_MS = 1_500L
    }

    private val sockets = HashMap<Int, Socket>()
    private var cmdOut: OutputStream? = null
    private var touchOut: OutputStream? = null

    private val running = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)

    @Volatile
    private var versionMatched = false

    @Volatile
    private var featureAcked = false

    @Volatile
    private var firstFrameLogged = false

    private val writeLock = Any()
    private val touchLock = Any()
    private val unknownIds = HashSet<Int>()
    private val touchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "carlife-touch").apply { isDaemon = true }
    }

    /** 界面触摸映射用的尺寸（默认 = 申请的分辨率；后续可由 SPS/回包更新）。 */
    @Volatile
    var touchWidth = displayWidth
        private set

    @Volatile
    var touchHeight = displayHeight
        private set

    fun isRunning(): Boolean = running.get()

    /** 启动并同步完成版本协商（阻塞，需在后台线程调用）。 */
    fun start(): Boolean {
        if (running.get()) return true

        RingLog.add("CARLIFE: 连接本机车机端口 $host …")
        for (port in CarLifeProtocol.ALL_PORTS) {
            try {
                sockets[port] = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                RingLog.add("CARLIFE: 端口 $port 连接失败 - ${e.message}")
            }
        }

        val cmd = sockets[CarLifeProtocol.PORT_CMD]
        if (cmd == null) {
            RingLog.add("CARLIFE: 控制端口 7240 未连通——荣耀车联可能不在「无线连接/正在连接」态")
            closeAll()
            end("控制端口未连通")
            return false
        }

        cmdOut = cmd.getOutputStream()
        touchOut = sockets[CarLifeProtocol.PORT_TOUCH]?.getOutputStream()
        running.set(true)

        startThread("carlife-cmd") { cmdReader(cmd.getInputStream()) }
        sockets[CarLifeProtocol.PORT_VIDEO]?.let { video ->
            startThread("carlife-video") { videoReader(video.getInputStream()) }
        }
        sockets[CarLifeProtocol.PORT_MEDIA]?.let { media ->
            startThread("carlife-media") { audioReader(media.getInputStream(), isTts = false) }
        }
        sockets[CarLifeProtocol.PORT_TTS]?.let { tts ->
            startThread("carlife-tts") { audioReader(tts.getInputStream(), isTts = true) }
        }
        startThread("carlife-heartbeat") { heartbeatLoop() }

        return negotiateVersion()
    }

    fun stop() {
        running.set(false)
        closeAll()
        touchExecutor.shutdown()
    }

    // ---- 握手 ----

    private fun negotiateVersion(): Boolean {
        val deadline = System.currentTimeMillis() + VERSION_WAIT_MS
        while (running.get() && !versionMatched && System.currentTimeMillis() < deadline) {
            send(CarLifeProtocol.HU_PROTOCOL_VERSION, CarLifeProtocol.protocolVersion())
            var waited = 0L
            while (waited < 1_500 && running.get() && !versionMatched) {
                Thread.sleep(100)
                waited += 100
            }
        }
        if (!versionMatched) {
            RingLog.add("CARLIFE: 15s 内未收到版本匹配，放弃本次连接")
            stop()
            end("版本协商超时")
            return false
        }
        RingLog.add("CARLIFE: 版本匹配成功（协议 3.0）")
        return true
    }

    private fun cmdReader(input: InputStream) {
        val data = DataInputStream(input)
        try {
            while (running.get()) {
                val header = ByteArray(8)
                data.readFully(header)
                val length = CarLifeProtocol.readU16(header, 0)
                val serviceType = CarLifeProtocol.readU32(header, 4)
                if (length > MAX_CMD_PACKET) {
                    RingLog.add("CARLIFE: 控制包长度异常（$length），放弃控制通道")
                    break
                }
                val payload = ByteArray(length)
                if (length > 0) data.readFully(payload)
                handleMessage(serviceType, payload)
            }
        } catch (e: Exception) {
            if (running.get()) RingLog.add("CARLIFE: 控制通道断开 - ${e.message}")
        } finally {
            val unexpected = running.get()
            stop()
            if (unexpected) end("控制通道断开")
        }
    }

    private fun handleMessage(serviceType: Int, payload: ByteArray) {
        when (serviceType) {
            CarLifeProtocol.MD_VERSION_MATCH -> {
                versionMatched = true
                send(CarLifeProtocol.HU_STATISTICS, CarLifeProtocol.statisticsInfo())
            }

            CarLifeProtocol.MD_INFO -> {
                RingLog.add("CARLIFE: 收到手机设备信息（${payload.size}B）")
                send(
                    CarLifeProtocol.HU_INFO,
                    CarLifeProtocol.deviceInfo("${displayWidth}x$displayHeight"),
                )
                // 车机必须主动推进，不能等手机下发鉴权
                Thread { initAfterInfo() }.apply { isDaemon = true }.start()
            }

            CarLifeProtocol.MD_MODULE_STATUS -> {
                val modules = CarLifeProtocol.parseModuleStatus(payload)
                if (modules.isEmpty()) return
                RingLog.add(
                    "CARLIFE: 引擎模块状态 " +
                        modules.joinToString(" ") { "${it.first}=${it.second}" },
                )
                val toEnable = CarLifeProtocol.modulesToEnable(modules)
                if (toEnable.isNotEmpty()) {
                    send(CarLifeProtocol.HU_MODULE_CONTROL, CarLifeProtocol.moduleControl(toEnable))
                }
            }

            CarLifeProtocol.MD_VIDEO_ENC_INIT -> {
                val size = readEncoderInfo(payload)
                if (size != null) {
                    touchWidth = size.first.coerceAtLeast(1)
                    touchHeight = size.second.coerceAtLeast(1)
                    RingLog.add(
                        "CARLIFE: 手机确认编码参数 ${size.first}x${size.second}@${size.third}" +
                            "（申请 ${displayWidth}x$displayHeight@$fps），启动推流",
                    )
                }
                send(CarLifeProtocol.VIDEO_ENC_START, ByteArray(0))
            }

            CarLifeProtocol.MD_FEATURE_CONFIG_DONE -> {
                featureAcked = true
                RingLog.add("CARLIFE: 引擎已接受特征配置")
            }

            CarLifeProtocol.MD_ENCRYPT_SETUP -> {
                RingLog.add("CARLIFE: ⚠ 收到 65642 内容加密握手——本会话作废；请强制停止荣耀车联/CarLife+ 后重试")
                stop()
            }

            else -> {
                if (unknownIds.add(serviceType)) {
                    RingLog.add("CARLIFE: 未处理消息 0x%X（${payload.size}B）".format(serviceType))
                }
            }
        }
    }

    /** 98307 之后的主动初始化：98386 →（等 65570）→ 首条 98311。 */
    private fun initAfterInfo() {
        try {
            var waited = 0L
            while (!versionMatched && running.get() && waited < 2_000) {
                Thread.sleep(50)
                waited += 50
            }
            if (!running.get()) return
            RingLog.add("CARLIFE: 主动推送 98386 特征配置（CONNECT_TYPE=5，DISABLE_RESOLUTION_OPTION=1，CONTENT_ENCRYPTION=0）")
            send(CarLifeProtocol.HU_FEATURE_CONFIG, CarLifeProtocol.featureConfig())

            waited = 0L
            while (!featureAcked && running.get() && waited < FEATURE_ACK_WAIT_MS) {
                Thread.sleep(50)
                waited += 50
            }
            if (!running.get()) return
            if (!featureAcked) {
                RingLog.add("CARLIFE: 98386 未在 ${FEATURE_ACK_WAIT_MS}ms 内确认——照常发 98311（部分引擎不回 65570）")
            }
            sendEncoderInit()
        } catch (_: InterruptedException) {
            // 会话结束
        }
    }

    private fun sendEncoderInit() {
        val area = saveArea
        RingLog.add(
            "CARLIFE: 主动推送 98311 视频编码初始化 ${displayWidth}x${displayHeight}@$fps" +
                "（needAdapterScreen=$needAdapterScreen，安全区=${area?.joinToString("x") ?: "无"}）",
        )
        send(
            CarLifeProtocol.HU_VIDEO_ENC_INIT,
            CarLifeProtocol.videoEncoderInfo(
                displayWidth,
                displayHeight,
                fps,
                needAdapterScreen = needAdapterScreen,
                saveArea = area,
            ),
        )
    }

    /**
     * 车机安全区/适配参数更新后调用（AA 配置拿到 margins 时）。
     *
     * 按 CarPToCarL 的实测：98311 只在编码器创建前生效，所以这里重发
     * 98386 →（等 65570）→ 98311，让引擎在已有编码器上重建一次（settleReinit）。
     */
    fun updateVendorEncoderParams(safe: IntArray?, newWidth: Int? = null, newHeight: Int? = null): Boolean {
        val oldArea = saveArea
        val areaSame = when {
            oldArea == null || safe == null -> oldArea == null && safe == null
            else -> oldArea.contentEquals(safe)
        }
        val targetW = newWidth?.takeIf { it > 0 } ?: displayWidth
        val targetH = newHeight?.takeIf { it > 0 } ?: displayHeight
        val sizeSame = targetW == displayWidth && targetH == displayHeight
        saveArea = safe
        needAdapterScreen = true
        displayWidth = targetW
        displayHeight = targetH
        // 触摸映射也跟随新的编码尺寸
        touchWidth = displayWidth
        touchHeight = displayHeight
        if (!running.get()) return false
        // 关键：参数没变就绝不能重发 98386→98311。引擎每次重初始化都会先吐一段默认档
        // （1280x720）的过渡码流，转码管线若在这时新建会把源码流尺寸锁死，之后再也
        // 解不出 1412x654 —— 实车表现为"只有占位帧、偶尔一次才出画面"。
        if (areaSame && sizeSame) {
            RingLog.add(
                "CARLIFE: 编码参数未变化（" + displayWidth + "x" + displayHeight + "，安全区=" +
                    (safe?.joinToString("x") ?: "无") + "），跳过重发 98386→98311",
            )
            return false
        }
        Thread({
            runCatching {
                RingLog.add(
                    "CARLIFE: 收到车机安全区 ${safe?.joinToString("x") ?: "无"}，" +
                        "重发 98386→98311 尝试按 ${displayWidth}x${displayHeight} 重建",
                )
                featureAcked = false
                send(CarLifeProtocol.HU_FEATURE_CONFIG, CarLifeProtocol.featureConfig())
                var waited = 0L
                while (!featureAcked && running.get() && waited < FEATURE_ACK_WAIT_MS) {
                    Thread.sleep(50)
                    waited += 50
                }
                Thread.sleep(150)
                if (running.get()) sendEncoderInit()
            }
        }, "carlife-vendor-params").apply { isDaemon = true }.start()
        return true
    }

    private fun heartbeatLoop() {
        try {
            var beats = 0
            while (running.get()) {
                Thread.sleep(1_000)
                if (!running.get()) return
                send(CarLifeProtocol.HEARTBEAT, ByteArray(0))
                beats++
                // 每 30 秒留一条痕迹：能看出 App 进程是否被系统冻结/会话是否还活着
                if (beats % 30 == 0) RingLog.add("CARLIFE: 心跳正常（已 $beats 秒）")
            }
        } catch (e: Exception) {
            if (running.get()) RingLog.add("CARLIFE: 心跳中止 - ${e.message}")
        }
    }

    // ---- 视频 ----

    private fun videoReader(input: InputStream) {
        val data = DataInputStream(input)
        try {
            var frames = 0L
            while (running.get()) {
                val header = ByteArray(12)
                data.readFully(header)
                val length = (CarLifeProtocol.readU32(header, 0).toLong() and 0xFFFF_FFFFL).toInt()
                val timestampMs = CarLifeProtocol.readU32(header, 4).toLong() and 0xFFFF_FFFFL
                val pkgType = CarLifeProtocol.readU32(header, 8)
                if (length <= 0 || length > MAX_VIDEO_FRAME) {
                    RingLog.add("CARLIFE: 视频帧长度异常（$length），放弃视频通道")
                    break
                }
                val payload = ByteArray(length)
                data.readFully(payload)
                frames++
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    RingLog.add("CARLIFE: ★ 首帧 ${length}B（pkgType=0x%X）".format(pkgType))
                }
                val ptsUs = if (timestampMs > 0) timestampMs * 1_000L else frames * 33_333L
                onVideoFrame(payload, ptsUs)
            }
        } catch (e: Exception) {
            if (running.get()) RingLog.add("CARLIFE: 视频通道断开 - ${e.message}")
        }
    }

    // ---- 音频（9240 媒体 / 9241 TTS；裸字节流：12B 头 + PCM）----

    private fun audioReader(input: InputStream, isTts: Boolean) {
        val label = if (isTts) "TTS" else "媒体"
        val data = DataInputStream(input)
        try {
            while (running.get()) {
                val head = ByteArray(CarLifeProtocol.AUDIO_HEAD_LEN)
                data.readFully(head)
                val length = (CarLifeProtocol.readU32(head, 0).toLong() and 0xFFFF_FFFFL).toInt()
                val type = CarLifeProtocol.readU32(head, 8)
                if (length < 0 || length > MAX_AUDIO_PACKET) {
                    RingLog.add("CARLIFE: $label 音频包长度异常（$length），放弃该通道")
                    return
                }
                val payload = ByteArray(length)
                if (length > 0) data.readFully(payload)

                if (isTts) {
                    when (type) {
                        CarLifeProtocol.TTS_PKG_INIT -> {
                            val format = parseAudioInit(payload)
                            if (format != null) {
                                val channels = channelsOf(format.second)
                                RingLog.add("CARLIFE: TTS 音频格式宣告 ${format.first}Hz ${channels}ch")
                                onTtsFormat(format.first, channels)
                            }
                        }

                        CarLifeProtocol.TTS_PKG_NORMAL -> onTtsPcm(payload, length)
                        CarLifeProtocol.TTS_PKG_STOP -> {
                            RingLog.add("CARLIFE: TTS 停止")
                            onTtsStop()
                        }
                        else -> RingLog.add("CARLIFE: 未识别 TTS 包 0x%X（${length}B）".format(type))
                    }
                } else {
                    when (type) {
                        CarLifeProtocol.MUSIC_PKG_INIT -> {
                            val format = parseAudioInit(payload)
                            if (format != null) {
                                val channels = channelsOf(format.second)
                                RingLog.add(
                                    "CARLIFE: 媒体音频格式宣告 ${format.first}Hz ${channels}ch " +
                                        "${format.third}bit（channelConfig=${format.second}）",
                                )
                                onMediaFormat(format.first, channels)
                            }
                        }

                        CarLifeProtocol.MUSIC_PKG_NORMAL -> onMediaPcm(payload, length)
                        CarLifeProtocol.MUSIC_PKG_RESUME -> {
                            RingLog.add("CARLIFE: 媒体恢复播放")
                            onMediaResume()
                        }
                        CarLifeProtocol.MUSIC_PKG_PAUSE -> {
                            RingLog.add("CARLIFE: 媒体暂停")
                            onMediaPause()
                        }
                        CarLifeProtocol.MUSIC_PKG_STOP -> {
                            RingLog.add("CARLIFE: 媒体停止")
                            onMediaStop()
                        }
                        else -> RingLog.add("CARLIFE: 未识别媒体包 0x%X（${length}B）".format(type))
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) RingLog.add("CARLIFE: $label 音频通道断开 - ${e.message}")
        }
    }

    /** 解析 CarlifeMusicInit：field1 采样率 / field2 声道配置 / field3 位深（带数值兜底）。 */
    private fun parseAudioInit(payload: ByteArray): Triple<Int, Int, Int>? {
        var sampleRate = 0
        var channelConfig = 0
        var sampleFormat = 0
        var index = 0
        try {
            while (index < payload.size) {
                val tag = payload[index].toInt() and 0xFF
                index++
                if (tag and 0x07 != 0) return null
                var value = 0
                var shift = 0
                while (index < payload.size) {
                    val b = payload[index].toInt() and 0xFF
                    index++
                    value = value or ((b and 0x7F) shl shift)
                    shift += 7
                    if (b and 0x80 == 0) break
                }
                when (tag ushr 3) {
                    1 -> sampleRate = value
                    2 -> channelConfig = value
                    3 -> sampleFormat = value
                }
            }
        } catch (_: RuntimeException) {
            return null
        }
        if (sampleRate <= 4_000) sampleRate = 48_000
        if (sampleFormat != 8 && sampleFormat != 16) sampleFormat = 16
        if (channelConfig <= 0 || channelConfig == sampleRate || channelConfig == sampleFormat) {
            channelConfig = 2
        }
        return Triple(sampleRate, channelConfig, sampleFormat)
    }

    private fun channelsOf(channelConfig: Int): Int = if (channelConfig == 1) 1 else 2

    // ---- 触控 ----

    /** 单点/手势消息（0x68002/3/4、0x68005/6/7、0x6800A）：负载是 {x,y}。 */
    fun touchSingle(serviceType: Int, x: Int, y: Int) {
        enqueueTouch(serviceType, 0, x, y)
    }

    /** 0x6800B 第二根手指按下（负载 SinglePoint{x,y}，引擎内部映射 ACTION_POINTER_DOWN 261）。 */
    fun touchPointerDown(x: Int, y: Int) {
        enqueueTouch(CarLifeProtocol.TOUCH_POINTER_DOWN, 0, x, y)
    }

    /** 0x6800C 第一根手指抬起（引擎内部映射 ACTION_POINTER_UP 6）。 */
    fun touchPointerUp(x: Int, y: Int) {
        enqueueTouch(CarLifeProtocol.TOUCH_POINTER_UP, 0, x, y)
    }

    /** 0x6800D 第二根手指抬起（引擎内部映射 ACTION_POINTER_UP 262）。 */
    fun touchOtherPointerUp(x: Int, y: Int) {
        enqueueTouch(CarLifeProtocol.TOUCH_OTHER_POINTER_UP, 0, x, y)
    }

    /** 双指 MOVE：0x68004 负载带两根手指坐标（x/y + pointerx/pointery）。 */
    fun touchMoveDual(x0: Int, y0: Int, x1: Int, y1: Int) {
        enqueueTouch(CarLifeProtocol.TOUCH_MOVE, 0, x0, y0, x1, y1)
    }

    private fun enqueueTouch(
        serviceType: Int,
        action: Int,
        x: Int,
        y: Int,
        pointerX: Int = -1,
        pointerY: Int = -1,
    ) {
        if (!running.get()) return
        val px = x.coerceIn(0, 65535)
        val py = y.coerceIn(0, 65535)
        val ppx = pointerX.coerceIn(0, 65535)
        val ppy = pointerY.coerceIn(0, 65535)
        try {
            touchExecutor.execute {
                val out = touchOut ?: return@execute
                val payload = if (pointerX >= 0 && pointerY >= 0) {
                    CarLifeProtocol.touchPointDual(px, py, ppx, ppy)
                } else {
                    CarLifeProtocol.touchPoint(px, py)
                }
                try {
                    synchronized(touchLock) {
                        out.write(CarLifeProtocol.packCommand(serviceType, payload))
                        out.flush()
                    }
                } catch (e: Exception) {
                    RingLog.add("CARLIFE: 触控发送失败 - ${e.message}")
                }
            }
        } catch (_: RejectedExecutionException) {
            // 会话正在停止，触摸线程池已关闭：丢弃这笔迟到的触摸，不能让它崩 UI。
        }
    }

    // ---- 底层 ----

    private fun send(serviceType: Int, payload: ByteArray): Boolean {
        val out = cmdOut ?: return false
        return try {
            synchronized(writeLock) {
                out.write(CarLifeProtocol.packCommand(serviceType, payload))
                out.flush()
            }
            true
        } catch (e: Exception) {
            RingLog.add("CARLIFE: 发送 0x%X 失败 - %s".format(serviceType, e.message))
            false
        }
    }

    private fun readEncoderInfo(payload: ByteArray): Triple<Int, Int, Int>? {
        var width = 0
        var height = 0
        var rate = 0
        var index = 0
        try {
            while (index < payload.size) {
                val tag = payload[index].toInt() and 0xFF
                index++
                if (tag and 0x07 != 0) return null
                var value = 0
                var shift = 0
                while (index < payload.size) {
                    val b = payload[index].toInt() and 0xFF
                    index++
                    value = value or ((b and 0x7F) shl shift)
                    shift += 7
                    if (b and 0x80 == 0) break
                }
                when (tag ushr 3) {
                    1 -> width = value
                    2 -> height = value
                    3 -> rate = value
                }
            }
        } catch (_: RuntimeException) {
            return null
        }
        if (width <= 0 || height <= 0) return null
        return Triple(width, height, if (rate > 0) rate else fps)
    }

    private fun startThread(name: String, block: () -> Unit) {
        Thread(block, name).apply { isDaemon = true }.start()
    }

    private fun closeAll() {
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
        cmdOut = null
        touchOut = null
    }

    private fun end(reason: String) {
        if (ended.compareAndSet(false, true)) onState(reason)
    }
}
