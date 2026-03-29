package top.bilibili.utils

import java.time.Instant
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.ImageConfig
import top.bilibili.TimeDisplayMode

class TimeDisplayModeTest {
    private val absoluteFixtureTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private var originalTimeZone: TimeZone = TimeZone.getDefault()

    /**
     * 每个用例前都重置运行时配置，并记录默认时区，避免不同时间模式之间互相污染。
     */
    @BeforeTest
    fun setupRuntimeConfig() {
        originalTimeZone = TimeZone.getDefault()
        setRuntimeConfig(BiliConfig())
    }

    /**
     * 绝对时间格式依赖 JVM 默认时区，用例结束后恢复现场，避免影响其他测试。
     */
    @AfterTest
    fun restoreDefaultTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun `image config should default to absolute time display`() {
        assertEquals(TimeDisplayMode.ABSOLUTE, ImageConfig().timeDisplayMode)
    }

    @Test
    fun `display time should use absolute format when config chooses absolute`() {
        withDefaultTimeZone(absoluteFixtureTimeZone) {
            setRuntimeConfig(BiliConfig(imageConfig = ImageConfig(timeDisplayMode = TimeDisplayMode.ABSOLUTE)))

            assertEquals("2025年02月02日 12:22", 1738470120L.displayTime)
        }
    }

    @Test
    fun `display time should use relative format when config chooses relative`() {
        setRuntimeConfig(BiliConfig(imageConfig = ImageConfig(timeDisplayMode = TimeDisplayMode.RELATIVE)))

        val timestamp = Instant.now().epochSecond - 120

        assertEquals("2分钟前", timestamp.displayTime)
    }

    @Test
    fun `display time override should keep live draw time absolute even when config chooses relative`() {
        withDefaultTimeZone(absoluteFixtureTimeZone) {
            setRuntimeConfig(BiliConfig(imageConfig = ImageConfig(timeDisplayMode = TimeDisplayMode.RELATIVE)))

            assertEquals("2025年02月02日 12:22", 1738470120L.formatDisplayTime(TimeDisplayMode.ABSOLUTE))
        }
    }

    /**
     * 通过反射写入运行时配置，是为了复用现有单测设置方式而不改生产初始化路径。
     */
    private fun setRuntimeConfig(config: BiliConfig) {
        val configField = BiliConfigManager::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(BiliConfigManager, config)
    }

    /**
     * 为时间格式断言固定测试时区，确保 CI 与本地环境使用同一组预期值。
     */
    private fun withDefaultTimeZone(timeZone: TimeZone, block: () -> Unit) {
        val previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(timeZone)
        try {
            block()
        } finally {
            TimeZone.setDefault(previousTimeZone)
        }
    }
}
