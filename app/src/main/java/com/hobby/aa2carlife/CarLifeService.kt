package com.hobby.aa2carlife

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.Surface
import com.hobby.aa2carlife.aa.AaAnnexB
import com.hobby.aa2carlife.aa.AaHeadUnitServer
import com.hobby.aa2carlife.aa.SpsInfo
import com.hobby.aa2carlife.carlife.CarLifeAudio
import com.hobby.aa2carlife.carlife.CarLifeHuClient
import com.hobby.aa2carlife.carlife.CarLifeVideoDecoder
import com.hobby.aa2carlife.carlife.TranscodePipeline
import com.hobby.aa2carlife.diag.RingLog

/**
 * 前台服务：持有 CarLife 会话与解码器，避免 App 退到后台被系统杀掉。
 *
 * M1 阶段只做「手机本地预览」，Surface 由 MainActivity 的 SurfaceView 提供。
 * M2 接入 AA 后，这里会把 CarLife 的 NAL 直接喂给 AA 视频通道而不是解码上屏。
 */
class CarLifeService : Service() {

    companion object {
        @Volatile
        var current: CarLifeService? = null
            private set

        /** 记住上次 AA 车机协商出的分辨率（借鉴 CarPToCarL 的"车机画布记忆"）。 */
        const val PREFS = "aa2carlife_prefs"
        private const val KEY_LAST_W = "last_video_width"
        private const val KEY_LAST_H = "last_video_height"

        /** CarLife 编码器 SPS 里的 profile_idc / level_idc（占位画面模仿用）。 */
        const val KEY_SPS_PROFILE = "sps_profile_idc"
        const val KEY_SPS_LEVEL = "sps_level_idc"

        /** 本机蓝牙地址：车机蓝牙服务通道用它确认投屏手机身份（Honor 系统脱敏，需手填）。 */
        const val KEY_PHONE_BT_MAC = "phone_bt_mac"

        /** 车机 USB 接入/断开时由界面启动/通知服务，保持 AA 会话进程存活（进设置/后台不被杀）。 */
        const val ACTION_AA_ATTACHED = "com.hobby.aa2carlife.action.AA_ATTACHED"
        const val ACTION_AA_DETACHED = "com.hobby.aa2carlife.action.AA_DETACHED"
    }

    inner class LocalBinder : Binder() {
        fun service(): CarLifeService = this@CarLifeService
    }

    private val binder = LocalBinder()


    private var client: CarLifeHuClient? = null

    /** 补边转码管线：CarLife 码流 → AA 标准画布（1412×654 内容画进 1920×1080 中央）。 */
    @Volatile
    private var transcode: TranscodePipeline? = null
    @Volatile
    private var decoder: CarLifeVideoDecoder? = null
    private var audio: CarLifeAudio? = null

    /** 缓存的 H.264 参数集/关键帧：界面切后台 Surface 重建后，靠它立刻恢复预览。 */
    @Volatile
    private var lastCodecConfig: ByteArray? = null

    @Volatile
    private var lastKeyFrame: ByteArray? = null

    /** 会话期间持有部分唤醒锁：防止息屏/切后台时 CPU 被冻结导致心跳中断、引擎断开。 */
    private var wakeLock: PowerManager.WakeLock? = null

    /** 防止并发重复启动会话（探测 7240 在后台线程，可能连点）。 */
    @Volatile
    private var startInProgress = false

    /** USB 状态/配件广播监听：记录系统层面的 USB 重枚举，定位"进设置就断 AA"的元凶。 */
    private var usbReceiver: BroadcastReceiver? = null

    @Volatile
    private var surface: Surface? = null

    /** 视频显示是否旋转 90°（由界面开关设置，下次启动会话时生效）。 */
    @Volatile
    var rotate90 = false

    /** CarLife 实际码流与车机安全区不一致时提示（App 负责弹 UI）。 */
    @Volatile
    var onResolutionMismatch: ((String) -> Unit)? = null

    /** KEY_ROTATION 不支持、预览被迫退回横屏时通知 UI。 */
    @Volatile
    var onPreviewRotationFallback: (() -> Unit)? = null

