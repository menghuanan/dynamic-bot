package top.bilibili

import org.jetbrains.skia.Graphics
import org.slf4j.LoggerFactory
import kotlin.math.min

/**
 * Skiko 图形库初始化配置
 * 必须在任何 Skiko 类加载之前调用
 */
object SkikoInitializer {
    private val logger = LoggerFactory.getLogger(SkikoInitializer::class.java)

    private const val resourceCacheLimitProperty = "skiko.resourceCache.maxBytes"
    private const val resourceCacheSingleAllocationLimitProperty = "skiko.resourceCache.singleAllocationMaxBytes"
    private const val defaultResourceCacheLimitBytes = 64 * 1024 * 1024
    private const val defaultResourceCacheSingleAllocationLimitBytes = 16 * 1024 * 1024

    private var initialized = false

    /**
     * 初始化 Skiko 配置
     * 强制使用软件渲染，避免硬件加速导致的 native 崩溃
     */
    @JvmStatic
    fun initialize() {
        // 只允许初始化一次，是为了避免运行中反复修改图形后端属性带来不可预期行为。
        if (initialized) return
        initialized = true

        // 强制使用软件渲染 API (关键配置)
        System.setProperty("skiko.renderApi", "SOFTWARE")

        // 禁用硬件加速
        System.setProperty("skiko.hardwareAcceleration", "false")
        System.setProperty("skiko.vsync.enabled", "false")

        // 禁用 GPU 相关功能
        System.setProperty("skiko.gpu.enabled", "false")

        // 禁用 OpenGL 和 XRender (Java2D)
        System.setProperty("sun.java2d.opengl", "false")
        System.setProperty("sun.java2d.xrender", "false")

        // 禁用 Direct3D (Windows)
        System.setProperty("sun.java2d.d3d", "false")
        System.setProperty("sun.java2d.noddraw", "true")

        // 统一保持 AWT headless=true，避免容器内尝试连接 X11 display 产生额外初始化成本。
        System.setProperty("java.awt.headless", "true")

        // 通过 Graphics API 显式写入 native 缓存上限，避免仅靠 JVM 属性在不同 Skiko 版本下被静默忽略。
        configureResourceCacheLimits()
    }

    /**
     * 将资源缓存阈值下发到 Skia native 层。
     */
    private fun configureResourceCacheLimits() {
        val totalLimitBytes = resolvePositiveIntProperty(
            resourceCacheLimitProperty,
            defaultResourceCacheLimitBytes,
        )
        val singleAllocationLimitBytes = resolvePositiveIntProperty(
            resourceCacheSingleAllocationLimitProperty,
            defaultResourceCacheSingleAllocationLimitBytes,
        )
        val safeSingleAllocationLimitBytes = min(totalLimitBytes, singleAllocationLimitBytes)

        runCatching {
            Graphics.resourceCacheTotalLimit = totalLimitBytes
            Graphics.resourceCacheSingleAllocationByteLimit = safeSingleAllocationLimitBytes

            val effectiveTotalLimit = Graphics.resourceCacheTotalLimit
            val effectiveSingleAllocationLimit = Graphics.resourceCacheSingleAllocationByteLimit
            logger.info(
                "Skia resource cache configured: totalLimit={}B (effective={}B), singleAllocationLimit={}B (effective={}B)",
                totalLimitBytes,
                effectiveTotalLimit,
                safeSingleAllocationLimitBytes,
                effectiveSingleAllocationLimit,
            )
        }.onFailure { error ->
            logger.warn("Failed to apply Skia resource cache limits via Graphics API: ${error.message}")
        }
    }

    /**
     * 解析正整数系统属性；非法值回退默认值。
     */
    private fun resolvePositiveIntProperty(propertyName: String, defaultValue: Int): Int {
        val raw = System.getProperty(propertyName)?.trim().orEmpty()
        if (raw.isEmpty()) return defaultValue

        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed <= 0) {
            logger.warn("Invalid value for {}: '{}', fallback to {}", propertyName, raw, defaultValue)
            return defaultValue
        }

        return parsed
    }
}
