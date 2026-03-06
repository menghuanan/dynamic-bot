package top.bilibili.tasker

import top.bilibili.BiliConfigManager
import top.bilibili.client.BiliClient
import top.bilibili.utils.logger
import java.time.Instant
import java.time.LocalTime

abstract class BiliCheckTasker(
    val taskerName: String? = null
) : BiliTasker(taskerName) {

    private val intervalTime: Int by lazy { interval }

    protected open var lowSpeedEnable = BiliConfigManager.config.enableConfig.lowSpeedEnable
    private var lowSpeedTimeRange = listOf(0, 0)  // [startHour, endHour]
    private var lowSpeedIntervalRange = listOf(60, 240)  // [minSeconds, maxSeconds]
    private var normalIntervalRange = listOf(30, 120)  // [minSeconds, maxSeconds]

    protected open var checkReportEnable = true
    private val checkReportInterval: Int = BiliConfigManager.config.checkConfig.checkReportInterval
    private var lastCheck: Long = Instant.now().epochSecond - checkReportInterval * 60
    private var checkCount = 0

    companion object {
        @Volatile
        private var sharedClient: BiliClient? = null

        @Synchronized
        private fun getSharedClient(): BiliClient {
            return sharedClient ?: BiliClient().also { sharedClient = it }
        }

        @JvmStatic
        protected val client: BiliClient
            get() = getSharedClient()

        @JvmStatic
        fun closeSharedClient() {
            val toClose = synchronized(this) {
                val current = sharedClient
                sharedClient = null
                current
            }
            runCatching { toClose?.close() }
                .onFailure { logger.warn("关闭 BiliCheckTasker 共享 BiliClient 失败: ${it.message}", it) }
        }
    }

    override fun init() {
        if (lowSpeedEnable) runCatching {
            // 解析低频时段配置：如 "22-8" 表示晚上10点到早上8点
            lowSpeedTimeRange = BiliConfigManager.config.checkConfig.lowSpeedTime
                .split("-").map { it.trim().toInt() }

            // 解析低频间隔范围：如 "60-240"
            lowSpeedIntervalRange = BiliConfigManager.config.checkConfig.lowSpeedRange
                .split("-").map { it.trim().toInt() }

            // 解析正常间隔范围：如 "30-120"
            normalIntervalRange = BiliConfigManager.config.checkConfig.normalRange
                .split("-").map { it.trim().toInt() }

            // 验证配置
            if (lowSpeedTimeRange.size != 2 || lowSpeedIntervalRange.size != 2 || normalIntervalRange.size != 2) {
                throw IllegalArgumentException("配置格式错误")
            }

            // 确保最小间隔至少为30秒
            if (lowSpeedIntervalRange[0] < 30) {
                lowSpeedIntervalRange = listOf(30, lowSpeedIntervalRange[1])
                logger.warn("低频间隔最小值不能小于30秒，已自动调整为30秒")
            }
            if (normalIntervalRange[0] < 30) {
                normalIntervalRange = listOf(30, normalIntervalRange[1])
                logger.warn("正常间隔最小值不能小于30秒，已自动调整为30秒")
            }

            // 检查是否禁用（开始和结束时间相同表示禁用）
            lowSpeedEnable = lowSpeedTimeRange[0] != lowSpeedTimeRange[1]

            if (lowSpeedEnable) {
                logger.info("$taskerName 动态轮询已启用：")
                logger.info("  低频时段：${lowSpeedTimeRange[0]}-${lowSpeedTimeRange[1]} 点，间隔 ${lowSpeedIntervalRange[0]}-${lowSpeedIntervalRange[1]} 秒（随机）")
                logger.info("  正常时段：${if (lowSpeedTimeRange[0] <= lowSpeedTimeRange[1])
                    "${lowSpeedTimeRange[1]}-${lowSpeedTimeRange[0]}"
                    else
                    "${lowSpeedTimeRange[1]}-${lowSpeedTimeRange[0]}"} 点，间隔 ${normalIntervalRange[0]}-${normalIntervalRange[1]} 秒（随机）")
            }
        }.onFailure {
            logger.error("低频检测参数错误: ${it.message}")
            lowSpeedEnable = false
        }
    }

    override fun before() {
        if (checkReportEnable) {
            ++ checkCount
            val now = Instant.now().epochSecond
            if (now - lastCheck >= checkReportInterval * 60){
                logger.debug("$taskerName check running...${checkCount}")
                lastCheck = now
                checkCount = 0
            }
        }
    }

    override fun after() {
        if (lowSpeedEnable) interval = calcTime(intervalTime)
    }

    private fun calcTime(time: Int): Int {
        if (!lowSpeedEnable) return time

        val hour = LocalTime.now().hour
        val isInLowSpeedPeriod = if (lowSpeedTimeRange[0] > lowSpeedTimeRange[1]) {
            // 跨午夜的时段，如 22-8
            hour >= lowSpeedTimeRange[0] || hour < lowSpeedTimeRange[1]
        } else {
            // 不跨午夜的时段，如 0-8
            hour >= lowSpeedTimeRange[0] && hour < lowSpeedTimeRange[1]
        }

        return if (isInLowSpeedPeriod) {
            // 在低频时段，使用随机间隔
            val min = lowSpeedIntervalRange[0]
            val max = lowSpeedIntervalRange[1]
            val randomInterval = (min..max).random()
            logger.debug("$taskerName 当前处于低频时段（${hour}点），下次检查间隔：$randomInterval 秒")
            randomInterval
        } else {
            // 在正常时段，使用正常随机间隔
            val min = normalIntervalRange[0]
            val max = normalIntervalRange[1]
            val randomInterval = (min..max).random()
            logger.debug("$taskerName 当前处于正常时段（${hour}点），下次检查间隔：$randomInterval 秒")
            randomInterval
        }
    }

    /**
     * 手动执行一次检查（用于测试）
     */
    suspend fun executeOnce() {
        logger.info("$taskerName 手动触发检查...")
        main()
    }

}
