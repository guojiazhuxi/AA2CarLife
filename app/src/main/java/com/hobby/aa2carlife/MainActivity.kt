package com.hobby.aa2carlife

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.MotionEvent
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hobby.aa2carlife.carlife.CarLifeProtocol
import com.hobby.aa2carlife.aa.AaDisplayProfile
import com.hobby.aa2carlife.aa.AaHeadUnitServer
import com.hobby.aa2carlife.aa.AaTouchPoint
import com.hobby.aa2carlife.aa.PlaceholderVideo
import com.hobby.aa2carlife.diag.RingLog
import com.hobby.aa2carlife.ui.PreviewLayout

/** 点按/双击判定阈值（坐标是 1280×720 目标域）。 */
private const val TAP_MAX_MS = 600L
private const val DOUBLE_TAP_MS = 350L
private const val TAP_MAX_DRIFT = 60

/** 单指拖动最小位移：从按下点累计位移小于这个值不发 MOVE，点按更可靠。 */
private const val MOVE_MIN_DRIFT = 20

/**
 * M1 预览界面：SurfaceView 显示 CarLife 画面，触摸按视频坐标回传给 CarLife 引擎（9340）。
 *
 * 使用顺序（车内，只连蓝牙不插 USB）：
 *  1. 手机蓝牙连 MIB3；
 *  2. 荣耀车联 → CarLife → 无线 WLAN 连接 → 停在「正在连接」；
 *  3. 回本 App 点「启动」。
 */
class MainActivity : Activity() {

    companion object {
        /** AA 服务器跨 Activity 生命周期存活（切后台/开设置销毁界面时不丢车机会话）。 */
        @Volatile
        private var sharedAaServer: AaHeadUnitServer? = null
    }

    private var service: CarLifeService? = null
    private var bound = false

    // 触摸状态机：单指走单点通道；第二根手指按下后整笔手势切到复合动作通道
    private var singleDown = false
    private var multiActive = false
    private var secondDown = false
    private var secondX = 0
    private var secondY = 0
    private var touchLogBudget = 0
    private var multiDebugBudget = 0
    
    // 单指拖动判定：从按下点累计位移超过阈值才算拖动，之前一律不发 MOVE
    // （防止慢点击/手指微抖被引擎当成滚动，导致"按钮点不动"）
    private var lastSentMoveX = 0
    private var lastSentMoveY = 0
    private var lastMoveAtMs = 0L
    private var downIsDrag = false

    // 单击/双击/长按判定（官方旧触摸模式：DOWN 发 ACTION_BEGIN，点按发 SINGLE_CLICK）
    private var downAtMs = 0L
    private var downViewX = 0
    private var downViewY = 0
    private var lastTapAtMs = 0L
    private var lastTapX = 0
    private var lastTapY = 0


    /** 官方旧模式手势状态机：单指点击只发 SINGLE_CLICK，拖动才发 DOWN/MOVE/150ms UP。 */
    private var officialGestureMode = true
    private var officialScrollStarted = false
    private var officialWasScroll = false
    private var officialMultiFallback = false
    private var officialDownSent = false
    private var officialDoubleCandidate = false
    private var officialDownX = 0
    private var officialDownY = 0
    private var officialLastX = 0
    private var officialLastY = 0
    private var officialLastMoveAt = 0L
    private var officialLastTapAt = 0L
    private var officialLastTapX = 0
    private var officialLastTapY = 0
    private val officialUpHandler = Handler(Looper.getMainLooper())
    private val officialUpRunnable = Runnable {
        if (officialScrollStarted) {
            service?.touchSingle(CarLifeProtocol.TOUCH_UP, officialLastX, officialLastY)
            officialScrollStarted = false
            officialDownSent = false
            RingLog.add("TOUCH: 官方手势 150ms 无移动 → UP")
        }
    }

    private lateinit var surfaceView: PreviewLayout
    private lateinit var textureView: TextureView
    private var previewSurface: Surface? = null
    private lateinit var statusDetail: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private var aaServer: AaHeadUnitServer? = null

    /** USB 车机已接入、但还没启动 CarLife 会话（等 Service 绑定后自动启动）。 */
    private var pendingUsbAutoStart = false
    private var autoStartLogged = false

    /** 车机接入后若 CarLife 还没就绪，每 3 秒自动重试（用户中途去开「正在连接」也能自动连上）。 */
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var engineWaiting = false

    /** 当前占位画面实际使用的编码参数（-1=编码器默认），用于判断是否需要按 CarLife 参数重新生成。 */
    private var placeholderProfileIdc = -1
    private var placeholderLevelIdc = -1

    /** 当前占位画面尺寸（跟随车机选中的 AA 配置动态调整；默认按 1080p 方案生成）。 */
    private var placeholderWidth = 1920
    private var placeholderHeight = 1080

    // 状态位：分开展示「CarLife 状态」和「AA/车机状态」，避免互相覆盖
    private var aaAttached = false
    private var aaSessionReady = false
    private var aaProjecting = false
    private var carlifeAlive = false
    private var carlifeFailed = false
    private var carlifePaused = false

    /** 一键连接主页控件 */
    private lateinit var btnMainConnect: Button
    private lateinit var chkShowPreview: CheckBox
    private lateinit var previewFrame: FrameLayout
    private lateinit var chkLocalAudio: CheckBox
    private lateinit var hintExtra: TextView
    private var firstUiStateApplied = false

    /** 0=自动 1=1280x720 2=1920x1080 3=800x480 */

    /** AA 车机选定的视频画布尺寸（触摸坐标换算用，默认 1280x720）。 */
    @Volatile
    private var aaVideoWidth = 1280

    /** AA 车机两指的当前位置（已映射到 CarLife 画布），用于双指 MOVE。 */
    private val aaPointerX = android.util.SparseIntArray()
    private val aaPointerY = android.util.SparseIntArray()
    private var aaDualLogBudget = 20

    /** 首次触摸时记录 View 与触摸目标的尺寸，用于核对坐标映射。 */
    private var touchMapLogged = false

    /** 复刻预览触摸的 CPTCL 状态机：第二指按下后 multiActive=true。 */
    private var aaMultiActive = false
    private var aaSecondDown = false
    private var aaSecondX = 0
    private var aaSecondY = 0

