package top.bilibili.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import top.bilibili.BiliConfigManager
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformAdapter
import top.bilibili.connector.PlatformAdapterKind
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformType
import top.bilibili.connector.onebot11.generic.GenericOneBot11Adapter
import top.bilibili.connector.onebot11.vendors.napcat.NapCatAdapter
import top.bilibili.connector.qqofficial.QQOfficialAdapter
import top.bilibili.config.ConfigManager
import top.bilibili.core.resource.LambdaResourcePartition
import top.bilibili.core.resource.ResourceStopReport
import top.bilibili.core.resource.ResourceStrictness
import top.bilibili.core.resource.ResourceSupervisor
import top.bilibili.core.resource.ShutdownPhase
import top.bilibili.data.BiliCookie
import top.bilibili.data.BiliMessage
import top.bilibili.data.DynamicDetail
import top.bilibili.data.LiveDetail
import top.bilibili.napcat.NapCatClient
import top.bilibili.service.CacheMaintenanceService
import top.bilibili.service.FirstRunService
import top.bilibili.service.MessageEventDispatchService
import top.bilibili.service.MessageGatewayProvider
import top.bilibili.service.NapCatMessageGateway
import top.bilibili.service.StartupDataInitService
import top.bilibili.service.TaskBootstrapService
import top.bilibili.service.closeServiceClient
import top.bilibili.tasker.BiliCheckTasker
import top.bilibili.tasker.BiliTasker
import top.bilibili.utils.parsePlatformContact
import top.bilibili.utils.ImageCache
import top.bilibili.utils.actionNotify
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.Path

enum class BotLifecycleState {
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
}

object BiliBiliBot : CoroutineScope {
    val logger = LoggerFactory.getLogger(BiliBiliBot::class.java)

    private var job: Job? = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + (job ?: SupervisorJob().also { job = it })

    private val isRunning = AtomicBoolean(false)
    private val lifecycleState = AtomicReference(BotLifecycleState.STOPPED)
    private var startTime: Long = 0L

    val dataFolder = File("data")
    val dataFolderPath: Path = Path("data")

    val tempPath: Path = Path("temp").also { path ->
        if (!path.toFile().exists()) {
            path.toFile().mkdirs()
        }
    }

    var cookie = BiliCookie()

    lateinit var napCat: NapCatClient
        private set

    lateinit var platformAdapter: PlatformAdapter
        private set

    lateinit var config: top.bilibili.config.BotConfig
        private set

    private var eventCollectorJob: Job? = null
    private val resourceSupervisor = ResourceSupervisor()

    fun isNapCatInitialized(): Boolean = ::napCat.isInitialized

    fun isPlatformAdapterInitialized(): Boolean = ::platformAdapter.isInitialized

    fun isConfigInitialized(): Boolean = ::config.isInitialized

    fun isStopping(): Boolean = lifecycleState.get() == BotLifecycleState.STOPPING

    fun requireNapCat(): NapCatClient {
        require(::napCat.isInitialized) {
            "NapCat 客户端尚未初始化，请先完成启动。"
        }
        return napCat
    }

    fun requirePlatformAdapter(): PlatformAdapter {
        require(::platformAdapter.isInitialized) {
            "平台适配器尚未初始化，请先完成启动。"
        }
        return platformAdapter
    }

    fun requireConfig(): top.bilibili.config.BotConfig {
        require(::config.isInitialized) {
            "配置尚未加载。"
        }
        return config
    }

    var uid: Long = 0L
    var tagid: Int = 0
    private var commandLineDebugMode: Boolean? = null

    val dynamicChannel = Channel<DynamicDetail>(20)
    val liveChannel = Channel<LiveDetail>(20)
    val messageChannel = Channel<BiliMessage>(20)
    val liveUsers = mutableMapOf<Long, Long>()

