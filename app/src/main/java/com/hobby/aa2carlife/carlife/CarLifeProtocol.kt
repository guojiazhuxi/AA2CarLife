package com.hobby.aa2carlife.carlife

/**
 * CarLife 车机端（HU）协议常量与小工具。
 *
 * 移植自 CarPToCarL（MIT License, Copyright (c) 2026 lizhihua）的
 * `app/src/main/java/io/github/shaonialife/carptocar/carlife/CarLifeProtocol.kt`，
 * 为 M1（单侧直通）裁剪。协议事实来自其 `docs/carlife-hijack-findings.md` 的实测记录。
 *
 * 关键结论（原文）：
 *  - 手机端（MD）监听 7 个 TCP 端口，车机端主动连接；
 *  - 控制通道 8 字节头 `[u16 len][u16 rsv][u32 type]`，其余通道 12 字节头
 *    `[u32 len][u32 seq][u32 type]`（大端）；
 *  - 协议版本必须发 **3.0**（`pbVarint(1,3)+pbVarint(2,0)`）：4.0 或 3.2+ 会触发
 *    RSA/AES 加密鉴权并让引擎静默挂死，且进程级标记只置不复位；
 *  - 98386 的 `CONTENT_ENCRYPTION` 必须为 0，否则引擎发 65642（EncryptSetup）、
 *    约 20 秒后拆掉整条会话。
 */
internal object CarLifeProtocol {

    // ---- 端口（手机端监听，车机端主动连）----
    const val PORT_CMD = 7240
    const val PORT_VIDEO = 8240
    const val PORT_MEDIA = 9240
    const val PORT_TTS = 9241
    const val PORT_VR = 9242
    const val PORT_TOUCH = 9340
    const val PORT_DATA = 9440

    val ALL_PORTS = intArrayOf(PORT_CMD, PORT_VIDEO, PORT_MEDIA, PORT_TTS, PORT_VR, PORT_TOUCH, PORT_DATA)

    // ---- 控制消息 ID ----
    const val HU_PROTOCOL_VERSION = 98305
    const val MD_VERSION_MATCH = 65538
    const val HU_STATISTICS = 98343
    const val MD_INFO = 65540
    const val HU_INFO = 98307
    const val HU_MODULE_CONTROL = 98344
    const val MD_MODULE_STATUS = 65574
    const val HU_FEATURE_CONFIG = 98386
    const val MD_FEATURE_CONFIG_DONE = 65570
    const val HU_VIDEO_ENC_INIT = 98311
    const val MD_VIDEO_ENC_INIT = 65544
    const val VIDEO_ENC_START = 98313
    const val MD_ENCRYPT_SETUP = 65642
    const val HEARTBEAT = 131074

    // ---- 触控（9340，控制帧格式 8B 头）----
    const val TOUCH_DOWN = 425986 // 0x68002
    const val TOUCH_UP = 425987 // 0x68003
    const val TOUCH_MOVE = 425988 // 0x68004
    const val TOUCH_SINGLE_CLICK = 425989 // 0x68005 单击（官方旧触摸模式的点按）
    const val TOUCH_DOUBLE_CLICK = 425990 // 0x68006 双击
    const val TOUCH_ACTION_BEGIN = 425994 // 0x6800A 手势开始（官方旧触摸模式在 DOWN 时发）
    const val TOUCH_POINTER_DOWN = 425995 // 0x6800B 第二根手指按下（负载 CarlifeTouchSinglePoint{x,y}）
    const val TOUCH_POINTER_UP = 425996 // 0x6800C 第一根手指抬起（负载 SinglePoint）
    const val TOUCH_OTHER_POINTER_UP = 425997 // 0x6800D 第二根手指抬起（负载 SinglePoint）

    // ---- 音频（9240 媒体 / 9241 TTS；M1 不实现，仅保留常量）----
    const val AUDIO_HEAD_LEN = 12
    const val MUSIC_PKG_INIT = 0x30001
    const val MUSIC_PKG_STOP = 0x30002
    const val MUSIC_PKG_PAUSE = 0x30003
    const val MUSIC_PKG_RESUME = 0x30004
    const val MUSIC_PKG_NORMAL = 0x30006

    // ---- TTS（9241）：0x40001 格式宣告 / 0x40002 停止 / 0x40003 裸 PCM ----
    const val TTS_PKG_INIT = 0x40001
    const val TTS_PKG_STOP = 0x40002
    const val TTS_PKG_NORMAL = 0x40003

