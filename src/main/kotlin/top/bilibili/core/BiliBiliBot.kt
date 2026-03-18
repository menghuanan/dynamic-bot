package top.bilibili.core

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import top.bilibili.BiliConfigManager
import top.bilibili.config.ConfigManager
import top.bilibili.napcat.MessageSegment
import top.bilibili.napcat.NapCatClient
import top.bilibili.data.BiliCookie
import top.bilibili.data.DynamicDetail
import top.bilibili.data.LiveDetail
import top.bilibili.data.BiliMessage
import top.bilibili.service.CacheMaintenanceService
import top.bilibili.service.FirstRunService
import top.bilibili.service.MessageEventDispatchService
import top.bilibili.service.MessageGatewayProvider
import top.bilibili.service.NapCatMessageGateway
import top.bilibili.service.StartupDataInitService
import top.bilibili.service.TaskBootstrapService
import top.bilibili.service.closeServiceClient
import top.bilibili.utils.ImageCache
import top.bilibili.utils.actionNotify
import top.bilibili.tasker.BiliCheckTasker
import top.bilibili.tasker.BiliTasker
import top.bilibili.core.resource.LambdaResourcePartition
import top.bilibili.core.resource.ResourceSupervisor
import top.bilibili.core.resource.ResourceStrictness
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.Path

/**
 * BiliBili 动态推送 Bot 核心类
 * 替代原来的 BiliBiliBot 插件对象
 */
object BiliBiliBot : CoroutineScope {
    val logger = LoggerFactory.getLogger(BiliBiliBot::class.java)
    private var job: Job? = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + (job ?: SupervisorJob().also { job = it })

    // ✅ 运行状态标志
    private val isRunning = AtomicBoolean(false)
    private var startTime: Long = 0L

    /** 数据文件夹 */
    val dataFolder = File("data")
    val dataFolderPath: Path = Path("data")

    /** 临时文件目录 */
    val tempPath: Path = Path("temp").also { path ->
        if (!path.toFile().exists()) {
            path.toFile().mkdirs()
        }
    }

    /** B站 Cookie */
    var cookie = BiliCookie()

    /** NapCat WebSocket 客户端 */
    lateinit var napCat: NapCatClient
        private set

    /** Bot 配置 */
    lateinit var config: top.bilibili.config.BotConfig
        private set

    /** ✅ 事件收集协程的显式引用 */
    private var eventCollectorJob: Job? = null
    private val resourceSupervisor = ResourceSupervisor()

    /**
     * 检查 NapCat 客户端是否已初始化
     */
    fun isNapCatInitialized(): Boolean = ::napCat.isInitialized

    /**
     * 检查配置是否已加载
     */
    fun isConfigInitialized(): Boolean = ::config.isInitialized

    /**
     * 安全获取 NapCat 客户端，未初始化时抛出有意义的异常
     */
    fun requireNapCat(): NapCatClient {
        require(::napCat.isInitialized) {
            "NapCat 客户端尚未初始化，请先完成启动。"
        }
        return napCat
    }

    /**
     * 安全获取配置，未初始化时抛出有意义的异常
     */
    fun requireConfig(): top.bilibili.config.BotConfig {
        require(::config.isInitialized) {
            "配置尚未加载。"
        }
        return config
    }

    /** B站用户 UID */
    var uid: Long = 0L

    /** B站关注分组 ID */
    var tagid: Int = 0

    /** 命令行参数：Debug模式 */
    private var commandLineDebugMode: Boolean? = null

    /** 数据 Channel */
    val dynamicChannel = Channel<DynamicDetail>(20)
    val liveChannel = Channel<LiveDetail>(20)
    val messageChannel = Channel<BiliMessage>(20)
    // ✅ P2修复: 删除未使用的 missChannel
    val liveUsers = mutableMapOf<Long, Long>()

    /**
     * 获取资源文件流
     * @deprecated 使用 useResourceAsStream 或 getResourceBytes 替代，以确保资源正确关闭
     */
    @Deprecated("使用 useResourceAsStream 或 getResourceBytes 替代", ReplaceWith("useResourceAsStream(path) { it.readBytes() }"))
    fun getResourceAsStream(path: String): InputStream? {
        // 使用 classLoader 加载资源，确保从 classpath 根目录开始查找
        val resourcePath = if (path.startsWith("/")) path.substring(1) else path
        return this::class.java.classLoader.getResourceAsStream(resourcePath)
    }

