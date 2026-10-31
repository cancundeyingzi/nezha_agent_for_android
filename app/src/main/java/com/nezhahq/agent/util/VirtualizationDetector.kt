package com.nezhahq.agent.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import java.io.File

/**
 * Android 虚拟化环境检测器（单例）。
 *
 * ## 设计目标
 * 判断当前 Android 设备是否运行在虚拟化环境中（PC 模拟器、云手机、Android Studio 模拟器等），
 * 用于在哪吒探针面板上正确显示 "Physical Core" 或 "Virtual Core"，
 * 与原版 Go Agent 通过 `host.Info().VirtualizationRole` 判断的行为保持一致。
 *
 * ## 检测策略（五层分级，短路返回）
 * 1. `/proc/cpuinfo` 硬件字段关键字匹配
 * 2. `Build.*` 系统属性指纹匹配
 * 3. 虚拟化特征文件存在性检测
 * 4. 物理传感器数量检测（模拟器通常无真实传感器）
 * 5. 系统属性检测（需 Root/Shizuku 权限）
 *
 * ## 注意事项
 * - **多开/分身空间不是虚拟化**，它们共享宿主内核，不会被检测为虚拟化
 * - 结果会被缓存，整个生命周期内只检测一次，不影响后续高频采集性能
 * - 每层检测独立 try-catch，任何一层异常不影响后续层
 * - 默认为"非虚拟化"（Physical），只有明确命中特征才判定为虚拟化
 */
object VirtualizationDetector {

    /**
     * 缓存的检测结果。
     * - 非空字符串 → 虚拟化系统名称（如 "QEMU"、"VirtualBox"、"Emulator"）
     * - 空字符串 → 实体机（非虚拟化）
     * - null → 尚未执行检测
     */
    @Volatile
    private var cachedResult: String? = null

    // ── 公开 API ───────────────────────────────────────────────────────────

    /**
     * 检测当前设备是否运行在虚拟化环境中。
     *
     * @param context     Android Context，用于获取 SensorManager
     * @param isRootMode  是否处于 Root/Shizuku 提权模式（决定是否执行第 5 层检测）
     * @return 虚拟化系统名称（如 "QEMU"），实体机返回空字符串
     */
    fun detect(context: Context, isRootMode: Boolean): String {
        // 缓存命中时直接返回，避免重复检测
        cachedResult?.let { return it }

        val result = runDetection(context, isRootMode)

        // 缓存结果
        cachedResult = result

        if (result.isNotEmpty()) {
            Logger.i("VirtualizationDetector: 检测到虚拟化环境 → $result")
        } else {
            Logger.i("VirtualizationDetector: 未检测到虚拟化特征，判定为实体机")
        }

        return result
    }

    // ── 私有检测逻辑 ────────────────────────────────────────────────────────

    /**
     * 执行完整的五层虚拟化检测，任一层命中即短路返回。
     */
    private fun runDetection(context: Context, isRootMode: Boolean): String {
        // ── 第 1 层：/proc/cpuinfo 硬件字段 ─────────────────────────────────
        detectByCpuInfo()?.let { return it }

        // ── 第 2 层：Build.* 系统属性指纹 ──────────────────────────────────
        detectByBuildProps()?.let { return it }

        // ── 第 3 层：虚拟化特征文件 ────────────────────────────────────────
        detectByCharacteristicFiles()?.let { return it }

        // ── 第 4 层：物理传感器数量 ────────────────────────────────────────
        detectBySensors(context)?.let { return it }

        // ── 第 5 层：系统属性（需要 Root/Shizuku）──────────────────────────
        if (isRootMode) {
            detectBySystemProperties()?.let { return it }
        }

        // 所有检测均未命中 → 判定为实体机
        return ""
    }

