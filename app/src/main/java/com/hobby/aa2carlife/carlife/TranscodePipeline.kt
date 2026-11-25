package com.hobby.aa2carlife.carlife

import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.SystemClock
import android.view.Surface
import com.hobby.aa2carlife.aa.SpsInfo
import com.hobby.aa2carlife.diag.RingLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarLife 码流 → AA 视频帧 的补边转码管线。
 *
 * 车机安全区（如 1412×654，2.16:1）与 AA 标准画布（1920×1080，16:9）尺寸不同，
 * 不能直接透传。这里用 MediaCodec 解码 → OpenGL 把画面按"1:1 像素"画进画布中央的安全区
 * （其余填黑）→ MediaCodec 重新编码成 AA 配置分辨率，再交给 AA 发送。
 *
 * 解码/渲染/编码都在同一个 GL 线程上串行执行；输入帧只接收最近几帧（丢旧帧防堆积）。
 */
class TranscodePipeline(
    canvasWidth: Int,
    canvasHeight: Int,
    /** 内容目标区域（相对画布左上角）；null=按画布等比铺满（手机本地预览用）。 */
    private val destRect: Rect?,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val canvasW = canvasWidth
    private val canvasH = canvasHeight

    /** 当前源码流尺寸（用于检查 CarLife 实际码流是否与车机安全区一致）。 */
    val sourceWidth: Int get() = srcW
    val sourceHeight: Int get() = srcH

    /** 当前画布尺寸（CarLifeService 判断是否需要重建管线时用）。 */
    val canvasWidthPx: Int get() = canvasW
    val canvasHeightPx: Int get() = canvasH

    /** 当前内容目标区（null=等比铺满），供 CarLifeService 判断补边几何是否变化。 */
    val destRectPx: Rect? get() = destRect

    private val running = AtomicBoolean(true)
    private val pending = ArrayDeque<ByteArray>()
    private val lock = Object()

    private var srcW = 0
    private var srcH = 0

    // EGL / GL
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglPbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglEncoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurface: Surface? = null
    private var oesTex = 0
    private var surfaceTexture: android.graphics.SurfaceTexture? = null
    private var decoderSurface: Surface? = null
    private var program = 0
    private var aPosLoc = 0
    private var aTexLoc = 0
    private var uTexMatrixLoc = 0
    private var quad: FloatBuffer? = null
    private val texMatrix = FloatArray(16)
    private var frameAvailable = false

    // codecs
    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    private val encInfo = MediaCodec.BufferInfo()
    private var encCsd: ByteArray? = null
    private var encFirst = true

    private val thread = Thread({ runLoop() }, "carlife-transcode").apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    fun submit(annexB: ByteArray) {
        if (annexB.isEmpty() || !running.get()) return
        synchronized(lock) {
            pending.addLast(annexB)
            while (pending.size > 2) pending.removeFirst() // 丢弃旧帧，避免延迟堆积
            lock.notifyAll()
        }
    }

    fun release() {
        running.set(false)
        synchronized(lock) { lock.notifyAll() }
        thread.interrupt()
    }

    // ---------- GL 线程主循环 ----------

    private fun runLoop() {
        try {
            initEgl()
            RingLog.add("TRANSCODE: 转码管线启动（画布 ${canvasW}x$canvasH，目标区=${destRect ?: "等比铺满"}）")
            val decInfo = MediaCodec.BufferInfo()
            while (running.get()) {
                val frame: ByteArray
                synchronized(lock) {
                    while (pending.isEmpty() && running.get()) {
                        lock.wait(500)
                    }
                    frame = pending.pollFirst() ?: continue
                }
                if (!running.get()) break
                ensureCodecs(frame)
                feedDecoder(frame)
                drainDecoder(decInfo)
                drainEncoder()
            }
        } catch (t: Throwable) {
            if (running.get()) {
                RingLog.add("TRANSCODE: 管线异常 - ${t.javaClass.simpleName}: ${t.message}")
                onError("${t.javaClass.simpleName}: ${t.message}")
            }
        } finally {
            releaseAll()
            RingLog.add("TRANSCODE: 转码管线已停止")
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "eglChooseConfig failed"
        }
        val cfg = configs[0]!!
        eglConfig = cfg
        eglContext = EGL14.eglCreateContext(
            eglDisplay, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        // 先用 1x1 pbuffer 建立当前上下文
        eglPbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext)) { "eglMakeCurrent failed" }

        // OES 纹理 + SurfaceTexture（解码输出目标）
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = android.graphics.SurfaceTexture(oesTex)
        surfaceTexture = st
        st.setOnFrameAvailableListener {
            synchronized(lock) {
                frameAvailable = true
                lock.notifyAll()
            }
        }
        decoderSurface = Surface(st)
        initProgram()
    }

    private fun initProgram() {
        val vs = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            uniform mat4 uTexMatrix;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """.trimIndent()
        program = link(vs, fs)
        aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        // 底边取 v=0、顶边取 v=1：SurfaceTexture 的 transformMatrix 已经包含垂直翻转，
        // 这里若再反着取（原来底边 v=1）就会翻两次，车机上画面上下颠倒。
        // 约定与 CarPToCarL 的 DualDisplayComposer.drawInputUV 一致。
        val verts = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
        quad = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(verts); position(0) }
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, src)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("shader: ${GLES20.glGetShaderInfoLog(id)}")
            return id
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("link: ${GLES20.glGetProgramInfoLog(p)}")
        return p
    }

    /**
     * 按 SPS 尺寸创建解码器/编码器/GL 输出链；**流中途换分辨率时自动重建**。
     *
     * 为什么必须处理变化：CarLife 引擎被 98386→98311 重初始化时会先吐一段默认档
     * （1280x720）的过渡码流，之后才切到申请尺寸（1412x654）。管线如果只在第一帧建
     * 一次编解码器，就会永久锁在 1280x720，后续帧全部解不出来（实车日志：
     * `TRANSCODE: 源码流 1280x720` 之后再无出帧）。
     */
    private fun ensureCodecs(frame: ByteArray) {
        val dims = parseSpsDimensions(frame)
        if (decoder != null && encoder != null) {
            val w = dims?.first
            val h = dims?.second
            if (w != null && h != null && (w != srcW || h != srcH)) {
                RingLog.add("TRANSCODE: 源码流尺寸变化 ${srcW}x$srcH -> ${w}x$h，重建编解码链")
                releaseCodecs()
            } else {
                return
            }
        }
        srcW = dims?.first ?: 1280
        srcH = dims?.second ?: 720
        RingLog.add("TRANSCODE: 源码流 ${srcW}x$srcH")

        surfaceTexture?.setDefaultBufferSize(srcW, srcH)
        val decFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, srcW, srcH)
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(decFormat, decoderSurface, null, 0)
            start()
        }

        val (enc, surface) = openEncoder()
        encoder = enc
        encoderSurface = surface
        encoderSurface?.let {
            eglEncoderSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig!!, it, intArrayOf(EGL14.EGL_NONE), 0,
            )
            check(eglEncoderSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        }
        RingLog.add("TRANSCODE: 编码画布 ${canvasW}x$canvasH，目标区=${destRect ?: "等比铺满"}")
    }

    /**
     * 创建 H.264 编码器。优先强制 **Baseline + Level 4.1**：
     * AA 侧声明的 codec 是 H264_BP，部分车机（MIB3）只按 Baseline 解码；编码器默认档
     * 可能是 High，会让车机解不出画面（实车表现：黑屏/一直停在"检查手机"）。
     * 个别编码器不支持强制档位时退回默认，不让整条管线失败。
     */
    private fun openEncoder(): Pair<MediaCodec, Surface?> {
        fun build(profile: Int?, level: Int?): Pair<MediaCodec, Surface?> {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, canvasW, canvasH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, 0x7F000789) // COLOR_FormatSurface
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (profile != null) setInteger(MediaFormat.KEY_PROFILE, profile)
                if (level != null) setInteger(MediaFormat.KEY_LEVEL, level)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val s = codec.createInputSurface()
            codec.start()
            return codec to s
        }
        return try {
            build(
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                MediaCodecInfo.CodecProfileLevel.AVCLevel41,
            )
        } catch (t: Throwable) {
            RingLog.add(
                "TRANSCODE: 强制 Baseline/4.1 失败（${t.javaClass.simpleName}: ${t.message}），回退编码器默认档",
            )
            build(null, null)
        }
    }

    private fun feedDecoder(frame: ByteArray) {
        val d = decoder ?: return
        val idx = d.dequeueInputBuffer(5_000)
        if (idx < 0) return
        val buf = d.getInputBuffer(idx) ?: return
        buf.clear()
        buf.put(frame)
        d.queueInputBuffer(idx, 0, frame.size, System.nanoTime() / 1000, 0)
    }

    private fun drainDecoder(info: MediaCodec.BufferInfo) {
        val d = decoder ?: return
        while (true) {
            val idx = d.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (idx >= 0) {
                val render = info.size > 0
                d.releaseOutputBuffer(idx, render)
                if (render) {
                    // 等 SurfaceTexture 拿到帧后渲染 + 编码
                    waitAndRender()
                }
            }
        }
    }

    private fun waitAndRender() {
        val st = surfaceTexture ?: return
        val deadline = SystemClock.elapsedRealtime() + 500
        synchronized(lock) {
            while (!frameAvailable && running.get() && SystemClock.elapsedRealtime() < deadline) {
                lock.wait(10)
            }
            if (!frameAvailable) return
            frameAvailable = false
        }
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        renderToEncoder()
        drainEncoder()
    }

    private fun renderToEncoder() {
        val es = eglEncoderSurface
        if (es == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(eglDisplay, es, es, eglContext)
        GLES20.glViewport(0, 0, canvasW, canvasH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 计算目标区域（GL 坐标原点在左下角）
        val rect = destRect ?: Rect(0, 0, canvasW, canvasH)
        val dstW = rect.width().coerceAtLeast(1)
        val dstH = rect.height().coerceAtLeast(1)
        val dstX = rect.left
        val dstY = canvasH - rect.bottom
        GLES20.glViewport(dstX, dstY, dstW, dstH)

        GLES20.glUseProgram(program)
        synchronized(lock) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        }
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        val q = quad ?: return
        q.position(0)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, q)
        q.position(2)
        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, q)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)
        EGL14.eglSwapBuffers(eglDisplay, es)
        EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext)
    }

    private fun drainEncoder() {
        val e = encoder ?: return
        while (true) {
            val idx = e.dequeueOutputBuffer(encInfo, 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = e.outputFormat
                    val csd0 = fmt.getByteBuffer("csd-0")?.let { copy(it) }
                    val csd1 = fmt.getByteBuffer("csd-1")?.let { copy(it) }
                    val c = ByteArray((csd0?.size ?: 0) + (csd1?.size ?: 0))
                    csd0?.copyInto(c, 0, 0, csd0.size)
                    csd1?.copyInto(c, csd0?.size ?: 0, 0, csd1.size)
                    encCsd = c
                    // 把编码器真实参数集打出来：实车排查需要知道车机收到的是不是
                    // Baseline / 1080p（profile 不对、或 SPS 是 1088 都会让车机不出画面）。
                    val pl = runCatching { SpsInfo.parseProfileLevel(c) }.getOrNull()
                    val dims = parseSpsDimensions(c)
                    RingLog.add(
                        "TRANSCODE: 编码器参数集 profile=${pl?.first ?: "?"} level=${pl?.second ?: "?"} " +
                            "SPS=${dims?.first ?: "?"}x${dims?.second ?: "?"}（画布 ${canvasW}x$canvasH）csd=${c.size}B",
                    )
                }
                idx >= 0 -> {
                    val buf = e.getOutputBuffer(idx) ?: continue
                    buf.position(encInfo.offset)
                    buf.limit(encInfo.offset + encInfo.size)
                    val data = ByteArray(encInfo.size)
                    buf.get(data)
                    e.releaseOutputBuffer(idx, false)
                    // 编码器在 FORMAT_CHANGED 之后可能先吐一个 CODEC_CONFIG 缓冲区（只有
                    // SPS/PPS，没有图像）。以前它被当成"关键帧"发给车机（日志里的 32B 关键帧），
                    // 车机收到"有参数集没 IDR"的关键帧后会一直等图像。必须跳过。
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        RingLog.add("TRANSCODE: 跳过编码器 CODEC_CONFIG 缓冲区（${encInfo.size}B，不是图像帧）")
                        continue
                    }
                    val key = encInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    // 车机的 CODEC_CONFIG 已经单独送过参数集；帧里再带 SPS/PPS 会造成参数集
                    // 重复，部分车机（MIB3）会逐帧 ACK 却始终不出画面（AARE 的
                    // videoHeadersOnce 就是治这个的）。这里只在第一帧关键帧里保留一份参数集，
                    // 供 App 侧提取并发送 CODEC_CONFIG；之后所有帧都剥掉 SPS/PPS。
                    val clean = stripParameterSets(data)
                    if (clean.isEmpty()) continue
                    var out = clean
                    if (key && encFirst) {
                        encFirst = false
                        val csd = encCsd
                        if (csd != null && csd.isNotEmpty()) {
                            out = ByteArray(csd.size + clean.size)
                            csd.copyInto(out, 0)
                            clean.copyInto(out, csd.size)
                        }
                        RingLog.add("TRANSCODE: 首帧关键帧附带参数集 ${csd?.size ?: 0}B，后续帧不再带 SPS/PPS")
                    }
                    if (out.isNotEmpty()) onFrame(out)
                }
            }
        }
    }

    /** 去掉 Annex-B 帧里的 SPS(7)/PPS(8)，其余 NAL 原样保留（参数集只走 CODEC_CONFIG）。 */
    private fun stripParameterSets(frame: ByteArray): ByteArray {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < frame.size) {
            if (frame[i] == 0.toByte() && frame[i + 1] == 0.toByte() && frame[i + 2] == 1.toByte()) {
                starts.add(if (i > 0 && frame[i - 1] == 0.toByte()) i - 1 else i)
                i += 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return frame
        val out = java.io.ByteArrayOutputStream(frame.size)
        for ((idx, s) in starts.withIndex()) {
            val end = if (idx + 1 < starts.size) starts[idx + 1] else frame.size
            val header = if (s + 3 < end && frame[s] == 0.toByte() && frame[s + 1] == 0.toByte() &&
                frame[s + 2] == 0.toByte() && frame[s + 3] == 1.toByte()
            ) {
                s + 4
            } else {
                s + 3
            }
            if (header >= end) continue
            val type = frame[header].toInt() and 0x1f
            if (type != 7 && type != 8) out.write(frame, s, end - s)
        }
        val result = out.toByteArray()
        return if (result.isEmpty()) frame else result
    }

    private var eglConfig: EGLConfig? = null

    /**
     * 只释放编解码器与编码器输入 Surface，保留 EGL 上下文和解码器 SurfaceTexture，
     * 供"源码流换分辨率"后原地重建（见 [ensureCodecs]）。
     */
    private fun releaseCodecs() {
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext) }
            if (eglEncoderSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglEncoderSurface) }
            }
        }
        eglEncoderSurface = EGL14.EGL_NO_SURFACE
        runCatching { encoderSurface?.release() }
        encoderSurface = null
        encCsd = null
        encFirst = true
        synchronized(lock) { frameAvailable = false }
    }

    private fun releaseAll() {
        releaseCodecs()
        runCatching { decoderSurface?.release() }
        runCatching { surfaceTexture?.release() }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            if (eglEncoderSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, eglEncoderSurface) }
            if (eglPbuffer != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, eglPbuffer) }
            runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            runCatching { EGL14.eglTerminate(eglDisplay) }
        }
        eglEncoderSurface = EGL14.EGL_NO_SURFACE
        eglPbuffer = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun copy(b: ByteBuffer): ByteArray {
        val d = b.duplicate()
        val out = ByteArray(d.remaining())
        d.get(out)
        return out
    }

    companion object {
        /** 从 SPS 解析显示宽高（找不到返回 null）。 */
        fun parseSpsDimensions(annexB: ByteArray): Pair<Int, Int>? {
            var i = 0
            while (i + 5 < annexB.size) {
                val sc = when {
                    annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte() && annexB[i + 2] == 1.toByte() -> 3
                    annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte() &&
                        annexB[i + 2] == 0.toByte() && annexB[i + 3] == 1.toByte() -> 4
                    else -> 0
                }
                if (sc == 0) { i++; continue }
                val header = i + sc
                if (header >= annexB.size) return null
                val type = annexB[header].toInt() and 0x1F
                if (type == 7) {
                    return parseSps(annexB, header + 1)
                }
                i = header + 1
            }
            return null
        }

        private fun parseSps(data: ByteArray, start: Int): Pair<Int, Int>? {
            // 去 emulation prevention + 位读取
            val rbsp = ArrayList<Byte>(data.size)
            var i = start
            while (i < data.size) {
                if (i + 2 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 3.toByte()
                ) {
                    rbsp.add(data[i]); rbsp.add(data[i + 1]); i += 3
                } else if (data[i].toInt() == 0 && i + 2 >= data.size) {
                    i++
                } else {
                    rbsp.add(data[i]); i++
                }
                if (rbsp.size > 256) break
            }
            val bits = BitReader(rbsp.toByteArray())
            return try {
                val profile = bits.u(8)
                bits.u(8); bits.u(8)
                bits.ue() // sps_id
                var chroma = 1
                if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                    chroma = bits.ue()
                    if (chroma == 3) bits.u(1)
                    bits.ue(); bits.ue(); bits.u(1)
                    val se = bits.se()
                    if (se == 0) { bits.ue(); bits.ue() }
                    else if (se == 1) {
                        bits.u(1); bits.se(); bits.se()
                        repeat(bits.ue()) { bits.se() }
                    }
                }
                bits.ue() // log2_max_frame_num
                val poct = bits.ue()
                if (poct == 0) bits.ue()
                else if (poct == 1) {
                    bits.u(1); bits.se(); bits.se()
                    repeat(bits.ue()) { bits.se() }
                }
                bits.ue() // max ref frames
                bits.u(1)
                val wMbs = bits.ue() + 1
                val hMap = bits.ue() + 1
                val frameMbsOnly = bits.u(1)
                if (frameMbsOnly == 0) bits.u(1)
                bits.u(1)
                var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
                if (bits.u(1) == 1) {
                    cropL = bits.ue(); cropR = bits.ue(); cropT = bits.ue(); cropB = bits.ue()
                }
                var w = wMbs * 16
                var h = hMap * 16 * (2 - frameMbsOnly)
                if (chroma == 1) {
                    w -= (cropL + cropR) * 2
                    h -= (cropT + cropB) * 2
                }
                w to h
            } catch (_: Throwable) {
                null
            }
        }

        private class BitReader(private val data: ByteArray) {
            private var pos = 0
            fun u(n: Int): Int {
                var v = 0
                repeat(n) {
                    val byte = data[pos ushr 3].toInt()
                    v = (v shl 1) or ((byte ushr (7 - (pos and 7))) and 1)
                    pos++
                }
                return v
            }

            fun ue(): Int {
                var z = 0
                while (u(1) == 0) z++
                return (1 shl z) - 1 + if (z == 0) 0 else u(z)
            }

            fun se(): Int {
                val k = ue()
                return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
            }
        }
    }
}