    /**
     * ✅ 安全地使用资源文件流
     * @param path 资源路径
     * @param block 使用流的操作
     * @return 操作结果，如果资源不存在则返回 null
     */
    inline fun <T> useResourceAsStream(path: String, block: (InputStream) -> T): T? {
        val resourcePath = if (path.startsWith("/")) path.substring(1) else path
        return this::class.java.classLoader.getResourceAsStream(resourcePath)?.use(block)
    }

    /**
     * ✅ 读取资源文件为字节数组
     */
    fun getResourceBytes(path: String): ByteArray? {
        val resourcePath = if (path.startsWith("/")) path.substring(1) else path
        return this::class.java.classLoader.getResourceAsStream(resourcePath)?.use {
            it.readBytes()
        }
    }

    /** 启动 Bot */
    fun start(enableDebug: Boolean? = null) {
        // ✅ 重复启动保护
        if (!isRunning.compareAndSet(false, true)) {
            logger.warn("Bot 已在运行中，忽略重复启动请求")
            return
        }

        startTime = System.currentTimeMillis()

        // 保存命令行参数
        if (enableDebug != null) {
            commandLineDebugMode = enableDebug
        }

        // 先加载配置以确定日志级别
        try {
            BiliConfigManager.init()
            // 根据配置或命令行参数设置日志级别（命令行参数优先）
            val debugMode = commandLineDebugMode ?: BiliConfigManager.config.enableConfig.debugMode
            if (debugMode) {
                System.setProperty("APP_LOG_LEVEL", "DEBUG")
                ch.qos.logback.classic.LoggerContext::class.java.cast(
                    org.slf4j.LoggerFactory.getILoggerFactory()
                ).let { context ->
                    context.getLogger("top.bilibili").level = ch.qos.logback.classic.Level.DEBUG
                    context.getLogger("ROOT").level = ch.qos.logback.classic.Level.DEBUG
                }
                val source = if (commandLineDebugMode == true) "命令行参数" else "配置文件"
                logger.debug("调试模式已启用（来源：$source）")
            }
        } catch (e: Exception) {
            logger.error("加载配置失败: ${e.message}")
            isRunning.set(false)
            return
        }

        logger.info("========================================")
        logger.info("  欢迎使用 BiliBili 动态推送 Bot")
        logger.info("========================================")

        try {
            resourceSupervisor.reset()

            // 0. 创建必要的目录
            logger.info("正在初始化目录结构...")
            listOf("config", "data", "temp", "logs").forEach { dir ->
                File(dir).apply {
                    if (!exists()) {
                        mkdirs()
                        logger.debug("创建目录: $dir")
                    }
                }
            }

            // 1. 加载配置（已在前面加载）
            logger.info("正在加载配置...")
            ConfigManager.init()
            config = ConfigManager.botConfig
            top.bilibili.data.BiliImageTheme.reload()
            top.bilibili.data.BiliImageQuality.reload()

            // 清理所有缓存（启动时）
            if (BiliConfigManager.config.enableConfig.cacheClearEnable) {
                logger.info("正在清理所有缓存...")
                CacheMaintenanceService.clearAllCache()
            }

            if (!config.napcat.validate()) {
                logger.error("NapCat 配置无效，请检查 config/bot.yml")
                isRunning.set(false)
                return
            }

            // 2. 初始化 NapCat 客户端
            logger.info("正在初始化 NapCat 客户端...")
            napCat = NapCatClient(config.napcat)
            MessageGatewayProvider.register(
                NapCatMessageGateway(
                    napCatProvider = { requireNapCat() },
                    adminIdProvider = { BiliConfigManager.config.admin },
                    logger = logger
                )
            )

            // 3. 订阅消息事件（✅ 显式管理协程）
            eventCollectorJob = launch {
                napCat.eventFlow.collect { event ->
                    try {
                        // ✅ 单个事件处理最多 5 秒
                        withTimeout(5000) {
                            MessageEventDispatchService.handleMessageEvent(event)
                        }
                    } catch (e: TimeoutCancellationException) {
                        logger.warn("处理事件超时: ${event.messageType}")
                    } catch (e: Exception) {
                        logger.error("处理事件失败", e)
                    }
                }
            }

            // 4. 启动 NapCat 客户端
            napCat.start()
            launch {
                delay(1000)
                BiliConfigManager.consumePendingSubjectColorMigrationNotice()?.let { notice ->
                    runCatching { actionNotify(notice) }
                        .onFailure { logger.warn("Failed to send subject color migration summary", it) }
                }
            }
            registerResourcePartitions()

            // 5. 初始化 B站数据（✅ 添加超时保护）
            launch {
                try {
                    withTimeout(60000) {  // 60 秒超时
                        delay(3000) // 等待 WebSocket 连接
                        StartupDataInitService.initBiliData()
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.error("初始化数据超时")
                    stop()  // 超时则停止 Bot
                }
            }

            // 6. 启动任务（✅ 添加超时保护）
            launch {
                try {
                    withTimeout(30000) {  // 30 秒超时
                        delay(5000) // 等待初始化完成
                        TaskBootstrapService.startTasks()

                        // 首次运行检查
                        delay(1000)
                        FirstRunService.checkFirstRun(config)
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.error("启动任务超时")
                }
            }

            logger.info("Bot 启动成功")

        } catch (e: Exception) {
            logger.error("Bot 启动失败: ${e.message}", e)
            isRunning.set(false)
            stop()
        }
    }

    /** 停止 Bot */
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) {
            logger.warn("Bot 未在运行，忽略停止请求")
            return
        }

        logger.info("正在停止 Bot...")

        try {
            val report = runCatching {
                runBlocking { resourceSupervisor.stopAll() }
            }.onFailure {
                logger.error("统一资源总管停止失败，进入分区兜底释放: ${it.message}", it)
            }.getOrNull()

            if (report != null) {
                if (report.totalPartitions == 0) {
                    logger.warn("统一资源总管未注册分区，进入兜底释放")
                    runBlocking { fallbackStopResources() }
                } else if (!report.success) {
                    logger.warn(
                        "统一资源总管停止存在失败: total=${report.totalPartitions}, failed=${report.failedPartitions}, failures=${report.failures}"
                    )
                    runBlocking { fallbackStopResources() }
                } else {
                    logger.info("统一资源总管已完成全部分区释放")
                }
            } else {
                runBlocking { fallbackStopResources() }
            }

            // 8. 保存配置和数据
            if (::config.isInitialized) {
                BiliConfigManager.saveAll()
            }

            // 9. 等待协程完成（带超时）
            runBlocking {
                try {
                    withTimeout(10000) { // 10 秒超时
                        job?.cancelAndJoin()
                        logger.info("所有协程已正常结束")
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.warn("等待协程结束超时，强制取消")
                    job?.cancel()
                }
            }

            job = null

            logger.info("Bot 已停止")
        } catch (e: Exception) {
            logger.error("停止 Bot 时发生错误: ${e.message}", e)
        }
    }

    /**
     * 检查 Bot 是否正常运行
     */
    fun isHealthy(): Boolean {
        return isRunning.get() &&
               job?.isActive == true &&
               ::napCat.isInitialized
    }

    /**
     * 获取运行时长（秒）
     */
    fun getUptimeSeconds(): Long {
        return if (isRunning.get()) {
            (System.currentTimeMillis() - startTime) / 1000
        } else {
            0L
        }
    }

    /** 发送群消息 */
    suspend fun sendGroupMessage(groupId: Long, message: List<MessageSegment>): Boolean {
        return MessageGatewayProvider.require().sendGroupMessage(groupId, message)
    }

    /** 发送私聊消息 */
    suspend fun sendPrivateMessage(userId: Long, message: List<MessageSegment>): Boolean {
        return MessageGatewayProvider.require().sendPrivateMessage(userId, message)
    }

    suspend fun sendAdminMessage(message: String): Boolean {
        return MessageGatewayProvider.require().sendAdminMessage(message)
    }

    /** 发送消息到指定联系人 */
    suspend fun sendMessage(contact: ContactId, message: List<MessageSegment>): Boolean {
        return MessageGatewayProvider.require().sendMessage(contact, message)
    }

    private fun registerResourcePartitions() {
        resourceSupervisor.reset()

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "scope-job",
                owns = listOf("BiliBiliBot.job"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                stopAction = {
                    try {
                        withTimeout(10_000) {
                            job?.cancelAndJoin()
                            logger.info("所有协程已正常结束")
                        }
                    } catch (_: TimeoutCancellationException) {
                        logger.warn("等待协程结束超时，强制取消")
                        job?.cancel()
                    } finally {
                        job = null
                    }
                },
            )
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "skia-manager",
                owns = listOf("SkiaManager", "FontManager"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                stopAction = {
                    top.bilibili.skia.SkiaManager.shutdown()
                    logger.info("Skia 管理器已关闭")
                },
            )
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "bili-client",
                owns = listOf("biliClient"),
                strictness = ResourceStrictness.STRICT,
                stopAction = {
                    top.bilibili.utils.biliClient.close()
                    logger.info("B站客户端已关闭")
                },
            )
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "service-bili-client",
                owns = listOf("service.client"),
                strictness = ResourceStrictness.STRICT,
                stopAction = {
                    closeServiceClient()
                    logger.info("Service 共享 B站客户端已关闭")
                },
            )
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "check-tasker-bili-client",
                owns = listOf("BiliCheckTasker.client"),
                strictness = ResourceStrictness.STRICT,
                stopAction = {
                    BiliCheckTasker.closeSharedClient()
                    logger.info("检查任务共享 B站客户端已关闭")
                },
            )
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "image-cache",
                owns = listOf("ImageCache"),
                strictness = ResourceStrictness.STRICT,
                stopAction = {
                    ImageCache.close()
                },
            )
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "taskers",
                owns = listOf("BiliTasker.*"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                stopAction = {
                    BiliTasker.cancelAll()
                },
            )
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "gateway-napcat",
                owns = listOf("NapCatClient", "MessageGatewayProvider"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                stopAction = {
                    if (::napCat.isInitialized) {
                        napCat.stop()
                    }
                    MessageGatewayProvider.unregister()
                },
            )
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "event-collector",
                owns = listOf("eventCollectorJob"),
                strictness = ResourceStrictness.STRICT,
                stopAction = {
                    eventCollectorJob?.cancelAndJoin()
                    eventCollectorJob = null
                },
            )
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "channels",
                owns = listOf("dynamicChannel", "liveChannel", "messageChannel"),
                strictness = ResourceStrictness.STRICT,
                stopAction = {
                    dynamicChannel.close()
                    liveChannel.close()
                    messageChannel.close()
                    logger.debug("所有 Channel 已关闭")
                },
            )
        )
    }

    private suspend fun fallbackStopResources() {
        runCatching {
            dynamicChannel.close()
            liveChannel.close()
            messageChannel.close()
            logger.debug("兜底释放: 所有 Channel 已关闭")
        }.onFailure {
            logger.warn("兜底释放: 关闭 Channel 失败: ${it.message}", it)
        }

        runCatching {
            eventCollectorJob?.cancelAndJoin()
            eventCollectorJob = null
        }.onFailure {
            logger.warn("兜底释放: 停止事件收集器失败: ${it.message}", it)
        }

        runCatching {
            if (::napCat.isInitialized) {
                napCat.stop()
            }
            MessageGatewayProvider.unregister()
        }.onFailure {
            logger.warn("兜底释放: 停止网关/NapCat失败: ${it.message}", it)
        }

        runCatching { BiliTasker.cancelAll() }
            .onFailure { logger.warn("兜底释放: 停止任务失败: ${it.message}", it) }

        runCatching { ImageCache.close() }
            .onFailure { logger.warn("兜底释放: 关闭 ImageCache 失败: ${it.message}", it) }

        runCatching {
            top.bilibili.utils.biliClient.close()
            logger.info("兜底释放: B站客户端已关闭")
        }.onFailure {
            logger.warn("兜底释放: 关闭 BiliClient 失败: ${it.message}", it)
        }

        runCatching {
            closeServiceClient()
            logger.info("兜底释放: Service 共享 B站客户端已关闭")
        }.onFailure {
            logger.warn("兜底释放: 关闭 Service 共享 BiliClient 失败: ${it.message}", it)
        }

        runCatching {
            BiliCheckTasker.closeSharedClient()
            logger.info("兜底释放: 检查任务共享 B站客户端已关闭")
        }.onFailure {
            logger.warn("兜底释放: 关闭检查任务共享 BiliClient 失败: ${it.message}", it)
        }

        runCatching {
            top.bilibili.skia.SkiaManager.shutdown()
            logger.info("兜底释放: Skia 管理器已关闭")
        }.onFailure {
            logger.warn("兜底释放: 关闭 SkiaManager 失败: ${it.message}", it)
        }

        runCatching {
            withTimeout(10_000) {
                job?.cancelAndJoin()
                logger.info("兜底释放: 所有协程已正常结束")
            }
        }.onFailure {
            logger.warn("兜底释放: 等待协程结束失败，强制取消: ${it.message}", it)
            job?.cancel()
        }
        job = null
    }
}


