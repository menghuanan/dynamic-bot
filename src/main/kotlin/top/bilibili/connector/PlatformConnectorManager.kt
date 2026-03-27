package top.bilibili.connector

import kotlinx.coroutines.flow.Flow
import top.bilibili.config.BotConfig
import top.bilibili.connector.onebot11.core.KtorOneBot11Transport
import top.bilibili.connector.onebot11.generic.GenericOneBot11Adapter
import top.bilibili.connector.onebot11.vendors.llbot.LlBotAdapter
import top.bilibili.connector.onebot11.vendors.llbot.LlBotClient
import top.bilibili.connector.onebot11.vendors.napcat.NapCatAdapter
import top.bilibili.connector.onebot11.vendors.napcat.NapCatClient
import top.bilibili.connector.qqofficial.QQOfficialAdapter

class PlatformConnectorManager(
    private val config: BotConfig,
    private val adapterFactory: (() -> PlatformAdapter)? = null,
) {
    private val lifecycleLock = Any()
    private var platformAdapter: PlatformAdapter? = null
    private var lifecycleState: ConnectorLifecycleState = ConnectorLifecycleState.IDLE

    /**
     * 启动层统一通过 manager 获取事件流，避免再感知具体 vendor 适配器的创建细节。
     */
    val eventFlow: Flow<PlatformInboundMessage>
        get() = adapter().eventFlow

    fun isInitialized(): Boolean = synchronized(lifecycleLock) { platformAdapter != null }

    /**
     * 先创建并缓存运行期适配器，便于调用方在 start 前完成事件订阅和网关注册。
     */
    fun initialize() {
        adapter()
    }

    fun start() {
        val adapterToStart = synchronized(lifecycleLock) {
            if (
                lifecycleState == ConnectorLifecycleState.STARTING ||
                lifecycleState == ConnectorLifecycleState.STARTED
            ) {
                return
            }
            adapter().also {
                lifecycleState = ConnectorLifecycleState.STARTING
            }
        }

        try {
            adapterToStart.start()
            synchronized(lifecycleLock) {
                if (platformAdapter === adapterToStart) {
                    lifecycleState = ConnectorLifecycleState.STARTED
                }
            }
        } catch (throwable: Throwable) {
            synchronized(lifecycleLock) {
                if (platformAdapter === adapterToStart) {
                    platformAdapter = null
                }
                lifecycleState = ConnectorLifecycleState.IDLE
            }
            throw throwable
        }
    }

    /**
     * connector manager 统一暴露 suspend 停机入口，确保底层 transport 可沿生命周期安全关闭。
     */
    suspend fun stop() {
        val adapterToStop = synchronized(lifecycleLock) {
            val currentAdapter = platformAdapter ?: return
            if (lifecycleState == ConnectorLifecycleState.STOPPING) {
                return
            }
            lifecycleState = ConnectorLifecycleState.STOPPING
            currentAdapter
        }

        try {
            adapterToStop.stop()
        } finally {
            synchronized(lifecycleLock) {
                if (platformAdapter === adapterToStop) {
                    platformAdapter = null
                }
                lifecycleState = ConnectorLifecycleState.IDLE
            }
        }
    }

    /**
     * 统一暴露平台发送入口，避免业务层继续拿 raw adapter 发送消息。
     */
    suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
        return currentAdapter()?.sendMessage(contact, message) ?: false
    }

    /**
     * 统一暴露 capability guard，避免业务层继续直接依赖 adapter 实现。
     */
    suspend fun guardCapability(request: CapabilityRequest): CapabilityGuardResult {
        return currentAdapter()?.guardCapability(request)
            ?: CapabilityGuard.unsupported("platform adapter is not initialized")
    }

    fun runtimeStatus(): PlatformRuntimeStatus {
        return currentAdapter()?.runtimeStatus()
            ?: PlatformRuntimeStatus(
                connected = false,
                reconnectAttempts = 0,
            )
    }

    /**
     * 兼容仍在迁移中的群号能力判断入口，但实现依然收口在 connector manager。
     */
    suspend fun isGroupReachable(groupId: Long): Boolean {
        return currentAdapter()?.isGroupReachable(groupId) ?: false
    }

    /**
     * 兼容仍在迁移中的群 @全体 能力判断入口，但实现依然收口在 connector manager。
     */
    suspend fun canAtAll(groupId: Long): Boolean {
        return currentAdapter()?.canAtAll(groupId) ?: false
    }

    /**
     * 将平台选择与 vendor 组装收口到 connector 层，避免业务启动入口继续直接 new adapter。
     */
    private fun createPlatformAdapter(): PlatformAdapter {
        adapterFactory?.let { factory ->
            return factory.invoke()
        }
        return when (config.selectedAdapterKind()) {
            PlatformAdapterKind.NAPCAT -> {
                val oneBotConfig = config.selectedOneBot11Config()
                NapCatAdapter(NapCatClient(oneBotConfig))
            }
            PlatformAdapterKind.LLBOT -> {
                val oneBotConfig = config.selectedOneBot11Config()
                LlBotAdapter(LlBotClient(oneBotConfig))
            }
            PlatformAdapterKind.ONEBOT11 -> {
                val oneBotConfig = config.selectedOneBot11Config()
                GenericOneBot11Adapter(KtorOneBot11Transport(oneBotConfig))
            }
            PlatformAdapterKind.QQ_OFFICIAL -> QQOfficialAdapter(config.platform.qqOfficial)
        }
    }

    /**
     * manager 内部统一取出运行期适配器，禁止把 raw adapter 暴露给外层业务代码。
     */
    private fun adapter(): PlatformAdapter {
        return synchronized(lifecycleLock) {
            platformAdapter ?: createPlatformAdapter().also { adapter ->
                platformAdapter = adapter
            }
        }
    }

    /**
     * 仅在 manager 当前仍持有活动代际时返回 adapter，避免停机后被发送链路隐式拉起新实例。
     */
    private fun currentAdapter(): PlatformAdapter? {
        return synchronized(lifecycleLock) {
            platformAdapter
        }
    }

    /**
     * 显式区分 connector manager 当前所处的代际状态，避免 stop 后误复用已关闭实例。
     */
    private enum class ConnectorLifecycleState {
        IDLE,
        STARTING,
        STARTED,
        STOPPING,
    }
}