    @Deprecated("使用 useResourceAsStream 或 getResourceBytes 替代", ReplaceWith("useResourceAsStream(path) { it.readBytes() }"))
    fun getResourceAsStream(path: String): InputStream? {
        val resourcePath = if (path.startsWith("/")) path.substring(1) else path
        return this::class.java.classLoader.getResourceAsStream(resourcePath)
    }

    inline fun <T> useResourceAsStream(path: String, block: (InputStream) -> T): T? {
        val resourcePath = if (path.startsWith("/")) path.substring(1) else path
        return this::class.java.classLoader.getResourceAsStream(resourcePath)?.use(block)
    }

    fun getResourceBytes(path: String): ByteArray? {
        val resourcePath = if (path.startsWith("/")) path.substring(1) else path
        return this::class.java.classLoader.getResourceAsStream(resourcePath)?.use {
            it.readBytes()
        }
    }

    fun start(enableDebug: Boolean? = null) {
        if (!isRunning.compareAndSet(false, true)) {
            logger.warn("Bot 已在运行中，忽略重复启动请求")
            return
        }

        lifecycleState.set(BotLifecycleState.STARTING)
        startTime = System.currentTimeMillis()

        if (enableDebug != null) {
            commandLineDebugMode = enableDebug
        }

        try {
            BiliConfigManager.init()
            val debugMode = commandLineDebugMode ?: BiliConfigManager.config.enableConfig.debugMode
            if (debugMode) {
                System.setProperty("APP_LOG_LEVEL", "DEBUG")
                ch.qos.logback.classic.LoggerContext::class.java.cast(
                    org.slf4j.LoggerFactory.getILoggerFactory(),
                ).let { context ->
                    context.getLogger("top.bilibili").level = ch.qos.logback.classic.Level.DEBUG
                    context.getLogger("ROOT").level = ch.qos.logback.classic.Level.DEBUG
                }
                val source = if (commandLineDebugMode == true) "命令行" else "配置文件"
                logger.debug("已从${source}启用调试模式")
            }
        } catch (e: Exception) {
            logger.error("初始化配置失败: ${e.message}", e)
            isRunning.set(false)
            lifecycleState.set(BotLifecycleState.STOPPED)
            return
        }

        logger.info("========================================")
        logger.info("  欢迎使用 BiliBili Dynamic Bot")
        logger.info("========================================")

        try {
            resourceSupervisor.reset()

            logger.info("正在初始化必要目录...")
            listOf("config", "data", "temp", "logs").forEach { dir ->
                File(dir).apply {
                    if (!exists()) {
                        mkdirs()
                        logger.debug("已创建目录: $dir")
                    }
                }
            }

            logger.info("正在加载 Bot 配置...")
            ConfigManager.init()
            config = ConfigManager.botConfig
            top.bilibili.data.BiliImageTheme.reload()
            top.bilibili.data.BiliImageQuality.reload()

            if (BiliConfigManager.config.enableConfig.cacheClearEnable) {
                logger.info("正在清理启动缓存...")
                CacheMaintenanceService.clearAllCache()
            }

            if (!config.validateSelectedPlatform()) {
                logger.error("平台配置无效，请检查 config/bot.yml 中的 {}", config.selectedPlatformType())
                isRunning.set(false)
                lifecycleState.set(BotLifecycleState.STOPPED)
                return
            }

            logger.info("正在初始化平台适配器...")
            // 启动期先按显式适配器选择分发，后续任务再把通用 OneBot11 传输与 NapCat 实现彻底拆开。
            platformAdapter = when (config.selectedAdapterKind()) {
                PlatformAdapterKind.NAPCAT -> {
                    val oneBotConfig = config.selectedOneBot11Config()
                    napCat = NapCatClient(oneBotConfig)
                    NapCatAdapter(napCat)
                }
                PlatformAdapterKind.ONEBOT11 -> {
                    val oneBotConfig = config.selectedOneBot11Config()
                    // 在引入新 vendor 前，通用 OneBot11 先通过显式传输桥接复用现有连接配置。
                    GenericOneBot11Adapter(NapCatAdapter.transport(NapCatClient(oneBotConfig)))
                }
                PlatformAdapterKind.QQ_OFFICIAL -> QQOfficialAdapter(config.platform.qqOfficial)
            }
            MessageGatewayProvider.register(
                NapCatMessageGateway(
                    platformAdapterProvider = { requirePlatformAdapter() },
                    adminContactProvider = { BiliConfigManager.config.normalizedAdminSubject() },
                    logger = logger,
                ),
            )

            eventCollectorJob = launch {
                platformAdapter.eventFlow.collect { event ->
                    try {
                        withTimeout(5_000) {
                            MessageEventDispatchService.handleMessageEvent(event)
                        }
                    } catch (_: TimeoutCancellationException) {
                        logger.warn("消息事件处理超时: ${event.chatType}")
                    } catch (e: Exception) {
                        if (isStopping()) {
                            logger.debug("停机期间忽略事件分发异常: ${e.message}")
                        } else {
                            logger.error("分发消息事件失败", e)
                        }
                    }
                }
            }

            platformAdapter.start()

            registerResourcePartitions()

            launch {
                try {
                    withTimeout(60_000) {
                        delay(3_000)
                        StartupDataInitService.initBiliData()
                    }
                } catch (_: TimeoutCancellationException) {
                    logger.error("启动数据初始化超时")
                    stop("startup-data-timeout")
                }
            }

            launch {
                try {
                    withTimeout(30_000) {
                        delay(5_000)
                        TaskBootstrapService.startTasks()
                        delay(1_000)
                        FirstRunService.checkFirstRun(config)
                    }
                } catch (_: TimeoutCancellationException) {
                    logger.error("任务启动超时")
                }
            }

            lifecycleState.set(BotLifecycleState.RUNNING)
            logger.info("Bot 启动成功")
        } catch (e: Exception) {
            logger.error("Bot 启动失败: ${e.message}", e)
            stop("startup-failure")
        }
    }