    /** CMD 通道 CONNECT_TYPE：5 = Wi-Fi 局域网（本机回环同样按此上报）。 */
    const val CONNECT_TYPE_WIFI = 5

    /** 每多少个 P 帧插一个 I 帧（20fps 时 = 1 秒）。 */
    const val I_FRAME_INTERVAL_DEFAULT = 20

    // ---- 帧格式（大端）----

    /** 控制通道（Channel 1）：`[u16 len][u16 reserved][u32 serviceType]`。 */
    fun packCommand(serviceType: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeU16(out, 0, payload.size)
        writeU16(out, 2, 0)
        writeU32(out, 4, serviceType)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    fun readU32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    // ---- 载荷构造 ----

    /**
     * 协议版本 3.0。
     *
     * MD 端 `updateVehicleVersion()`：`major==4` 或 `major==3 && minor>=2` 会置
     * 「新车机版本」→ 走 RSA/AES 加密鉴权且永不下发 65611 → 会话静默挂死。
     * 手机支持列表 {1.0,2.0,3.0,4.0}，3.0 既能 match=1 又走老版明文路径。
     */
    fun protocolVersion(): ByteArray = pbVarint(1, 3) + pbVarint(2, 0)

    fun statisticsInfo(): ByteArray =
        pbString(1, "12345678") + // cuid
            pbString(2, "4.0.0") + // versionName（版本字符串，不是协议版本）
            pbVarint(3, 16) + // versionCode
            pbString(4, "20029999") // channel

    /**
     * 车机设备信息（98307 HU_INFO）。
     *
     * 字段号必须与官方车机版 `CarlifeDeviceInfoProto` 一致：
     * 1 os, 2 board, 3 bootloader, 4 brand, 5 cpuAbi, 6 cpuAbi2, 7 device, 8 display…
     * 早期项目按猜测把字符串写进整型位 → 对端 protobuf 解析抛异常 → 静默断链。
     */
    fun deviceInfo(display: String): ByteArray =
        pbString(1, "Android") +
            pbString(2, "msm8998") +
            pbString(3, "unknown") +
            pbString(4, "baidu") +
            pbString(5, "arm64-v8a") +
            pbString(6, "arm64-v8a") +
            pbString(7, "CarLifeVehicle") +
            pbString(8, display)

    /**
     * 98386 特征配置。
     *
     * 两个关键开关（实测）：
     *  - `DISABLE_RESOLUTION_OPTION = 1` → `useVehicleResolution = true`，引擎按 98311
     *    上报的车机分辨率编码（不分档压成 1280×720），这是「直通不重编码」的前提；
     *  - `CONTENT_ENCRYPTION` 必须 0：置 1 会触发 65642 加密握手 → 约 20 秒后拆会话。
     */
    fun featureConfig(
        connectType: Int = CONNECT_TYPE_WIFI,
        iFrameInterval: Int = I_FRAME_INTERVAL_DEFAULT,
        useVehicleResolution: Boolean = true,
        mediaLyricSupport: Boolean = true,
        contentEncryption: Boolean = false,
    ): ByteArray {
        val items = ArrayList<ByteArray>()
        items += pbString(1, "I_FRAME_INTERVAL") + pbVarint(2, iFrameInterval)
        items += pbString(1, "CONNECT_TYPE") + pbVarint(2, connectType)
        items += pbString(1, "DISABLE_RESOLUTION_OPTION") + pbVarint(2, if (useVehicleResolution) 1 else 0)
        items += pbString(1, "CONTENT_ENCRYPTION") + pbVarint(2, if (contentEncryption) 1 else 0)
        items += pbString(1, "MEDIA_LYRIC_SUPPORT") + pbVarint(2, if (mediaLyricSupport) 1 else 0)

        var list = ByteArray(0)
        for (item in items) list += pbBytes(2, item)
        return pbVarint(1, items.size) + list + pbVarint(3, 0) +
            pbString(4, "AA2CarLife") + pbString(5, "00:11:22:33:44:55")
    }

    /**
     * 98311 视频编码初始化（9 字段）。
     *
     * 1 width / 2 height / 3 fps / 4~7 saveArea(w,h,x,y) / 8 drawUIOutsideSafeArea /
     * 9 needAdapterScreen。厂商适配层会读安全区与适配开关，只发宽高会让宽屏被拉伸。
     */
    fun videoEncoderInfo(
        width: Int,
        height: Int,
        fps: Int,
        needAdapterScreen: Boolean = false,
        saveArea: IntArray? = null,
        drawUIOutsideSafeArea: Boolean = false,
    ): ByteArray {
        var out = pbVarint(1, width) + pbVarint(2, height) + pbVarint(3, fps)
        if (saveArea != null && saveArea.size == 4) {
            out += pbVarint(4, saveArea[0]) + pbVarint(5, saveArea[1]) +
                pbVarint(6, saveArea[2]) + pbVarint(7, saveArea[3])
        }
        if (drawUIOutsideSafeArea) out += pbVarint(8, 1)
        if (needAdapterScreen) out += pbVarint(9, 1)
        return out
    }

    /** 98344 模块控制回复：把模块置 1。 */
    fun moduleControl(moduleIds: List<Int>): ByteArray {
        var out = pbVarint(1, moduleIds.size)
        for (id in moduleIds) {
            out += pbBytes(2, pbVarint(1, id) + pbVarint(2, 1))
        }
        return out
    }

    /**
     * 解析 65574 模块状态列表 → (moduleId, status)。
     *
     * 语音(4)/麦克风(6) 的 status==0 是「当前没有语音会话/走手机麦」的真实状态，
     * 不能回 98344 强行置 1（会把引擎语音态压住）。其余模块的 0 照旧回置 1。
     */
    fun parseModuleStatus(payload: ByteArray): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var index = 0
        try {
            while (index < payload.size) {
                val tag = payload[index].toInt() and 0xFF
                index++
                if (tag == 0x08) {
                    index = skipVarint(payload, index)
                } else if (tag == 0x12) {
                    if (index >= payload.size) break
                    val length = payload[index].toInt() and 0xFF
                    index++
                    val end = (index + length).coerceAtMost(payload.size)
                    var moduleId = 0
                    var status = 0
                    var cursor = index
                    while (cursor < end) {
                        val subTag = payload[cursor].toInt() and 0xFF
                        cursor++
                        when (subTag) {
                            0x08 -> if (cursor < end) moduleId = payload[cursor].toInt() and 0xFF
                            0x10 -> if (cursor < end) status = payload[cursor].toInt() and 0xFF
                            else -> break
                        }
                        cursor++
                    }
                    result += moduleId to status
                    index = end
                } else {
                    break
                }
            }
        } catch (_: RuntimeException) {
            // 截断/畸形包：返回已解析部分，不把异常抛给上层（否则整条连接会被重连）
        }
        return result
    }

