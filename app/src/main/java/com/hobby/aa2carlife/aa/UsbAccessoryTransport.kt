package com.hobby.aa2carlife.aa

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import net.prototux.aare.aap.Transport
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * M4：把 AAP 会话跑在 USB accessory（AOA）通道上。
 *
 * 车机在 AA 有线连接时作为 USB host 发起 AOA 握手，把手机切成 accessory 模式，
 * 系统随后把 [UsbAccessory] 交给声明了 accessory 过滤规则的 App；打开得到的
 * ParcelFileDescriptor 是双向字节流，AAP 帧协议原样跑在上面（与 TCP 台架一致）。
 */
class UsbAccessoryTransport(
    manager: UsbManager,
    private val accessory: UsbAccessory,
) : Transport {

    override val name: String =
        "usb accessory ${accessory.manufacturer}/${accessory.model}/${accessory.version}"

    private val pfd = manager.openAccessory(accessory)
        ?: throw IllegalStateException("openAccessory 返回 null（可能没有 accessory 权限或已被别的 App 占用）")

    override val input: InputStream = FileInputStream(pfd.fileDescriptor)
    override val output: OutputStream = FileOutputStream(pfd.fileDescriptor)

    override fun close() {
        runCatching { output.close() }
        runCatching { input.close() }
        runCatching { pfd.close() }
    }
}