    @Volatile
    private var aaVideoHeight = 720

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? CarLifeService.LocalBinder ?: return
            service = local.service()
            surfaceView.setVideoSize(service?.previewWidth ?: 1412, service?.previewHeight ?: 654)
            service?.onResolutionMismatch = { msg -> showReconnectHint(msg) }
            service?.setLocalAudioEnabled(chkLocalAudio.isChecked)
            // M3：把 CarLife 视频/音频转给 AA 手机端（只在台架连接时有用）
            service?.aaVideoSink = { frame -> aaServer?.sendVideoFrame(frame) }
            service?.aaAudioSink = { data, length -> aaServer?.sendAudioPcm(data, length) }
            service?.aaAudioFormatSink = { rate, channels -> aaServer?.setSourceAudioFormat(rate, channels) }
            attachPreviewSurfaceIfReady()
            refreshMainButton()
                // 若绑定完成时车机已经接入（USB 事件先于 Service 绑定），这里补一次自动启动
            tryAutoStartForUsb()
            if (service?.isSessionRunning() == true) {
                setStatus(getString(R.string.status_waiting_car))
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onResume() {
        super.onResume()
        if (!firstUiStateApplied) {
            firstUiStateApplied = true
            chkShowPreview.isChecked = false
            findViewById<TextView>(R.id.hint_preview).visibility = View.GONE
            previewFrame.visibility = View.GONE
            chkLocalAudio.isChecked = false
            service?.setLocalAudioEnabled(false)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 白底状态栏配深色图标，避免时间/电量看不见
        runCatching {
            window.statusBarColor = Color.WHITE
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        }
        RingLog.init(applicationContext)
        // 投屏期间屏幕常亮（manifest 的 activity 没有 keepScreenOn 属性，必须代码加）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surface_view)
        textureView = findViewById(R.id.preview_texture)
        statusDetail = findViewById(R.id.status_detail)
        logText = findViewById(R.id.log_text)
        logScroll = findViewById(R.id.log_scroll)
        btnMainConnect = findViewById(R.id.btn_main_connect)
        chkShowPreview = findViewById(R.id.chk_show_preview)
        previewFrame = findViewById(R.id.preview_frame)
        chkLocalAudio = findViewById(R.id.chk_local_audio)
        hintExtra = findViewById(R.id.hint_extra)

        // 两个开关每次启动默认关闭；系统恢复勾选状态不改默认值。
        chkShowPreview.isChecked = false
        val hintPreview = findViewById<TextView>(R.id.hint_preview)
        previewFrame.visibility = View.GONE
        hintPreview.visibility = View.GONE
        chkShowPreview.setOnCheckedChangeListener { _, checked ->
            previewFrame.visibility = if (checked) View.VISIBLE else View.GONE
            hintPreview.visibility = previewFrame.visibility
            RingLog.add("UI: 画面预览 " + (if (checked) "开启" else "关闭"))
        }
        // 本机劫持声音开关
        chkLocalAudio.isChecked = false
        chkLocalAudio.setOnCheckedChangeListener { _, checked ->
            service?.setLocalAudioEnabled(checked)
        }
        chkShowPreview.post {
            if (!firstUiStateApplied) {
                firstUiStateApplied = true
                chkShowPreview.isChecked = false
                previewFrame.visibility = View.GONE
                hintPreview.visibility = View.GONE
                chkLocalAudio.isChecked = false
                service?.setLocalAudioEnabled(false)
            }
        }

        // 日志允许长按选择复制（高级区另有"复制日志"按钮一键复制全部）
        // 日志不再设为可选中，避免抢焦点把外层 ScrollView 滚到日志；复制用按钮。
        logText.isFocusable = false
        logText.isFocusableInTouchMode = false
        logScroll.isFocusable = false
        findViewById<Button>(R.id.btn_log_bottom).setOnClickListener {
            logScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
        // 日志内部滚动优先：能滚时禁止外层 ScrollView 抢；到顶/底后交还整页。
        var logDownY = 0f
        logScroll.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    logDownY = event.y
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - logDownY
                    if (dy != 0f) {
                        val canScroll = if (dy > 0) v.canScrollVertically(-1) else v.canScrollVertically(1)
                        v.parent?.requestDisallowInterceptTouchEvent(canScroll)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        findViewById<ScrollView>(R.id.root_scroll).post {
            findViewById<ScrollView>(R.id.root_scroll).scrollTo(0, 0)
        }
        RingLog.listener = { line ->
            runOnUiThread {
                appendLog(line)
                updateStatusFromLog(line)
            }
        }
        // AA 服务器跨 Activity 生命周期存活：打开设置/切后台导致界面销毁时，车机会话不跟着断
        aaServer = sharedAaServer ?: AaHeadUnitServer(
            assets = applicationContext.assets,
            onLog = { RingLog.add(it) },
            onTouch = { action, x, y, id -> handleAaTouch(action, x, y, id) },
            onMultiTouch = { points -> handleAaMultiTouch(points) },
        ).also { sharedAaServer = it }
        aaServer?.updateHandlers(
            log = { RingLog.add(it) },
            touch = { action, x, y, id -> handleAaTouch(action, x, y, id) },
            multiTouch = { points -> handleAaMultiTouch(points) },
        )
        // 车机分辨率记忆：首次学习，之后配置列表能对上就复用（同 CarPToCarL CarCanvasMemory）
        aaServer?.loadDisplayProfile = { loadDisplayProfile() }
        aaServer?.saveDisplayProfile = { profile -> saveDisplayProfile(profile) }
        aaServer?.onReconnectSuggested = { msg -> showReconnectHint(msg) }
        // 后台生成一帧 1280x720 占位画面：AA 连上但 CarLife 还没出帧时，先发给车机避免黑屏超时
        if (aaServer?.hasPlaceholder() != true) {
            generatePlaceholder(aaServer)
        }
        // 车机选定的 AA 视频画布尺寸：触摸换算按它，CarLife 编码分辨率也跟着它走
        aaServer?.currentStreamSizeProvider = {
            val svc = service
            if (svc != null && svc.isSessionRunning()) svc.videoWidth to svc.videoHeight else null
        }
        aaServer?.onVideoConfigSelected = { w, h ->
            aaVideoWidth = w
            aaVideoHeight = h
            RingLog.add("AA: 车机视频画布 ${w}x$h（触摸换算 + CarLife 编码分辨率跟随）")
            runOnUiThread {
                service?.ensureVideoSize(w, h)
                service?.applyCarSafeArea(aaServer?.carSafeArea)
                updateResolutionHint(w, h)
                // 占位画面尺寸跟随车机选中的配置动态生成（1280x720 / 1920x1080 / 800x480）
                if (w != placeholderWidth || h != placeholderHeight) {
                    RingLog.add("AA: 车机配置 ${w}x$h，重新生成同尺寸占位画面")
                    aaServer?.clearPlaceholder()
                    generatePlaceholder(aaServer, w, h)
                }
                // AA 已经知道车机分辨率了，如果 CarLife 还没启动，现在按这个分辨率启动
                tryAutoStartForUsb()
            }
        }
        // 本机蓝牙地址：车机蓝牙服务通道靠它确认"投屏的是哪台手机"
        aaServer?.phoneBluetoothAddress = loadBtMac()
        aaServer?.phoneDisplayName = loadPhoneName()
        logText.text = RingLog.snapshot()
        // M4：如果本 Activity 是被 USB accessory 接入事件拉起的，直接开 AAP 会话
        handleUsbIntent(intent)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                previewSurface?.release()
                val s = Surface(st)
                previewSurface = s
                service?.attachSurface(s)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                // 这里绝对不能 requestLayout：TextureView 尺寸变化会再次触发该回调，
                // 形成 measure/layout 死循环，把 UI 线程和整机输入拖死。
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                service?.detachSurface()
                previewSurface?.release()
                previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        surfaceView.setOnTouchListener { v, event ->
            // 预览区自己处理滑动：按下时禁止外层 ScrollView 拦截，抬手/取消再恢复。
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            try {
                handleTouch(v, event)
            } catch (e: Exception) {
                // 触摸路径任何异常都不能让 App 闪退：记录日志并吞掉
                RingLog.add("TOUCH: 处理异常 ${e.javaClass.simpleName}: ${e.message}")
                true
            }
        }

        // ===== 一键连接：唯一主按钮 =====
        btnMainConnect.setOnClickListener { onMainConnectClick() }


        // 单击补发/MOVE索引/新触摸实验项已移除；单指走官方手势，双指回退 CPTCL。

        findViewById<Button>(R.id.btn_clear_display_profile).setOnClickListener {
            clearDisplayProfiles()
            Toast.makeText(this, "已清除分辨率记忆，下次连接重新学习", Toast.LENGTH_SHORT).show()
        }

        // 本机蓝牙地址：车机蓝牙服务通道靠它确认"投屏的是哪台手机"
        val editBtMac = findViewById<EditText>(R.id.edit_bt_mac)
        editBtMac.setText(loadBtMac().orEmpty())
        findViewById<Button>(R.id.btn_save_bt_mac).setOnClickListener {
            val normalized = normalizeBtMac(editBtMac.text.toString())
            if (normalized == null) {
                Toast.makeText(this, "蓝牙地址格式不对，应类似 AA:BB:CC:DD:EE:FF", Toast.LENGTH_SHORT).show()
            } else {
                getSharedPreferences(CarLifeService.PREFS, MODE_PRIVATE).edit()
                    .putString(CarLifeService.KEY_PHONE_BT_MAC, normalized).apply()
                aaServer?.phoneBluetoothAddress = normalized
                Toast.makeText(this, "已保存：" + normalized, Toast.LENGTH_SHORT).show()
                RingLog.add("AA: 本机蓝牙地址改为 " + normalized + "（下次连接生效）")
            }
        }

        // 调试：USB 测试图（不依赖 CarLife），用来单独验证 AA 视频链路
        val chkTestPattern = findViewById<CheckBox>(R.id.chk_test_pattern)
        chkTestPattern.isChecked = aaServer?.useTestPattern == true
        chkTestPattern.setOnCheckedChangeListener { _, checked ->
            aaServer?.useTestPattern = checked
            RingLog.add("AA: USB 测试图 ${if (checked) "开启（不依赖 CarLife）" else "关闭"}")
        }

        refreshMainButton()
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            bound = bindService(
                Intent(this, CarLifeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }
        // 前台服务：保持会话存活；Android 12+ 从可见 Activity 启动合法
        startForegroundService(Intent(this, CarLifeService::class.java))
        syncUsbAccessory()
    }

    /**
     * Activity 恢复时检查系统当前是否已有 accessory。
     *
     * 车机 attach 的 intent 只保证送到 Activity；如果插线时前台是荣耀车联、我们的 Activity 在后台，
     * 这次 attach 可能被漏掉。这里以 UsbManager 的实际状态为准补一次启动，避免“手机等车机、
     * 车机却停在上一次的占位帧”。
     */
    private fun syncUsbAccessory() {
        val usb = getSystemService(UsbManager::class.java) ?: return
        val accessory = usb.accessoryList?.firstOrNull()
        if (accessory == null) {
            if (aaServer?.isAccessoryAttached() != true) aaAttached = false
            return
        }
        aaAttached = true
        if (aaServer?.isUsbSessionRunning() != true) {
            RingLog.add("USB: Activity 恢复时发现已接入的 accessory，补启动 AA 会话")
            aaServer?.startAccessorySession(usb, accessory)
            pendingUsbAutoStart = true
            autoStartLogged = false
            runOnUiThread { tryAutoStartForUsb() }
        }
        recomputeStatus()
    }

    override fun onStop() {
        super.onStop()
        RingLog.add("ACT: onStop")
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        previewSurface?.release()
        previewSurface = null
        RingLog.add("ACT: onDestroy")
        // 关键：不 stop() AA 服务器。打开设置/切后台时 Activity 可能被销毁，
        // 一旦 stop 就会把车机投屏会话一起掐断。服务器由静态持有，界面重建后重新绑定回调。
        cancelAutoStartRetry()
        aaServer?.updateHandlers(log = { RingLog.add(it) }, touch = null, multiTouch = null)
        aaServer = null
        RingLog.listener = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    /** M4：处理车机经 AOA 把手机切成 accessory 模式后的接入/断开事件。 */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                @Suppress("DEPRECATION")
                val accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as? UsbAccessory
                RingLog.add(
                    "USB: accessory 接入 manufacturer=${accessory?.manufacturer} " +
                        "model=${accessory?.model} version=${accessory?.version}",
                )
                if (accessory != null) {
                    aaAttached = true
                    // 车机接入：把服务拉成"已启动的前台服务"，进设置/切后台时进程不被清理，AA 会话不断
                    runCatching {
                        startForegroundService(
                            Intent(this, CarLifeService::class.java)
                                .setAction(CarLifeService.ACTION_AA_ATTACHED),
                        )
                    }.onFailure { RingLog.add("SERVICE: 启动前台服务失败 - ${it.message}") }
                    val usb = getSystemService(UsbManager::class.java)
                    // CarLife 没在跑时先清空旧缓存，避免把上一会话的画面发给车机
                    if (service?.isSessionRunning() != true) aaServer?.invalidateVideoCache()
                    aaServer?.startAccessorySession(usb, accessory)
                    // 车机已接入：立即自动启动 CarLife（不等 AA 服务发现），尽量缩短车机等首帧的时间
                    pendingUsbAutoStart = true
                    autoStartLogged = false
                    runOnUiThread { tryAutoStartForUsb() }
                }
            }
            UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                RingLog.add("USB: accessory 已断开，停止 AAP 会话")
                pendingUsbAutoStart = false
                autoStartLogged = false
                engineWaiting = false
                cancelAutoStartRetry()
                aaServer?.stopSessions()
                runCatching {
                    startService(
                        Intent(this, CarLifeService::class.java)
                            .setAction(CarLifeService.ACTION_AA_DETACHED),
                    )
                }
            }
        }
    }

    private fun appendLog(line: String) {
        if (isFinishing) return
        if (isDestroyed) return
        logText.append(line)
        logText.append("\n")
    }

    // ===== 一键连接：状态与按钮 =====

    private fun setStatus(text: String) {
        if (isFinishing) return
        statusDetail.text = text
    }

    /** 主按钮点击：未运行就启动 CarLife 会话，运行中就断开。 */
    private fun onMainConnectClick() {
        val svc = service
        if (svc == null) {
            setStatus("服务尚未连接，请稍候再试…")
            RingLog.add("M1: 服务尚未连接，稍后再点")
            return
        }
        if (svc.isSessionRunning()) {
            svc.stopSession("用户点断开")
            // 只暂停视频流、保留 AA 会话：之后重新连 CarLife 可以继续投屏（不用重新插线）
            aaServer?.pauseVideoForCarLife()
            carlifeAlive = false
            carlifeFailed = false
            carlifePaused = true
            setStatus(getString(R.string.status_carlife_paused))
            hintExtra.visibility = View.GONE
            refreshMainButton()
            return
        }
        requestNotificationPermission()
        ensureBatteryOptimizationExempt()
        setStatus(getString(R.string.status_connecting))
        svc.startSession()
        aaServer?.resumeVideoForCarLifeIfPaused()
        refreshMainButton()
    }

    /** 车机 USB 已接入且 CarLife 会话未启动时自动启动（插车即投屏，无需再点按钮）。 */
    private fun tryAutoStartForUsb() {
        if (!pendingUsbAutoStart) return
        val svc = service ?: return
        if (svc.isSessionRunning()) {
            pendingUsbAutoStart = false
            refreshMainButton()
            return
        }
        // 不清 pending：若这次因 CarLife 引擎未就绪失败，AA 配置回调/Service 绑定完成时还会再试一次
        if (!autoStartLogged) {
            autoStartLogged = true
            RingLog.add("USB: 车机已接入，自动启动 CarLife 会话（无需手动点连接）")
        }
        requestNotificationPermission()
        ensureBatteryOptimizationExempt()
        setStatus(getString(R.string.status_connecting))
        svc.startSession()
        aaServer?.resumeVideoForCarLifeIfPaused()
        refreshMainButton()
        scheduleAutoStartRetry()
    }

    /** CarLife 没就绪时定时重试：用户在插车后才打开「正在连接」也能自动连上。 */
    private fun scheduleAutoStartRetry() {
        if (!pendingUsbAutoStart) return
        retryRunnable?.let { retryHandler.removeCallbacks(it) }
        val r = Runnable {
            retryRunnable = null
            if (!pendingUsbAutoStart) return@Runnable
            if (service?.isSessionRunning() == true) {
                pendingUsbAutoStart = false
                refreshMainButton()
                return@Runnable
            }
            tryAutoStartForUsb()
            scheduleAutoStartRetry()
        }
        retryRunnable = r
        retryHandler.postDelayed(r, 3000)
    }

    private fun cancelAutoStartRetry() {
        retryRunnable?.let { retryHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    /** 荣耀/MagicOS 的后台清理会冻结进程、断掉 CarLife 心跳；引导用户把 App 加入电池白名单。 */
    private fun ensureBatteryOptimizationExempt() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
            RingLog.add("POWER: 申请电池优化白名单（后台清理会断开会话）")
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure { RingLog.add("POWER: 申请电池白名单失败 - ${it.message}") }
    }

    /** 根据会话状态刷新主按钮文字。 */
    private fun refreshMainButton() {
        if (isFinishing) return
        val running = service?.isSessionRunning() == true
        btnMainConnect.text = getString(
            if (running) R.string.btn_disconnect_main else R.string.btn_connect_main,
        )
    }

    /** 分辨率不对/首次学习时的提示：Toast + 界面 hint + 日志，提示重新连接一次。 */
    private fun showReconnectHint(msg: String) {
        RingLog.add("UI: 分辨率提示 - " + msg)
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            if (isDestroyed) return@runOnUiThread
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            hintExtra.text = msg
            hintExtra.visibility = View.VISIBLE
        }
    }

    /** 读取上次学习到的车机显示档案（按车机配置签名存，兼容旧版单份档案）。 */
    private fun loadDisplayProfile(): AaDisplayProfile? {
        val sp = getSharedPreferences(CarLifeService.PREFS, MODE_PRIVATE)
        val key = aaServer?.displayProfileKey.orEmpty()
        if (key.isNotEmpty()) {
            val w = sp.getInt("profile_${key}_w", 0)
            val h = sp.getInt("profile_${key}_h", 0)
            val sw = sp.getInt("profile_${key}_sw", 0)
            val sh = sp.getInt("profile_${key}_sh", 0)
            val dpi = sp.getInt("profile_${key}_dpi", 0)
            if (w in 320..4096 && h in 180..2304 && sw in 1..w && sh in 1..h) {
                return AaDisplayProfile(w, h, sw, sh, dpi)
            }
        }
        // 旧版单份档案兜底（升级前记住的车机）
        val w = sp.getInt("car_display_w", 0)
        val h = sp.getInt("car_display_h", 0)
        val sw = sp.getInt("car_safe_w", 0)
        val sh = sp.getInt("car_safe_h", 0)
        val dpi = sp.getInt("car_display_dpi", 0)
        if (w !in 320..4096 || h !in 180..2304) return null
        if (sw !in 1..w || sh !in 1..h) return null
        return AaDisplayProfile(w, h, sw, sh, dpi)
    }

    /** 记住本次车机显示档案：按车机 key 存一份，同时更新最后使用的全局安全区供提前 98311 用。 */
    private fun saveDisplayProfile(profile: AaDisplayProfile) {
        if (profile.width !in 320..4096 || profile.height !in 180..2304) return
        val sp = getSharedPreferences(CarLifeService.PREFS, MODE_PRIVATE)
        val key = aaServer?.displayProfileKey.orEmpty()
        val samePerKey = key.isNotEmpty() &&
            sp.getInt("profile_${key}_w", 0) == profile.width &&
            sp.getInt("profile_${key}_h", 0) == profile.height &&
            sp.getInt("profile_${key}_sw", 0) == profile.safeWidth &&
            sp.getInt("profile_${key}_sh", 0) == profile.safeHeight
        val sameLegacy = sp.getInt("car_display_w", 0) == profile.width &&
            sp.getInt("car_display_h", 0) == profile.height &&
            sp.getInt("car_safe_w", 0) == profile.safeWidth &&
            sp.getInt("car_safe_h", 0) == profile.safeHeight
        if (samePerKey && sameLegacy) return
        val e = sp.edit()
        if (key.isNotEmpty()) {
            e.putInt("profile_${key}_w", profile.width)
            e.putInt("profile_${key}_h", profile.height)
            e.putInt("profile_${key}_sw", profile.safeWidth)
            e.putInt("profile_${key}_sh", profile.safeHeight)
            e.putInt("profile_${key}_dpi", profile.dpi)
        }
        e.putInt("car_display_w", profile.width)
        e.putInt("car_display_h", profile.height)
        e.putInt("car_safe_w", profile.safeWidth)
        e.putInt("car_safe_h", profile.safeHeight)
        e.putInt("car_display_dpi", profile.dpi)
        e.apply()
        RingLog.add("AA: 已记住车机分辨率 ${profile.width}x${profile.height}（安全区 ${profile.safeWidth}x${profile.safeHeight}，档案=$key）")
    }

    /** 清除所有车机分辨率记忆；下次连接重新学习。 */
    private fun clearDisplayProfiles() {
        val sp = getSharedPreferences(CarLifeService.PREFS, MODE_PRIVATE)
        val e = sp.edit()
        sp.all.keys.forEach { k ->
            if (k.startsWith("profile_") || k.startsWith("car_display_") || k.startsWith("car_safe_")) {
                e.remove(k)
            }
        }
        e.apply()
        RingLog.add("AA: 已清除全部车机分辨率记忆")
    }

    /** 预览按实际码流比例重排（CarLife 1412x654 ≈ 2.16:1）。 */
    private fun updatePreviewAspect() {
        val svc = service ?: return
        surfaceView.setVideoSize(svc.previewWidth, svc.previewHeight)
    }

    /** 从日志事件更新独立状态位，再组合出用户可读的状态（CarLife 与 AA/车机互不覆盖）。 */
    private fun updateStatusFromLog(line: String) {
        if (line.contains("记录编码参数")) maybeRegeneratePlaceholder()
        if (line.contains("★ 首帧") || line.contains("TRANSCODE: 源码流") ||
            line.contains("请求引擎按车机比例编码") || line.contains("视频请求分辨率") ||
            line.contains("VIDEO: 解码输出")
        ) {
            updatePreviewAspect()
        }
        var changed = true
        when {
            line.contains("USB: accessory 已断开") -> {
                aaAttached = false
                aaSessionReady = false
                aaProjecting = false
            }
            line.contains("USB: accessory 接入") -> {
                aaAttached = true
                aaSessionReady = false
                aaProjecting = false
                carlifeFailed = false
            }
            line.contains("AA: 会话结束") -> {
                aaSessionReady = false
                aaProjecting = false
                carlifePaused = false
            }
            line.contains("AA: 会话就绪") -> {
                aaSessionReady = true
                carlifeFailed = false
            }
            line.contains("视频焦点 PROJECTED") || line.contains("切换到 CarLife 实时画面") -> {
                aaProjecting = true
            }
            line.contains("视频焦点 NATIVE") -> {
                aaProjecting = false
            }
            line.contains("版本匹配成功") || line.contains("★ 首帧") -> {
                carlifeAlive = true
                carlifeFailed = false
                carlifePaused = false
                engineWaiting = false
            }
            line.contains("断开会话（") || line.contains("CARLIFE: 会话结束") ||
                line.contains("7240 未连通") || line.contains("控制通道断开") ||
                line.contains("7240 未监听") -> {
                carlifeAlive = false
                carlifeFailed = line.contains("失败") || line.contains("未连通") ||
                    line.contains("未监听")
                if (line.contains("7240 未监听") || line.contains("7240 未连通")) {
                    engineWaiting = true
                }
                if (carlifeFailed) aaServer?.invalidateVideoCache()
            }
            else -> changed = false
        }
        if (!changed) return
        recomputeStatus()
        refreshMainButton()
    }

    /** 若当前是按 CarLife 已有分辨率投屏、而车机支持更高原生分辨率，则提示如何切换。 */
    private fun updateResolutionHint(selectedW: Int, selectedH: Int) {
        val native = aaServer?.carPreferredVideoSize
        val running = service?.isSessionRunning() == true
        if (running && native != null && (native.first > selectedW || native.second > selectedH)) {
            hintExtra.text = getString(
                R.string.hint_native_resolution,
                selectedW,
                selectedH,
                native.first,
                native.second,
            )
            hintExtra.visibility = View.VISIBLE
        } else {
            hintExtra.visibility = View.GONE
        }
    }

    /** 把高级选项里的分辨率下拉框应用到 CarLifeService（未绑定时等绑定后再应用）。 */
    /** 生成/重新生成占位画面：读取 CarLife 最近一次编码参数（profile/level）来模仿。 */
    private fun generatePlaceholder(server: AaHeadUnitServer?, w: Int = placeholderWidth, h: Int = placeholderHeight) {
        val sp = getSharedPreferences("aa2carlife_prefs", MODE_PRIVATE)
        val prof = sp.getInt(CarLifeService.KEY_SPS_PROFILE, -1).takeIf { it > 0 }
        val lvl = sp.getInt(CarLifeService.KEY_SPS_LEVEL, -1).takeIf { it > 0 }
        Thread({
            val ph = PlaceholderVideo(width = w, height = h, profileIdc = prof, levelIdc = lvl)
            ph.generate()
            ph.previewPng?.let { png ->
                runCatching { java.io.File(filesDir, "placeholder_preview.png").writeBytes(png) }
            }
            if (ph.config != null && ph.idr != null) {
                server?.setPlaceholder(ph.config, ph.idr)
                placeholderProfileIdc = ph.usedProfileIdc
                placeholderLevelIdc = ph.usedLevelIdc
                placeholderWidth = w
                placeholderHeight = h
                RingLog.add(
                    "AA: 占位画面参数 profile_idc=${ph.usedProfileIdc} level_idc=${ph.usedLevelIdc}" +
                        "（模仿 CarLife=${prof != null}）",
                )
            } else {
                RingLog.add("AA: 占位画面生成失败 - ${ph.error ?: "未知原因"}")
            }
        }, "aa-placeholder").apply { isDaemon = true }.start()
    }

    /** CarLife 编码参数记录后，如果和占位画面用的参数不同，重新生成占位画面以保持一致。 */
    private fun maybeRegeneratePlaceholder() {
        val sp = getSharedPreferences("aa2carlife_prefs", MODE_PRIVATE)
        val prof = sp.getInt(CarLifeService.KEY_SPS_PROFILE, -1)
        val lvl = sp.getInt(CarLifeService.KEY_SPS_LEVEL, -1)
        if (prof > 0 && (prof != placeholderProfileIdc || lvl != placeholderLevelIdc)) {
            RingLog.add("AA: 检测到 CarLife 编码参数（profile=$prof level=$lvl），重新生成匹配的占位画面")
            generatePlaceholder(sharedAaServer ?: aaServer)
        }
    }

    /** 上报给车机的设备名：优先手机蓝牙名（车机列表里显示的就是它），再退型号。 */
    private fun loadPhoneName(): String {
        val btName = runCatching {
            android.provider.Settings.Secure.getString(contentResolver, "bluetooth_name")
        }.getOrNull()
        return btName?.trim()?.takeIf { it.isNotEmpty() } ?: Build.MODEL ?: "AA2CarLife"
    }

    /** 读已保存的本机蓝牙地址；没有就尝试系统安全设置里的兜底来源。 */
    private fun loadBtMac(): String? {
        val sp = getSharedPreferences(CarLifeService.PREFS, MODE_PRIVATE)
        val saved = sp.getString(CarLifeService.KEY_PHONE_BT_MAC, null)
        if (!saved.isNullOrBlank()) return normalizeBtMac(saved)
        // Honor/MagicOS 把 bluetooth_address 脱敏，读出来通常是 02:00:00:00:00:00
        val sys = runCatching {
            android.provider.Settings.Secure.getString(contentResolver, "bluetooth_address")
        }.getOrNull()
        return normalizeBtMac(sys)
    }

    /** 接受 11 位裸 MAC 或带分隔符的 MAC，统一成 AA:BB:CC:DD:EE:FF。 */
    private fun normalizeBtMac(raw: String?): String? {
        val hex = raw?.filter { it.isLetterOrDigit() }?.uppercase() ?: return null
        if (hex.length != 12 || hex == "020000000000") return null
        return hex.chunked(2).joinToString(":")
    }


    /** 按优先级组合状态：投屏中 > 车机握手 > CarLife 等待车机 > 车机已接入等待会话 > 失败/空闲 */
    private fun recomputeStatus() {
        val attached = aaAttached || aaServer?.isAccessoryAttached() == true
        val status = when {
            aaProjecting && carlifeAlive -> getString(R.string.status_projection)
            carlifePaused -> getString(R.string.status_carlife_paused)
            engineWaiting && attached -> getString(R.string.status_engine_waiting)
            aaSessionReady -> getString(R.string.status_car_handshake)
            attached -> getString(R.string.status_car_attached)
            carlifeFailed -> getString(R.string.status_failed)
            carlifeAlive -> getString(R.string.status_waiting_car)
            else -> getString(R.string.status_idle)
        }
        setStatus(status)
    }
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /**
     * 手机触摸 → CarLife 触控通道。
     *
     * 两套通道（与 CarPToCarL 实测一致）：
     *  - 单指：单点消息 0x68002/3/4（负载只有 x/y）；
     *  - 双指：第二根手指按下后整笔手势切到复合动作 0x68001：
     *    POINTER_DOWN = 5|(1<<8)，MOVE = **2（不带指针索引）**，
     *    POINTER_UP = 6|(1<<8)，最后一指 UP = 1。
     *    MOVE 若带索引（如 258）引擎不认——这是「双指捏合没反应」的根因。
     *
     * 坐标从 View 空间线性映射到「车机显示空间」（= 申请/解码分辨率），
     * 引擎内部会再做一次到手机屏的换算，这里不替它做 offset 换算。
     */
    private fun attachPreviewSurfaceIfReady() {
        val s = previewSurface ?: return
        if (!s.isValid) return
        service?.attachSurface(s)
    }

    private fun scheduleOfficialUp() {
        officialUpHandler.removeCallbacks(officialUpRunnable)
        officialUpHandler.postDelayed(officialUpRunnable, 150L)
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val svc = service ?: return false
        val targetWidth = svc.touchWidth.takeIf { it > 0 } ?: 1280
        val targetHeight = svc.touchHeight.takeIf { it > 0 } ?: 720
        val viewWidth = view.width.coerceAtLeast(1)
        val viewHeight = view.height.coerceAtLeast(1)

        if (!touchMapLogged) {
            touchMapLogged = true
            RingLog.add("TOUCHMAP: view=${viewWidth}x$viewHeight target=${targetWidth}x$targetHeight")
        }

        fun x(index: Int): Int =
            (event.getX(index) * targetWidth / viewWidth).toInt().coerceIn(0, targetWidth - 1)

        fun y(index: Int): Int =
            (event.getY(index) * targetHeight / viewHeight).toInt().coerceIn(0, targetHeight - 1)

        // ---- 官方旧模式单指手势（对齐 GestureListenerImp） ----
        // 点击只发 SINGLE_CLICK；拖动才补 DOWN + MOVE，150ms 无移动补 UP。
        if (officialGestureMode) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                officialMultiFallback = false
                officialDownSent = false
            }
            if (event.pointerCount > 1 || officialMultiFallback) {
                if (!officialMultiFallback) {
                    // 第二根手指出现：结束单指官方手势，切到 CPTCL 多指通道
                    officialUpHandler.removeCallbacks(officialUpRunnable)
                    if (officialScrollStarted) {
                        svc.touchSingle(CarLifeProtocol.TOUCH_UP, officialLastX, officialLastY)
                        officialScrollStarted = false
                        officialDownSent = false
                    }
                    if (!officialDownSent) {
                        svc.touchSingle(CarLifeProtocol.TOUCH_DOWN, officialDownX, officialDownY)
                        officialDownSent = true
                    }
                    singleDown = true
                    downIsDrag = true
                    multiActive = false
                    downViewX = officialDownX
                    downViewY = officialDownY
                    downAtMs = android.os.SystemClock.uptimeMillis()
                    officialMultiFallback = true
                    RingLog.add("TOUCH: 官方手势 → 双指，切 CPTCL 多指")
                }
                // 继续走下面的 CPTCL 多指分支
            } else {
                val now = android.os.SystemClock.uptimeMillis()
                when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    officialUpHandler.removeCallbacks(officialUpRunnable)
                    officialScrollStarted = false
                    officialWasScroll = false
                    officialDownX = x(0)
                    officialDownY = y(0)
                    officialLastX = officialDownX
                    officialLastY = officialDownY
                    officialLastMoveAt = now
                    // 第二下若在双击窗口内：立刻发 DOWN，不等移动阈值。
                    // 否则目标 App 的双击窗口会在等 MOVE 的过程中过期。
                    officialDoubleCandidate = officialLastTapAt > 0 &&
                        now - officialLastTapAt <= DOUBLE_TAP_MS &&
                        kotlin.math.abs(officialDownX - officialLastTapX) <= TAP_MAX_DRIFT &&
                        kotlin.math.abs(officialDownY - officialLastTapY) <= TAP_MAX_DRIFT
                    svc.touchSingle(CarLifeProtocol.TOUCH_ACTION_BEGIN, officialDownX, officialDownY)
                    if (officialDoubleCandidate) {
                        svc.touchSingle(CarLifeProtocol.TOUCH_DOWN, officialDownX, officialDownY)
                        officialDownSent = true
                        RingLog.add("TOUCH: 官方手势 BEGIN (${officialDownX},${officialDownY}) 双击候选 DOWN 立即下发")
                    } else {
                        officialDownSent = false
                        RingLog.add("TOUCH: 官方手势 BEGIN (${officialDownX},${officialDownY})")
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = x(0)
                    val my = y(0)
                    val total = kotlin.math.abs(mx - officialDownX) + kotlin.math.abs(my - officialDownY)
                    if (officialDoubleCandidate && !officialWasScroll && total >= 4) {
                        // 双击候选已经发过 DOWN，移动直接进入拖动，不再等 20px
                        officialScrollStarted = true
                        officialWasScroll = true
                        svc.touchSingle(CarLifeProtocol.TOUCH_MOVE, mx, my)
                        officialLastX = mx
                        officialLastY = my
                        officialLastMoveAt = now
                        scheduleOfficialUp()
                        RingLog.add("TOUCH: 官方手势 双击候选拖动 MOVE($mx,$my)")
                    } else if (!officialWasScroll && total >= MOVE_MIN_DRIFT) {
                        // 官方 onScroll 第一次滚动补 DOWN（用按下点）
                        svc.touchSingle(CarLifeProtocol.TOUCH_DOWN, officialDownX, officialDownY)
                        officialDownSent = true
                        officialScrollStarted = true
                        officialWasScroll = true
                        svc.touchSingle(CarLifeProtocol.TOUCH_MOVE, mx, my)
                        officialLastX = mx
                        officialLastY = my
                        officialLastMoveAt = now
                        scheduleOfficialUp()
                        RingLog.add("TOUCH: 官方手势 拖动开始 DOWN(${officialDownX},${officialDownY}) MOVE($mx,$my)")
                    } else if (officialWasScroll) {
                        if (!officialScrollStarted) {
                            // 150ms 定时器已经发过 UP，手指还在：官方行为是重新补 DOWN
                            svc.touchSingle(CarLifeProtocol.TOUCH_DOWN, officialDownX, officialDownY)
                            officialScrollStarted = true
                        }
                        val d = kotlin.math.abs(mx - officialLastX) + kotlin.math.abs(my - officialLastY)
                        if (d >= 4 || now - officialLastMoveAt >= 16) {
                            svc.touchSingle(CarLifeProtocol.TOUCH_MOVE, mx, my)
                            officialLastX = mx
                            officialLastY = my
                            officialLastMoveAt = now
                            scheduleOfficialUp()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    officialUpHandler.removeCallbacks(officialUpRunnable)
                    if (officialScrollStarted) {
                        svc.touchSingle(CarLifeProtocol.TOUCH_UP, officialLastX, officialLastY)
                        officialScrollStarted = false
                        officialDownSent = false
                        RingLog.add("TOUCH: 官方手势 拖动结束 UP(${officialLastX},${officialLastY})")
                    } else if (!officialWasScroll) {
                        if (officialDoubleCandidate) {
                            // 候选按下时已经发过 DOWN，抬手先释放再补双击
                            svc.touchSingle(CarLifeProtocol.TOUCH_UP, officialDownX, officialDownY)
                            svc.touchSingle(CarLifeProtocol.TOUCH_DOUBLE_CLICK, officialDownX, officialDownY)
                            officialLastTapAt = 0
                            RingLog.add("TOUCH: 官方手势 双击候选 UP+DOUBLE_CLICK (${officialDownX},${officialDownY})")
                        } else {
                            svc.touchSingle(CarLifeProtocol.TOUCH_SINGLE_CLICK, officialDownX, officialDownY)
                            officialLastTapAt = now
                            officialLastTapX = officialDownX
                            officialLastTapY = officialDownY
                            RingLog.add("TOUCH: 官方手势 SINGLE_CLICK (${officialDownX},${officialDownY})")
                        }
                    }
                    officialWasScroll = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    officialUpHandler.removeCallbacks(officialUpRunnable)
                    if (officialScrollStarted) {
                        svc.touchSingle(CarLifeProtocol.TOUCH_UP, officialLastX, officialLastY)
                        officialScrollStarted = false
                    }
                    officialDownSent = false
                    officialWasScroll = false
                    return true
                }
                }
                return true
            }
        }

        // 多指事件的详细日志（每次手势前 30 条），用于定位"哪些触摸真的发到了引擎/发的是什么"
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) multiDebugBudget = 30
        if (event.pointerCount >= 2 && multiDebugBudget > 0) {
            multiDebugBudget--
            val points = (0 until event.pointerCount.coerceAtMost(2))
                .joinToString(" ") { i -> "(${x(i)},${y(i)})" }
            RingLog.add(
                "TOUCH: 多指 ev action=${event.actionMasked} pc=${event.pointerCount} " +
                    "mode=CPTCL $points",
            )
        }


        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                singleDown = true
                multiActive = false
                secondDown = false
                downIsDrag = false
                touchLogBudget = 6
                downAtMs = android.os.SystemClock.uptimeMillis()
                downViewX = x(0)
                downViewY = y(0)
                lastSentMoveX = downViewX
                lastSentMoveY = downViewY
                lastMoveAtMs = downAtMs
                multiDebugBudget = 0
                svc.touchSingle(CarLifeProtocol.TOUCH_DOWN, downViewX, downViewY)
                logTouch("CPTCL ↓ ($downViewX,$downViewY)")
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (!multiActive && index == 1 && singleDown) {
                    multiActive = true
                    secondDown = true
                    secondX = x(1)
                    secondY = y(1)
                    svc.touchPointerDown(secondX, secondY)
                    logTouch("多指 ↓2 ($secondX,$secondY) → 0x6800B")
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (multiActive) {
                    if (event.pointerCount >= 2) {
                        svc.touchMoveDual(x(0), y(0), x(1), y(1))
                    }
                } else if (singleDown) {
                    val mx = x(0)
                    val my = y(0)
                    val totalDrift = kotlin.math.abs(mx - downViewX) + kotlin.math.abs(my - downViewY)
                    if (downIsDrag || totalDrift >= MOVE_MIN_DRIFT) {
                        downIsDrag = true
                        val now = android.os.SystemClock.uptimeMillis()
                        val drift = kotlin.math.abs(mx - lastSentMoveX) + kotlin.math.abs(my - lastSentMoveY)
                        if (drift >= 4 || now - lastMoveAtMs >= 32L) {
                            lastSentMoveX = mx
                            lastSentMoveY = my
                            lastMoveAtMs = now
                            svc.touchSingle(CarLifeProtocol.TOUCH_MOVE, mx, my)
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (multiActive) {
                    if (index == 1) {
                        secondX = x(1)
                        secondY = y(1)
                        svc.touchOtherPointerUp(secondX, secondY)
                        logTouch("多指 ↑2 ($secondX,$secondY) → 0x6800D")
                        secondDown = false
                    } else {
                        svc.touchPointerUp(x(index), y(index))
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (multiActive) {
                    if (secondDown) {
                        svc.touchOtherPointerUp(secondX, secondY)
                        secondDown = false
                    }
                    svc.touchSingle(CarLifeProtocol.TOUCH_UP, x(0), y(0))
                    multiActive = false
                    singleDown = false
                    logTouch("双指手势结束")
                } else if (singleDown) {
                    val now = android.os.SystemClock.uptimeMillis()
                    val upX = x(0)
                    val upY = y(0)
                    val duration = now - downAtMs
                    val drift = kotlin.math.abs(upX - downViewX) + kotlin.math.abs(upY - downViewY)
                    val isTap = duration <= TAP_MAX_MS && drift <= TAP_MAX_DRIFT
                    val upPointX = if (isTap) downViewX else upX
                    val upPointY = if (isTap) downViewY else upY
                    singleDown = false
                    svc.touchSingle(CarLifeProtocol.TOUCH_UP, upPointX, upPointY)
                    logTouch("CPTCL ↑ ($upPointX,$upPointY) ${duration}ms drift=$drift")
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (multiActive) {
                    if (secondDown) {
                        svc.touchOtherPointerUp(secondX, secondY)
                        secondDown = false
                    }
                    svc.touchSingle(CarLifeProtocol.TOUCH_UP, x(0), y(0))
                    multiActive = false
                } else if (singleDown) {
                    svc.touchSingle(CarLifeProtocol.TOUCH_UP, x(0), y(0))
                }
                singleDown = false
            }
        }

        return true
    }

    private fun logTouch(message: String) {
        if (touchLogBudget <= 0) return
        touchLogBudget--
        RingLog.add("TOUCH: $message")
    }

    // ---- M2 台架：AA 车机发来的触摸 → CarLife 触摸消息 ----

    private fun handleAaTouch(action: Int, x: Int, y: Int, pointerId: Int) {
        val svc = service
        if (svc == null) {
            RingLog.add("AA: 收到触摸但 CarLife 会话未启动（action=$action x=$x y=$y）")
            return
        }
        val (px, py) = mapAaPoint(svc, x, y)
        when (action) {
            0 -> {
                aaPointerX.clear(); aaPointerY.clear()
                aaPointerX.put(pointerId, px); aaPointerY.put(pointerId, py)
                aaMultiActive = false
                aaSecondDown = false
                svc.touchSingle(CarLifeProtocol.TOUCH_DOWN, px, py)
            }
            5 -> {
                aaPointerX.put(pointerId, px); aaPointerY.put(pointerId, py)
                aaMultiActive = true
                aaSecondDown = true
                aaSecondX = px; aaSecondY = py
                svc.touchPointerDown(px, py)
                if (aaDualLogBudget > 0) { aaDualLogBudget--; RingLog.add("TOUCH: 多指 ↓2 → 0x6800B id=$pointerId ($px,$py)") }
            }
            2 -> {
                aaPointerX.put(pointerId, px); aaPointerY.put(pointerId, py)
                // 双指 MOVE 由 handleAaMultiTouch 整包发 0x68004；双指期间的单指 MOVE 忽略，和预览 CPTCL 一致
                if (!aaMultiActive) svc.touchSingle(CarLifeProtocol.TOUCH_MOVE, px, py)
            }
            6 -> {
                if (pointerId == 0) {
                    svc.touchPointerUp(px, py) // 0x6800C 第一指抬起
                    if (aaDualLogBudget > 0) { aaDualLogBudget--; RingLog.add("TOUCH: 多指 ↑1 → 0x6800C ($px,$py)") }
                } else {
                    svc.touchOtherPointerUp(px, py) // 0x6800D 第二指抬起
                    aaSecondDown = false
                    if (aaDualLogBudget > 0) { aaDualLogBudget--; RingLog.add("TOUCH: 多指 ↑2 → 0x6800D ($px,$py)") }
                }
                aaPointerX.delete(pointerId); aaPointerY.delete(pointerId)
            }
            1 -> {
                if (aaMultiActive && aaSecondDown) {
                    // 收尾顺序：先补第二指抬起，再 ACTION_UP，否则引擎留幽灵触点
                    svc.touchOtherPointerUp(aaSecondX, aaSecondY)
                    aaSecondDown = false
                }
                svc.touchSingle(CarLifeProtocol.TOUCH_UP, px, py)
                aaMultiActive = false
                aaPointerX.clear(); aaPointerY.clear()
            }
            else -> RingLog.add("AA: 未知触摸 action=$action")
        }
    }

    /** 双指 MOVE：一次 AA 事件里的两根手指一起映射，只发一条 0x68004。 */
    private fun handleAaMultiTouch(points: List<AaTouchPoint>) {
        val svc = service ?: return
        if (points.size < 2) {
            val p = points.firstOrNull() ?: return
            val (px, py) = mapAaPoint(svc, p.x, p.y)
            aaPointerX.put(p.id, px); aaPointerY.put(p.id, py)
            if (!aaMultiActive) svc.touchSingle(CarLifeProtocol.TOUCH_MOVE, px, py)
            return
        }
        val p0 = points[0]
        val p1 = points[1]
        val (x0, y0) = mapAaPoint(svc, p0.x, p0.y)
        val (x1, y1) = mapAaPoint(svc, p1.x, p1.y)
        aaPointerX.put(p0.id, x0); aaPointerY.put(p0.id, y0)
        aaPointerX.put(p1.id, x1); aaPointerY.put(p1.id, y1)
        if (aaMultiActive) {
            svc.touchMoveDual(x0, y0, x1, y1)
            if (aaDualLogBudget > 0) {
                aaDualLogBudget--
                RingLog.add("TOUCH: 双指 MOVE → 0x68004 ($x0,$y0) ($x1,$y1)")
            }
        } else {
            // 异常顺序（没收到第二指按下）：按单指 MOVE 容错，不硬发双指
            svc.touchSingle(CarLifeProtocol.TOUCH_MOVE, x0, y0)
        }
    }

    /** AA 安全区坐标 → CarLife 触摸画布坐标。 */
    private fun mapAaPoint(svc: CarLifeService, x: Int, y: Int): Pair<Int, Int> {
        val tw = svc.touchWidth.coerceAtLeast(1)
        val th = svc.touchHeight.coerceAtLeast(1)
        val safe = svc.carSafeArea
        val aw = (safe?.getOrNull(0)?.takeIf { it > 0 } ?: aaVideoWidth).coerceAtLeast(1)
        val ah = (safe?.getOrNull(1)?.takeIf { it > 0 } ?: aaVideoHeight).coerceAtLeast(1)
        val px = (x.toLong() * tw / aw).toInt().coerceIn(0, tw - 1)
        val py = (y.toLong() * th / ah).toInt().coerceIn(0, th - 1)
        return px to py
    }






}