    /** 原始 CarLife 码流的 SPS/PPS 与关键帧（预览解码器重建时用，避免喂错成转码后的 1920x1088）。 */
    private var lastRawCodecConfig: ByteArray? = null
    private var lastRawKeyFrame: ByteArray? = null

    /** 每次会话只检查一次实际码流分辨率。 */
    private var resolutionCheckScheduled = false


    /** 向 CarLife 引擎申请的编码分辨率（M4 自适应：车机 AA 配置选 1920x1080 时就改成它）。 */
    @Volatile
    var videoWidth = 1920

    @Volatile
    var videoHeight = 1080

    /** 车机安全区（来自 AA 配置 margin），传给 CarLife 引擎做 98311 适配。 */
    @Volatile
    var carSafeArea: IntArray? = null

    /**
     * 收到车机安全区：保存；**只有参数真的变化时**才重初始化引擎并重建转码管线。
     *
     * 实车教训（2026-11-25 09:17 / 09:18 两次接入对照）：AA 每次接入都会回调这里，
     * 若无条件重发 98386→98311，引擎会重建编码器并先吐一段默认档 1280x720 的过渡
     * 码流；转码管线恰好此时新建，就把源码流尺寸锁死在 1280x720，之后 1412x654 的
     * 帧再也解不出来（车机只剩占位帧，表现为"偶尔一次才能显示"）。
     */
    fun applyCarSafeArea(area: IntArray?) {
        carSafeArea = area
        val w = area?.getOrNull(0)?.takeIf { it > 0 }
        val h = area?.getOrNull(1)?.takeIf { it > 0 }
        val reinitialized = client?.updateVendorEncoderParams(area, w, h) ?: false
        // 引擎参数没变，不代表管线不用动：
        // 插 USB 自动启动 CarLife 时 SafeArea 还是 null，管线会以"等比铺满"建出来；
        // 随后 AA 配置给出的安全区到达，必须把补边几何补上（之前只判引擎参数，导致画面被拉伸）。
        val tx = transcode
        val expectedDest = computeDestRect()
        val canvasW = videoWidth.takeIf { it > 0 } ?: 1920
        val canvasH = videoHeight.takeIf { it > 0 } ?: 1080
        val geometryChanged = tx == null ||
            tx.canvasWidthPx != canvasW ||
            tx.canvasHeightPx != canvasH ||
            tx.destRectPx != expectedDest
        if (!reinitialized && !geometryChanged) {
            RingLog.add(
                "CARLIFE: 车机安全区 " + (area?.joinToString("x") ?: "无") + " 未触发引擎重初始化，转码几何也未变化，保持现状",
            )
            return
        }
        RingLog.add(
            "CARLIFE: 车机安全区 " + (area?.joinToString("x") ?: "无") + " 需要更新管线" +
                "（引擎重初始化=" + reinitialized + "，几何变化=" + geometryChanged + "）",
        )
        recreateTranscode()
    }

    /** 当前画布 + 安全区对应的补边目标矩形（null=等比铺满整张画布）。 */
    private fun computeDestRect(): android.graphics.Rect? {
        val safe = carSafeArea ?: return null
        if (safe.size < 2) return null
        val canvasW = videoWidth.takeIf { it > 0 } ?: 1920
        val canvasH = videoHeight.takeIf { it > 0 } ?: 1080
        if (safe[0] !in 1..canvasW || safe[1] !in 1..canvasH) return null
        val x = ((canvasW - safe[0]) / 2).coerceAtLeast(0)
        val y = ((canvasH - safe[1]) / 2).coerceAtLeast(0)
        return android.graphics.Rect(x, y, x + safe[0], y + safe[1])
    }

    /** 按当前 AA 画布 + 车机安全区创建补边转码管线。 */
    private fun buildTranscode(): TranscodePipeline? {
        val canvasW = videoWidth.takeIf { it > 0 } ?: 1920
        val canvasH = videoHeight.takeIf { it > 0 } ?: 1080
        val dest = computeDestRect()
        RingLog.add("TRANSCODE: 创建管线 画布=${canvasW}x$canvasH 目标区=${dest ?: "等比铺满"}")
        return TranscodePipeline(
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            destRect = dest,
            onFrame = { frame -> handleTranscodedFrame(frame) },
            onError = { e -> RingLog.add("TRANSCODE: 错误 - $e") },
        ).also { it.start() }
    }