    // ────────────────────────────────────────────────────────────────────────
    // 第 1 层：/proc/cpuinfo 检测
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 通过 /proc/cpuinfo 的 Hardware 和 model name 字段检测虚拟化。
     *
     * 已知特征：
     * - "goldfish" / "ranchu" → Android Studio 模拟器（QEMU 系）
     * - "QEMU" → 通用 QEMU 虚拟机
     * - "vbox" / "VirtualBox" → VirtualBox 系（Genymotion、部分模拟器）
     *
     * @return 虚拟化系统名称，未检测到返回 null
     */
    private fun detectByCpuInfo(): String? {
        return try {
            val cpuInfo = File("/proc/cpuinfo").readText()

            // 关键字到虚拟化系统名称的映射表
            val keywords = listOf(
                "goldfish"    to "QEMU",         // Android Studio 模拟器旧版
                "ranchu"      to "QEMU",         // Android Studio 模拟器新版
                "QEMU"        to "QEMU",         // 通用 QEMU
                "VirtualBox"  to "VirtualBox",   // VirtualBox 系
                "vbox"        to "VirtualBox",   // VirtualBox 缩写
                "VMware"      to "VMware",       // VMware（罕见，但存在于云手机场景）
                "Hyper-V"     to "Hyper-V",      // Microsoft Hyper-V
            )

            // 检查 Hardware 字段
            val hardwareLine = Regex("Hardware\\s*:\\s*(.+)").find(cpuInfo)?.groupValues?.get(1)
            if (hardwareLine != null) {
                for ((keyword, system) in keywords) {
                    if (hardwareLine.contains(keyword, ignoreCase = true)) {
                        return system
                    }
                }
            }

            // 检查 model name 字段（x86 模拟器环境）
            val modelLine = Regex("model name\\s*:\\s*(.+)").find(cpuInfo)?.groupValues?.get(1)
            if (modelLine != null) {
                for ((keyword, system) in keywords) {
                    if (modelLine.contains(keyword, ignoreCase = true)) {
                        return system
                    }
                }
            }

            null // 未命中
        } catch (e: Exception) {
            Logger.i("VirtualizationDetector: 读取 /proc/cpuinfo 失败: ${e.message}")
            null
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 第 2 层：Build 指纹检测
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 通过 Android Build 系统属性检测已知的模拟器 / 云手机特征。
     *
     * 各属性的检测意义：
     * - `Build.FINGERPRINT`：设备完整指纹，SDK 模拟器通常包含 "generic" 或 "sdk"
     * - `Build.HARDWARE`：底层硬件名，模拟器常见 "goldfish"、"ranchu"、"vbox86"
     * - `Build.MODEL`：设备型号，模拟器通常包含 "Emulator" 或 "SDK"
     * - `Build.MANUFACTURER`：制造商，Genymotion 模拟器会显示 "Genymotion"
     * - `Build.PRODUCT`：产品名，SDK 模拟器包含 "sdk"
     * - `Build.BOARD`：主板名，模拟器可能为 "unknown"
     * - `Build.BRAND`：品牌名，通用模拟器通常为 "generic"
     *
     * @return 虚拟化系统名称，未检测到返回 null
     */
    private fun detectByBuildProps(): String? {
        try {
            val fingerprint = Build.FINGERPRINT.lowercase()
            val model       = Build.MODEL.lowercase()
            val manufacturer = Build.MANUFACTURER.lowercase()
            val hardware    = Build.HARDWARE.lowercase()
            val product     = Build.PRODUCT.lowercase()
            val brand       = Build.BRAND.lowercase()
            val device      = Build.DEVICE.lowercase()

            // ── 精确匹配已知硬件名 ──
            when (hardware) {
                "goldfish", "ranchu"  -> return "QEMU"
                "vbox86"              -> return "VirtualBox"
            }

            // ── FINGERPRINT 特征（覆盖面最广）──
            if (fingerprint.contains("generic") && fingerprint.contains("sdk")) {
                return "Emulator"
            }
            if (fingerprint.contains("vbox")) return "VirtualBox"
            if (fingerprint.contains("test-keys") && model.contains("emulator")) {
                return "Emulator"
            }

            // ── 已知模拟器品牌 / 产品 ──
            val emulatorBrands = mapOf(
                "genymotion"  to "Genymotion",
                "nox"         to "Nox",
                "bluestacks"  to "BlueStacks",
                "bignox"      to "Nox",
                "ttvm"        to "TianTian",     // 天天模拟器
                "mumu"        to "MuMu",         // 网易 MuMu
                "ldplayer"    to "LDPlayer",     // 雷电模拟器
                "microvirt"   to "MEmu",         // 逍遥安蒙
            )

            for ((keyword, system) in emulatorBrands) {
                if (manufacturer.contains(keyword) || product.contains(keyword)
                    || brand.contains(keyword) || device.contains(keyword)
                    || model.contains(keyword)
                ) {
                    return system
                }
            }

            // ── MODEL 关键字 ──
            if (model.contains("android sdk built for") || model.contains("emulator")) {
                return "Emulator"
            }
            // Google 官方模拟器设备名
            if (device.startsWith("generic") && brand == "generic") {
                return "Emulator"
            }

        } catch (e: Exception) {
            Logger.i("VirtualizationDetector: Build 属性检测异常: ${e.message}")
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────────────
    // 第 3 层：特征文件检测
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 通过检测虚拟化平台特有的设备文件或共享库来判断。
     *
     * 各文件含义：
     * - `/dev/qemu_pipe`, `/dev/goldfish_pipe` → QEMU 管道设备
     * - `/dev/vboxguest`, `/dev/vboxuser` → VirtualBox Guest Additions
     * - `/system/lib/libhoudini.so` → Intel Houdini（x86 上的 ARM 指令转译库，
     *   常见于 x86 模拟器；注意：某些 Intel 平板也有此库，因此优先级较低）
     *
     * @return 虚拟化系统名称，未检测到返回 null
     */
    private fun detectByCharacteristicFiles(): String? {
        try {
            // QEMU 系设备文件
            val qemuFiles = listOf(
                "/dev/qemu_pipe",
                "/dev/goldfish_pipe",
                "/dev/socket/qemud",
                "/sys/qemu_trace",
            )
            for (path in qemuFiles) {
                if (File(path).exists()) return "QEMU"
            }

            // VirtualBox 系设备文件
            val vboxFiles = listOf(
                "/dev/vboxguest",
                "/dev/vboxuser",
            )
            for (path in vboxFiles) {
                if (File(path).exists()) return "VirtualBox"
            }

            // ARM 转译库（仅作辅助判断，不单独判定）
            // 注意：某些 Intel 芯的实体 Android 设备也可能有此文件，
            // 但配合其他指标来看仍有参考价值
            if (File("/system/lib/libhoudini.so").exists()
                && Build.HARDWARE.lowercase().let {
                    it.contains("vbox") || it.contains("generic")
                }
            ) {
                return "Emulator"
            }
        } catch (e: Exception) {
            Logger.i("VirtualizationDetector: 特征文件检测异常: ${e.message}")
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────────────
    // 第 4 层：传感器检测
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 通过物理传感器数量判断是否为虚拟设备。
     *
     * 原理：
     * - 真实手机至少具备加速度计（TYPE_ACCELEROMETER）和陀螺仪等硬件传感器
     * - 云手机和模拟器通常没有任何物理传感器（getSensorList 返回空列表）
     * - 某些高级模拟器可能模拟加速度计，但通常无磁力计和气压计
     *
     * 我们使用组合条件：如果加速度计、磁力计、气压计都不存在，判定为虚拟设备
     *
     * @return 虚拟化系统名称，未检测到返回 null
     */
    private fun detectBySensors(context: Context): String? {
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                ?: return null

            val hasAccelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
            val hasMagneticField = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
            val hasGyroscope     = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

            // 三种核心传感器全部缺失 → 极大概率为虚拟设备
            if (!hasAccelerometer && !hasMagneticField && !hasGyroscope) {
                return "CloudPhone"  // 云手机/模拟器（无法精确区分类型）
            }

            null
        } catch (e: Exception) {
            Logger.i("VirtualizationDetector: 传感器检测异常: ${e.message}")
            null
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 第 5 层：系统属性检测（需要 Root/Shizuku 权限）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 通过系统属性（getprop）检测虚拟化环境。
     *
     * 该层需要 Root 或 Shizuku 权限才能读取部分受限属性。
     * 主要检测：
     * - `ro.kernel.qemu` = "1" → QEMU 内核
     * - `ro.hardware` = "goldfish" / "ranchu" → Android 模拟器
     * - `ro.boot.hardware` = "goldfish" / "ranchu" → 同上（bootloader 传入）
     * - `ro.product.device` 包含模拟器关键字
     *
     * @return 虚拟化系统名称，未检测到返回 null
     */
    private fun detectBySystemProperties(): String? {
        return try {
            // ro.kernel.qemu 是 QEMU 内核的直接标识
            val qemuProp = RootShell.executeFirstLine("getprop ro.kernel.qemu 2>/dev/null")
            if (qemuProp?.trim() == "1") return "QEMU"

            // ro.hardware 检测
            val hwProp = RootShell.executeFirstLine("getprop ro.hardware 2>/dev/null")
            val hwLower = hwProp?.trim()?.lowercase() ?: ""
            when {
                hwLower == "goldfish" || hwLower == "ranchu"  -> return "QEMU"
                hwLower.contains("vbox")                       -> return "VirtualBox"
            }

            // ro.boot.hardware 检测（bootloader 传入的原始硬件名）
            val bootHwProp = RootShell.executeFirstLine("getprop ro.boot.hardware 2>/dev/null")
            val bootHwLower = bootHwProp?.trim()?.lowercase() ?: ""
            when {
                bootHwLower == "goldfish" || bootHwLower == "ranchu" -> return "QEMU"
                bootHwLower.contains("vbox")                         -> return "VirtualBox"
            }

            null
        } catch (e: Exception) {
            Logger.i("VirtualizationDetector: 系统属性检测异常: ${e.message}")
            null
        }
    }
}