    fun stop(reason: String = "shutdown-request") {
        val currentState = lifecycleState.get()
        if (currentState == BotLifecycleState.STOPPING || currentState == BotLifecycleState.STOPPED) {
            logger.info("当前生命周期状态为 $currentState，忽略停止请求")
            return
        }

        if (!isRunning.compareAndSet(true, false) && currentState != BotLifecycleState.STARTING) {
            logger.warn("Bot 未运行，忽略停止请求")
            lifecycleState.compareAndSet(BotLifecycleState.STARTING, BotLifecycleState.STOPPED)
            return
        }

        lifecycleState.set(BotLifecycleState.STOPPING)
        logger.info("正在停止 Bot，原因=$reason")

        var report: ResourceStopReport? = null
        var usedFallback = false

        try {
            report = runCatching {
                runBlocking { resourceSupervisor.stopAll() }
            }.onFailure {
                logger.error("资源总管停止失败: ${it.message}", it)
            }.getOrNull()

            if (report == null || report.totalPartitions == 0 || !report.success) {
                usedFallback = true
                runBlocking { fallbackStopResources() }
            }

            if (::config.isInitialized) {
                runCatching { BiliConfigManager.saveData() }
                    .onFailure { logger.warn("停机时保存运行数据失败: ${it.message}", it) }
            }
        } catch (e: Exception) {
            logger.error("停机过程中发生未预期异常: ${e.message}", e)
        } finally {
            lifecycleState.set(BotLifecycleState.STOPPED)
            logShutdownSummary(reason, report, usedFallback)
            logger.info("Bot 已停止")
        }
    }

    fun isHealthy(): Boolean {
        return lifecycleState.get() == BotLifecycleState.RUNNING &&
            isRunning.get() &&
            job?.isActive == true &&
            ::platformAdapter.isInitialized
    }