    /** AA 配置尺寸或车机安全区变化时重建管线（引擎侧由 updateVendorEncoderParams 触发重编码）。 */
    private fun recreateTranscode() {
        if (client?.isRunning() != true) return
        RingLog.add("TRANSCODE: 画布/安全区变化，重建转码管线")
        transcode?.release()
        transcode = buildTranscode()
    }

    /** 原始 CarLife 帧（直通模式）：缓存参数集/关键帧、喂预览解码器、交给 AA。 */
    private fun handleVideoFrame(frame: ByteArray, ptsUs: Long) {
        if (!resolutionCheckScheduled) {
            resolutionCheckScheduled = true
            // 引擎可能在建流前降档（例如 1412x654 → 1280x720）；等它稳定几秒再对比。
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { checkResolutionSettled() },
                4000L,
            )
        }
        val cfg = AaAnnexB.extractCodecConfig(frame)
        if (cfg != null) {
            lastCodecConfig = cfg
            lastKeyFrame = frame
            rememberSpsParams(cfg)
        } else if (lastKeyFrame == null || AaAnnexB.isKeyFrame(frame)) {
            lastKeyFrame = frame
        }
        decoder?.submit(frame, ptsUs)
        aaVideoSink?.invoke(frame)
    }

    /** 转码后的帧（已是 AA 画布尺寸）：走同样的缓存/预览/AA 通路。 */
    private fun handleTranscodedFrame(frame: ByteArray) {
        val cfg = AaAnnexB.extractCodecConfig(frame)
        if (cfg != null) {
            lastCodecConfig = cfg
            lastKeyFrame = frame
        } else if (lastKeyFrame == null || AaAnnexB.isKeyFrame(frame)) {
            lastKeyFrame = frame
        }
        // 预览解码器只喂原始 CarLife 帧（onVideoFrame 里），这里不再喂转码后的 1920x1088。
        aaVideoSink?.invoke(frame)
    }

    /** 记录 CarLife 编码参数（profile/level），供占位画面模仿。 */
    private fun rememberSpsParams(cfg: ByteArray) {
        runCatching {
            val pl = SpsInfo.parseProfileLevel(cfg) ?: return
            val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (sp.getInt(KEY_SPS_PROFILE, -1) != pl.first ||
                sp.getInt(KEY_SPS_LEVEL, -1) != pl.second
            ) {
                sp.edit()
                    .putInt(KEY_SPS_PROFILE, pl.first)
                    .putInt(KEY_SPS_LEVEL, pl.second)
                    .apply()
                RingLog.add(
                    "CARLIFE: 记录编码参数 profile_idc=${pl.first} level_idc=${pl.second}（占位画面将模仿）",
                )
            }
        }
    }

    /** 手动指定分辨率（null=自动跟随车机/记忆）；只在下次启动会话时生效。 */
    @Volatile
    var resolutionOverride: Pair<Int, Int>? = null

    /** 设置手动分辨率：未运行则同时更新请求值，运行中提示下次生效。 */
    fun setResolutionOverride(w: Int?, h: Int?) {
        resolutionOverride = if (w != null && h != null) w to h else null
        if (resolutionOverride == null) {
            RingLog.add("CARLIFE: 分辨率改为【自动】（跟随车机）")
        } else {
            if (w == 1920 && h == 1080) {
                RingLog.add("CARLIFE: 注意 1920x1080 会被引擎编码为 1920x1088，车机可能不兼容；不行请换 1280x720")
            }
            if (client?.isRunning() != true) {
                videoWidth = resolutionOverride!!.first
                videoHeight = resolutionOverride!!.second
                RingLog.add("CARLIFE: 分辨率手动指定为 ${w}x$h（下次启动生效）")
            } else {
                RingLog.add("CARLIFE: 分辨率手动指定为 ${w}x$h（当前会话仍用旧尺寸，断开重连后生效）")
            }
        }
    }

    /** videoWidth/Height 是否来自"上次车机记忆"（还没连上当前车机时） */
    private var videoSizeFromMemory = false

    /** AA 车机选定的视频分辨率变化时调用：只更新参数，不重启会话（见下方实测结论）。 */
    fun ensureVideoSize(w: Int, h: Int) {
        if (sanitizeSize(w, h) == null) return
        if (w == videoWidth && h == videoHeight) {
            videoSizeFromMemory = false
            return
        }
        videoWidth = w
        videoHeight = h
        videoSizeFromMemory = false
        saveLastVideoSize(w, h)
        RingLog.add("CARLIFE: 视频请求分辨率跟随车机改为 ${w}x$h")
        // AA 画布尺寸变了：转码画布必须跟着换，但**不需要**动 CarLife 引擎（请求尺寸是
        // 车机安全区，不是 AA 画布）。这里只重建转码管线，避免误触发 98386→98311。
        val tx = transcode
        if (client?.isRunning() == true && tx != null &&
            (tx.canvasWidthPx != w || tx.canvasHeightPx != h)
        ) {
            recreateTranscode()
        }
        // 实测结论（2026-11-24）：一旦主动断开 CarLife 连接，荣耀车联/CarLife 引擎会退出
        // 「无线连接/正在连接」服务态（7240 不再监听），立即重连/延迟 0.6s/1.5s 重连都会失败，
        // 必须人工重新进荣耀车联的「正在连接」。所以这里绝不能为了换分辨率自动重启会话，
        // 只更新参数，等用户下次点「启动」时生效（或走转码方案）。
        if (client?.isRunning() == true) {
            RingLog.add(
                "CARLIFE: 车机分辨率 ${w}x$h 与当前会话不同；为避免断开后无法自动重连，" +
                    "本次不重启，下次启动生效",
            )
        }
    }

    private fun sanitizeSize(w: Int, h: Int): Pair<Int, Int>? =
        if (w in 320..4096 && h in 180..2304) w to h else null

    /** 启动时读取"上次车机分辨率"，用于"先开 CarLife、还没连车机"的场景。 */
    private fun loadLastVideoSize() {
        runCatching {
            val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
            var w = sp.getInt(KEY_LAST_W, 0)
            var h = sp.getInt(KEY_LAST_H, 0)
            if (sanitizeSize(w, h) != null) {

                videoWidth = w
                videoHeight = h
                videoSizeFromMemory = true
                RingLog.add("CARLIFE: 采用上次 AA 车机分辨率 ${w}x$h（连上当前车机后会被纠正）")
            }
            // 车机安全区记忆：CarLife 先启动、AA 还没连时，也能先按上次的尺寸申请编码器。
            val sw = sp.getInt("car_safe_w", 0)
            val sh = sp.getInt("car_safe_h", 0)
            if (sw in 320..4096 && sh in 180..2304) {
                carSafeArea = intArrayOf(sw, sh, 0, 0)
                RingLog.add("CARLIFE: 采用上次车机安全区 ${sw}x$sh（AA 连上后会再校准）")
            }
        }
    }

    private fun saveLastVideoSize(w: Int, h: Int) {
        runCatching {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_LAST_W, w)
                .putInt(KEY_LAST_H, h)
                .apply()
        }
    }

    /** AA 桥接（M3）：CarLife 的视频帧 / 媒体 PCM 转发给 AA 手机端服务。 */
    @Volatile
    var aaVideoSink: ((ByteArray) -> Unit)? = null

    @Volatile
    var aaAudioSink: ((ByteArray, Int) -> Unit)? = null

    /** CarLife 媒体 PCM 源格式（采样率/声道）→ AA 重采样用。 */
    var aaAudioFormatSink: ((Int, Int) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        current = this
        RingLog.init(applicationContext)
        loadLastVideoSize()
        startForegroundInternal()
        registerUsbWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_AA_ATTACHED -> {
                // AA 会话期间保持前台服务：进设置/切后台时系统不会清理进程，车机连接不断
                startForegroundInternal()
                acquireWakeLock()
                RingLog.add("SERVICE: 车机已连接，保持前台服务（防止进设置/后台被杀导致 AA 断开）")
            }
            ACTION_AA_DETACHED -> {
                RingLog.add("SERVICE: 车机已断开")
                if (client?.isRunning() != true) {
                    releaseWakeLock()
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        RingLog.add("SERVICE: CarLifeService.onDestroy")
        unregisterUsbWatcher()
        stopSession("服务销毁")
        current = null
        super.onDestroy()
    }

    /** 监听 USB 状态广播：记录 connected/configured/adb 等变化，定位"进设置 USB 被重置"的原因。 */
    private fun registerUsbWatcher() {
        if (usbReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                when (action) {
                    "android.hardware.usb.action.USB_STATE" -> {
                        val b = intent.extras ?: return
                        val flags = listOf("connected", "configured", "adb", "mtp", "ptp", "rndis", "midi", "audio_source")
                            .joinToString(" ") { f ->
                                val v = runCatching { b.getBoolean(f) }.getOrDefault(false)
                                if (v) "$f=1" else "$f=0"
                            }
                        RingLog.add("USB: 系统 USB_STATE 变化 $flags")
                    }
                    UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                        RingLog.add("USB: 系统广播 accessory 已断开（真·USB 断开）")
                        // Service 常驻时也要停会话，避免 Activity 不在前台留下死会话。
                        AaHeadUnitServer.current?.stopSessions()
                    }
                    UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                        @Suppress("DEPRECATION")
                        val accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as? UsbAccessory
                        RingLog.add(
                            "USB: 系统广播 accessory 接入 manufacturer=" + accessory?.manufacturer +
                                " model=" + accessory?.model,
                        )
                        val server = AaHeadUnitServer.current
                        if (server != null && accessory != null) {
                            // 关键：CarLife 在前台时我们的 Activity 可能在后台，收不到 attach intent。
                            // Service 的广播接收器是常驻的，这里直接启动 AA 会话。
                            RingLog.add("USB: Service 直接启动 AA 会话（Activity 可能不在前台）")
                            runCatching {
                                server.startAccessorySession(getSystemService(UsbManager::class.java), accessory)
                            }.onFailure { RingLog.add("USB: Service 启动 AA 会话失败 - ${it.message}") }
                        } else {
                            RingLog.add("USB: Service 收到 accessory 接入，等待 Activity 处理（server=" + server + "）")
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED ->
                        RingLog.add("USB: 系统广播 USB 设备拔出")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.hardware.usb.action.USB_STATE")
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            usbReceiver = receiver
            RingLog.add("USB: 已注册 USB 状态广播监听（连接/断开/调试模式变化都会记录）")
        }.onFailure { RingLog.add("USB: 注册 USB 广播失败 - ${it.message}") }
    }

    private fun unregisterUsbWatcher() {
        usbReceiver?.let { runCatching { unregisterReceiver(it) } }
        usbReceiver = null
    }

    /** Surface 重建/切换时串行处理，避免 surfaceCreated/surfaceChanged 连续回调导致解码器反复重建。 */
    private val surfaceLock = Any()

    fun attachSurface(s: Surface) {
        synchronized(surfaceLock) {
            if (s === surface && decoder != null) return
            surface = s
            // 界面回到前台：重建一个绑定到新 Surface 的解码器，
            // 并把缓存的 SPS/PPS + 关键帧喂进去，避免"回来黑屏等下一个关键帧"。
            if (client?.isRunning() == true) {
                decoder?.release()
                val d = CarLifeVideoDecoder(s, rotate90, lastRawCodecConfig)
                d.onRotationFallback = { onPreviewRotationFallback?.invoke() }
                decoder = d
                lastRawKeyFrame?.let { runCatching { d.submit(it, 0) } }
                RingLog.add(
                    "VIDEO: 预览解码器已重建（原始参数集=${lastRawCodecConfig != null}，" +
                        "回放原始关键帧=${lastRawKeyFrame != null}）",
                )
            }
        }
    }

    fun detachSurface() {
        synchronized(surfaceLock) {
            // 只释放本地预览解码器，绝对不能 stopSession()：
            // 界面切后台时 Surface 会被销毁，一旦断开会话，荣耀车联引擎会退出「正在连接」态，
            // 车机投屏也会跟着断且无法自动恢复。CarLife/AA 会话必须保持。
            decoder?.release()
            decoder = null
            surface = null
        }
        RingLog.add("VIDEO: 界面切后台，预览暂停（CarLife/AA 会话保持）")
    }

    fun startSession() {
        if (client?.isRunning() == true || startInProgress) return
        startInProgress = true
        // 探测/建流都放后台线程：主线程做 Socket 连接会抛 NetworkOnMainThreadException，
        // 之前"引擎明明在监听却提示未监听"就是这个原因。
        Thread({
            try {
                if (!isEngineReady()) {
                    RingLog.add("CARLIFE: 7240 未监听——请先打开荣耀车联 → CarLife → 无线连接 → 正在连接")
                    return@Thread
                }
                startSessionInternal()
            } finally {
                startInProgress = false
            }
        }, "carlife-start").apply { isDaemon = true }.start()
    }

    private fun startSessionInternal() {
        resolutionCheckScheduled = false
        // 没有 Surface 也能启动（本地预览可以后补），关键是不能因为界面不在就拒绝启动
        logAudioVolume()
        acquireWakeLock()
        lastCodecConfig = null
        lastKeyFrame = null
        lastRawCodecConfig = null
        lastRawKeyFrame = null
        decoder = surface?.let { s ->
            CarLifeVideoDecoder(s, rotate90).also { d ->
                d.onRotationFallback = { onPreviewRotationFallback?.invoke() }
            }
        }
        val audioSink = CarLifeAudio(getSystemService(AudioManager::class.java))
        audioSink.logOutputDevices()
        audio = audioSink
        // 直接按车机安全区 1412x654 请求 CarLife 编码（比例与车机 2.16:1 一致，后续 1:1 补边到 1920x1080）
        // 固定 1080p 输出：引擎始终按车机安全区请求（1412x654），不再受手动分辨率影响
        val safe = carSafeArea
        val targetW = safe?.getOrNull(0)?.takeIf { it > 0 } ?: 1412
        val targetH = safe?.getOrNull(1)?.takeIf { it > 0 } ?: 654
        RingLog.add("CARLIFE: 请求引擎按车机比例编码 ${targetW}x${targetH}（安全区=${safe?.joinToString("x") ?: "默认1412x654"}）")
        RingLog.add(
            "M1: 启动 CarLife 会话（申请 ${targetW}x${targetH}@30，协议 3.0" +
                if (resolutionOverride != null) {
                    "，手动指定分辨率）"
                } else if (videoSizeFromMemory) {
                    "，来自上次车机记忆）"
                } else {
                    "）"
                } +
                (if (rotate90) "，显示旋转 90°" else ""),
        )
        transcode?.release()
        transcode = buildTranscode()
        val c = CarLifeHuClient(
            displayWidth = targetW,
            displayHeight = targetH,
            fps = 30,
            needAdapterScreen = true,
            saveArea = carSafeArea ?: intArrayOf(targetW, targetH, 0, 0),
            onVideoFrame = { frame, ptsUs ->
                val tx = transcode
                if (tx != null) {
                    // 预览用原始 CarLife 帧（1412x654），这样预览不会被转码画布的黑边二次 letterbox。
                    AaAnnexB.extractCodecConfig(frame)?.let {
                        lastRawCodecConfig = it
                        lastRawKeyFrame = frame
                    } ?: run {
                        if (lastRawKeyFrame == null || AaAnnexB.isKeyFrame(frame)) lastRawKeyFrame = frame
                    }
                    decoder?.submit(frame, ptsUs)
                    // AA 侧仍走补边转码：原始帧→画进安全区→重编码后由 handleTranscodedFrame 出去。
                    tx.submit(frame)
                } else {
                    // 兜底：直通
                    handleVideoFrame(frame, ptsUs)
                }
            },
            onState = { reason -> RingLog.add("CARLIFE: 会话结束 - $reason") },
            onMediaFormat = { rate, channels ->
                audioSink.onMediaFormat(rate, channels)
                aaAudioFormatSink?.invoke(rate, channels)
            },
            onMediaPcm = { data, length ->
                audioSink.onMediaPcm(data, length)
                aaAudioSink?.invoke(data, length)
            },
            onTtsFormat = { rate, channels -> audioSink.onTtsFormat(rate, channels) },
            onTtsPcm = { data, length -> audioSink.onTtsPcm(data, length) },
        )
        client = c
        Thread({
            if (!c.start()) {
                RingLog.add("M1: CarLife 连接失败——请确认荣耀车联停在「正在连接」界面")
            }
        }, "carlife-session").apply { isDaemon = true }.start()
    }


    /** 会话稳定后对比 CarLife 实际码流尺寸与车机安全区；不一致提示重连一次。 */
    private fun checkResolutionSettled() {
        if (client?.isRunning() != true) return
        val safe = carSafeArea ?: return
        if (safe.size < 2) return
        val tx = transcode ?: return
        val aw = tx.sourceWidth
        val ah = tx.sourceHeight
        if (aw <= 0 || ah <= 0) return
        if (kotlin.math.abs(aw - safe[0]) <= 32 && kotlin.math.abs(ah - safe[1]) <= 32) return
        val msg = "CarLife 实际码流 " + aw + "x" + ah + " 与车机安全区 " + safe[0] + "x" + safe[1] +
            " 不一致，请重新连接一次（拔插 USB 或重开 CarLife）"
        RingLog.add("CARLIFE: " + msg)
        runCatching { onResolutionMismatch?.invoke(msg) }
    }

    fun stopSession(reason: String = "用户主动断开") {
        resolutionCheckScheduled = false
        if (client != null || decoder != null || audio != null) {
            RingLog.add("CARLIFE: 断开会话（$reason）")
        }
        client?.stop()
        client = null
        transcode?.release()
        transcode = null
        decoder?.release()
        decoder = null
        audio?.release()
        audio = null
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aa2carlife:session").apply {
                setReferenceCounted(false)
                acquire()
            }
            RingLog.add("POWER: 已获取部分唤醒锁（会话期间保持 CPU 运行）")
        }.onFailure { RingLog.add("POWER: 获取唤醒锁失败 - ${it.message}") }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    /** 当前是否有 CarLife 会话在运行（界面切换旋转等需要重启会话时用）。 */
    fun isSessionRunning(): Boolean = client?.isRunning() == true

    /** 快速探测 CarLife 引擎是否在监听 7240（引擎处于「正在连接」时才有）。 */
    private fun isEngineReady(): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", 7240), 300)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun logAudioVolume() {
        runCatching {
            val am = getSystemService(AudioManager::class.java)
            val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            RingLog.add(
                "AUDIO: 媒体音量 $vol/$max 静音=${am.isStreamMute(AudioManager.STREAM_MUSIC)} " +
                    "铃声模式=${am.ringerMode}",
            )
        }
    }

    fun touchComposite(action: Int, x: Int, y: Int) {
        client?.touchComposite(action, x, y)
    }

    fun touchSingle(serviceType: Int, x: Int, y: Int) {
        client?.touchSingle(serviceType, x, y)
    }

    fun touchPointerDown(x: Int, y: Int) {
        client?.touchPointerDown(x, y)
    }

    fun touchPointerUp(x: Int, y: Int) {
        client?.touchPointerUp(x, y)
    }

    fun touchOtherPointerUp(x: Int, y: Int) {
        client?.touchOtherPointerUp(x, y)
    }

    fun touchMoveDual(x0: Int, y0: Int, x1: Int, y1: Int) {
        client?.touchMoveDual(x0, y0, x1, y1)
    }

    /**
     * 触摸映射基准：CarLife 引擎画布尺寸（协商值优先，1280×720）。
     * 显示是否旋转不影响这里——回传给引擎的始终是未旋转画布坐标。
     */
    /** 预览用实际码流尺寸：解码器 SPS 优先，其次安全区，最后 1412x654。 */
    val previewWidth: Int
        get() = decoder?.width?.takeIf { it > 0 }
            ?: carSafeArea?.getOrNull(0)?.takeIf { it > 0 }
            ?: 1412

    val previewHeight: Int
        get() = decoder?.height?.takeIf { it > 0 }
            ?: carSafeArea?.getOrNull(1)?.takeIf { it > 0 }
            ?: 654

    val touchWidth: Int
        get() = client?.touchWidth?.takeIf { it > 0 }
            ?: decoder?.width?.takeIf { it > 0 }
            ?: 1280

    val touchHeight: Int
        get() = client?.touchHeight?.takeIf { it > 0 }
            ?: decoder?.height?.takeIf { it > 0 }
            ?: 720

    private fun startForegroundInternal() {
        val channelId = "aa2carlife"
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }
}