    fun modulesToEnable(modules: List<Pair<Int, Int>>): List<Int> =
        modules
            .filterNot { (id, status) -> status == 0 && (id == 4 || id == 6) }
            .map { it.first }

    /** 单点触控负载 `CarlifeTouchSinglePoint{x,y}`。 */
    fun touchPoint(x: Int, y: Int): ByteArray = pbVarint(1, x) + pbVarint(2, y)

    /**
     * 双点移动负载：引擎的 `CarlifeTouchSinglePoint` 还带 `pointerx/pointery`（field 3/4），
     * 多指 MOVE 用它一次带两根手指的坐标（action 由消息 ID 0x68004 决定）。
     */
    fun touchPointDual(x: Int, y: Int, pointerX: Int, pointerY: Int): ByteArray =
        pbVarint(1, x) + pbVarint(2, y) + pbVarint(3, pointerX) + pbVarint(4, pointerY)

    // ---- 手写 protobuf（不引 protobuf 运行时）----

    fun pbVarint(field: Int, value: Int): ByteArray = tagBytes(field, 0) + varintBytes(value.toLong())

    fun pbString(field: Int, value: String): ByteArray = pbBytes(field, value.toByteArray(Charsets.UTF_8))

    fun pbBytes(field: Int, value: ByteArray): ByteArray =
        tagBytes(field, 2) + varintBytes(value.size.toLong()) + value

    private fun tagBytes(field: Int, wire: Int): ByteArray =
        varintBytes((field.toLong() shl 3) or wire.toLong())

    private fun varintBytes(input: Long): ByteArray {
        var value = input
        val out = ArrayList<Byte>(8)
        while (true) {
            val low = (value and 0x7F).toInt()
            value = value ushr 7
            if (value == 0L) {
                out.add(low.toByte())
                return out.toByteArray()
            }
            out.add((low or 0x80).toByte())
        }
    }

    private fun skipVarint(payload: ByteArray, start: Int): Int {
        var i = start
        while (i < payload.size) {
            val b = payload[i].toInt() and 0xFF
            i++
            if (b and 0x80 == 0) break
        }
        return i
    }

    private fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }
}