    fun getUptimeSeconds(): Long {
        return if (isRunning.get()) {
            (System.currentTimeMillis() - startTime) / 1000
        } else {
            0L
        }
    }

    /**
     * 为仍在迁移中的 OneBot11 调用方保留数字群号发送入口。
     */
    @Deprecated(
        message = "优先使用 sendMessage(contact, message) 统一走 PlatformContact 发送入口",
        replaceWith = ReplaceWith("sendMessage(PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, groupId.toString()), message)"),
    )
    suspend fun sendGroupMessage(groupId: Long, message: List<OutgoingPart>): Boolean {
        return MessageGatewayProvider.require().sendMessage(
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, groupId.toString()),
            message,
        )
    }

    /**
     * 为仍在迁移中的 OneBot11 调用方保留数字私聊发送入口。
     */
    @Deprecated(
        message = "优先使用 sendMessage(contact, message) 统一走 PlatformContact 发送入口",
        replaceWith = ReplaceWith("sendMessage(PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, userId.toString()), message)"),
    )
    suspend fun sendPrivateMessage(userId: Long, message: List<OutgoingPart>): Boolean {
        return MessageGatewayProvider.require().sendMessage(
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, userId.toString()),
            message,
        )
    }

    suspend fun sendAdminMessage(message: String): Boolean {
        return MessageGatewayProvider.require().sendAdminMessage(message)
    }

    suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
        return MessageGatewayProvider.require().sendMessage(contact, message)
    }

    private fun registerResourcePartitions() {
        resourceSupervisor.reset()

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "scope-job",
                owns = listOf("BiliBiliBot.job"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                shutdownPhase = ShutdownPhase.ROOT_SCOPE,
                stopAction = {
                    try {
                        withTimeout(10_000) {
                            job?.cancelAndJoin()
                        }
                    } catch (_: TimeoutCancellationException) {
                        logger.warn("等待根协程作用域停止超时")
                        job?.cancel()
                    } finally {
                        job = null
                    }
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "skia-manager",
                owns = listOf("SkiaManager", "FontManager"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                shutdownPhase = ShutdownPhase.DEPENDENCIES,
                stopAction = {
                    top.bilibili.skia.SkiaManager.shutdown()
                    logger.info("Skia 管理器已停止")
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "bili-client",
                owns = listOf("biliClient"),
                strictness = ResourceStrictness.STRICT,
                shutdownPhase = ShutdownPhase.DEPENDENCIES,
                stopAction = {
                    top.bilibili.utils.biliClient.close()
                    logger.info("共享 biliClient 已停止")
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "service-bili-client",
                owns = listOf("service.client"),
                strictness = ResourceStrictness.STRICT,
                shutdownPhase = ShutdownPhase.DEPENDENCIES,
                stopAction = {
                    closeServiceClient()
                    logger.info("服务共享客户端已停止")
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "check-tasker-bili-client",
                owns = listOf("BiliCheckTasker.client"),
                strictness = ResourceStrictness.STRICT,
                shutdownPhase = ShutdownPhase.DEPENDENCIES,
                stopAction = {
                    BiliCheckTasker.closeSharedClient()
                    logger.info("BiliCheckTasker 共享客户端已停止")
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "image-cache",
                owns = listOf("ImageCache"),
                strictness = ResourceStrictness.STRICT,
                shutdownPhase = ShutdownPhase.DEPENDENCIES,
                stopAction = {
                    ImageCache.close()
                    logger.info("图片缓存已停止")
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "taskers",
                owns = listOf("BiliTasker.*"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                shutdownPhase = ShutdownPhase.WORKERS,
                stopAction = {
                    val report = BiliTasker.cancelAll(timeoutMs = 10_000)
                    if (!report.success) {
                        error("任务器停止失败: ${report.failures}")
                    }
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "gateway-platform",
                owns = listOf("PlatformAdapter", "MessageGatewayProvider"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                shutdownPhase = ShutdownPhase.INGRESS,
                stopAction = {
                    if (::platformAdapter.isInitialized) {
                        platformAdapter.stop()
                    }
                    MessageGatewayProvider.unregister()
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "event-collector",
                owns = listOf("eventCollectorJob"),
                strictness = ResourceStrictness.STRICT,
                shutdownPhase = ShutdownPhase.INGRESS,
                stopAction = {
                    eventCollectorJob?.cancelAndJoin()
                    eventCollectorJob = null
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "channels",
                owns = listOf("dynamicChannel", "liveChannel", "messageChannel"),
                strictness = ResourceStrictness.STRICT,
                shutdownPhase = ShutdownPhase.CHANNELS,
                stopAction = {
                    dynamicChannel.close()
                    liveChannel.close()
                    messageChannel.close()
                    logger.debug("所有消息通道已关闭")
                },
            ),
        )
    }

    private suspend fun fallbackStopResources() {
        runCatching {
            if (::platformAdapter.isInitialized) {
                platformAdapter.stop()
            }
            MessageGatewayProvider.unregister()
        }.onFailure {
            logger.warn("兜底停止入口资源失败: ${it.message}", it)
        }

        runCatching {
            eventCollectorJob?.cancelAndJoin()
            eventCollectorJob = null
        }.onFailure {
            logger.warn("兜底停止事件收集器失败: ${it.message}", it)
        }

        runCatching {
            val report = BiliTasker.cancelAll(timeoutMs = 10_000)
            if (!report.success) {
                logger.warn("兜底停止任务器存在失败项: ${report.failures}")
            }
        }.onFailure {
            logger.warn("兜底停止任务器失败: ${it.message}", it)
        }

        runCatching {
            dynamicChannel.close()
            liveChannel.close()
            messageChannel.close()
        }.onFailure {
            logger.warn("兜底关闭通道失败: ${it.message}", it)
        }

        runCatching { ImageCache.close() }
            .onFailure { logger.warn("兜底关闭图片缓存失败: ${it.message}", it) }

        runCatching { top.bilibili.utils.biliClient.close() }
            .onFailure { logger.warn("兜底关闭 biliClient 失败: ${it.message}", it) }

        runCatching { closeServiceClient() }
            .onFailure { logger.warn("兜底关闭服务共享客户端失败: ${it.message}", it) }

        runCatching { BiliCheckTasker.closeSharedClient() }
            .onFailure { logger.warn("兜底关闭 BiliCheckTasker 客户端失败: ${it.message}", it) }

        runCatching { top.bilibili.skia.SkiaManager.shutdown() }
            .onFailure { logger.warn("兜底关闭 Skia 失败: ${it.message}", it) }

        runCatching {
            withTimeout(10_000) {
                job?.cancelAndJoin()
            }
        }.onFailure {
            logger.warn("兜底停止根协程作用域失败: ${it.message}", it)
            job?.cancel()
        }
        job = null
    }

    private fun logShutdownSummary(
        reason: String,
        report: ResourceStopReport?,
        usedFallback: Boolean,
    ) {
        if (report == null) {
            logger.warn("停机摘要: 原因=$reason，资源总管报告缺失，是否使用兜底=$usedFallback")
            return
        }

        val phaseSummary = report.phaseReports.joinToString(separator = "; ") { phase ->
            "${phase.phase}:${phase.stoppedPartitions}/${phase.totalPartitions} in ${phase.durationMs}ms"
        }
        val message =
            "停机摘要: 原因=$reason，成功=${report.success}，总分区=${report.totalPartitions}，失败分区=${report.failedPartitions}，是否使用兜底=$usedFallback，阶段=[$phaseSummary]，失败详情=${report.failures}"
        if (report.success && !usedFallback) {
            logger.info(message)
        } else {
            logger.warn(message)
        }
    }
}
