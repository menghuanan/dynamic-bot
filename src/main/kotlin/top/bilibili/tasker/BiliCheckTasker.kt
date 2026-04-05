package top.bilibili.tasker

import top.bilibili.BiliConfigManager
import top.bilibili.client.BiliClient
import top.bilibili.core.BiliBiliBot
import top.bilibili.utils.logger
import java.time.Instant
import java.time.LocalTime

/**
 * 轮询检查类任务的基础抽象，统一处理低频时段与检查心跳上报。
 *
 * @param taskerName 任务名称
 */
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

        /**
         * 获取共享的 BiliClient 实例。
         */
        @Synchronized
        private fun getSharedClient(): BiliClient {
            // 停机阶段禁止再创建轮询共享客户端，避免资源分区进入回收后被新请求重新拉起。
            if (BiliBiliBot.isStopping()) {
                throw IllegalStateException("BiliCheckTasker shared BiliClient is unavailable while bot is stopping")
            }
            // 共享轮询客户端使用固定 owner 标签，便于 ProcessGuardian 在日志中识别轮询链路资源。
            return sharedClient ?: BiliClient("BiliCheckTasker.shared").also { sharedClient = it }
        }

        @JvmStatic
        protected val client: BiliClient
            get() = getSharedClient()

        /**
         * 主动关闭共享的 BiliClient 实例。
         */
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
        runCatching {
            // 解析正常间隔范围：如 "30-120"
            normalIntervalRange = BiliConfigManager.config.checkConfig.normalRange
                .split("-").map { it.trim().toInt() }
            // 验证配置
            if (normalIntervalRange.size != 2) {
                throw IllegalArgumentException("normalRange 配置格式错误")
            }
            // 确保最小间隔至少为30秒
            if (normalIntervalRange[0] < 30) {
                normalIntervalRange = listOf(30, normalIntervalRange[1])
                logger.warn("正常间隔最小值不能小于30秒，已自动调整为30秒")
            }
        }.onFailure {
            logger.error("正常检测参数错误: ${it.message}")
            // normalRange 解析失败时回退为当前默认间隔，避免轮询任务因配置错误长时间停滞。
            val fallback = intervalTime.coerceAtLeast(30)
            normalIntervalRange = listOf(fallback, fallback)
        }

        if (lowSpeedEnable) {
            runCatching {
                // 解析低频时段配置：如 "22-8" 表示晚上10点到早上8点
                lowSpeedTimeRange = BiliConfigManager.config.checkConfig.lowSpeedTime
                    .split("-").map { it.trim().toInt() }

                // 解析低频间隔范围：如 "60-240"
                lowSpeedIntervalRange = BiliConfigManager.config.checkConfig.lowSpeedRange
                    .split("-").map { it.trim().toInt() }

                // 验证配置
                if (lowSpeedTimeRange.size != 2 || lowSpeedIntervalRange.size != 2) {
                    throw IllegalArgumentException("lowSpeed 配置格式错误")
                }

                // 确保最小间隔至少为30秒
                if (lowSpeedIntervalRange[0] < 30) {
                    lowSpeedIntervalRange = listOf(30, lowSpeedIntervalRange[1])
                    logger.warn("低频间隔最小值不能小于30秒，已自动调整为30秒")
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

        // 初始化阶段先计算一次间隔，确保首轮执行后的休眠时间也遵循区间配置。
        interval = calcTime(intervalTime)
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
        interval = calcTime(intervalTime)
    }

    /**
     * 根据当前时间段计算下一次轮询间隔。
     *
     * @param time 默认轮询间隔
     */
    private fun calcTime(time: Int): Int {
        // range 配置异常时回退到默认间隔，避免随机区间非法导致任务中断。
        val (normalMin, normalMax) = normalizeRange(normalIntervalRange, time)

        val hour = LocalTime.now().hour
        val isInLowSpeedPeriod = if (lowSpeedTimeRange[0] > lowSpeedTimeRange[1]) {
            // 跨午夜的时段，如 22-8
            hour >= lowSpeedTimeRange[0] || hour < lowSpeedTimeRange[1]
        } else {
            // 不跨午夜的时段，如 0-8
            hour >= lowSpeedTimeRange[0] && hour < lowSpeedTimeRange[1]
        }

        if (!lowSpeedEnable) {
            // 在未启用低频时段时，统一使用正常时段区间。
            val randomInterval = (normalMin..normalMax).random()
            logger.debug("$taskerName 当前低频未启用，下次检查间隔：$randomInterval 秒")
            return randomInterval
        }

        return if (isInLowSpeedPeriod) {
            // 在低频时段，使用随机间隔
            val (min, max) = normalizeRange(lowSpeedIntervalRange, normalMin)
            val randomInterval = (min..max).random()
            logger.debug("$taskerName 当前处于低频时段（${hour}点），下次检查间隔：$randomInterval 秒")
            randomInterval
        } else {
            // 在正常时段，使用正常随机间隔
            val randomInterval = (normalMin..normalMax).random()
            logger.debug("$taskerName 当前处于正常时段（${hour}点），下次检查间隔：$randomInterval 秒")
            randomInterval
        }
    }

    /**
     * 归一化区间上下界，自动处理配置缺项、最小值不足和反向区间。
     */
    private fun normalizeRange(range: List<Int>, fallback: Int): Pair<Int, Int> {
        val fallbackValue = fallback.coerceAtLeast(30)
        val rawMin = range.getOrElse(0) { fallbackValue }
        val rawMax = range.getOrElse(1) { rawMin }
        val min = rawMin.coerceAtLeast(30)
        val max = rawMax.coerceAtLeast(min)
        return min to max
    }

    /**
     * 手动执行一次检查（用于测试）
     */
    suspend fun executeOnce() {
        // 手动触发需要复用与定时任务完全一致的执行路径，避免测试行为与线上轮询脱节。
        logger.info("$taskerName 手动触发检查...")
        main()
    }

}
