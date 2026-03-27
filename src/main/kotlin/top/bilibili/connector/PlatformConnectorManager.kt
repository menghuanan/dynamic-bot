package top.bilibili.connector

import kotlinx.coroutines.flow.Flow
import top.bilibili.config.BotConfig
import top.bilibili.connector.onebot11.generic.GenericOneBot11Adapter
import top.bilibili.connector.onebot11.vendors.napcat.NapCatAdapter
import top.bilibili.connector.onebot11.vendors.napcat.NapCatClient
import top.bilibili.connector.qqofficial.QQOfficialAdapter

class PlatformConnectorManager(
    private val config: BotConfig,
) {
    private var platformAdapter: PlatformAdapter? = null

    /**
     * 启动层统一通过 manager 获取事件流，避免再感知具体 vendor 适配器的创建细节。
     */
    val eventFlow: Flow<PlatformInboundMessage>
        get() = initialize().eventFlow

    fun isInitialized(): Boolean = platformAdapter != null

    fun requirePlatformAdapter(): PlatformAdapter {
        return platformAdapter ?: error("平台适配器尚未初始化，请先完成启动。")
    }

    /**
     * 先创建并缓存运行期适配器，便于调用方在 start 前完成事件订阅和网关注册。
     */
    fun initialize(): PlatformAdapter {
        return platformAdapter ?: createPlatformAdapter().also { adapter ->
            platformAdapter = adapter
        }
    }

    fun start() {
        initialize().start()
    }

    fun stop() {
        platformAdapter?.stop()
    }

    fun runtimeStatus(): PlatformRuntimeStatus {
        return requirePlatformAdapter().runtimeStatus()
    }

    /**
     * 将平台选择与 vendor 组装收口到 connector 层，避免业务启动入口继续直接 new adapter。
     */
    private fun createPlatformAdapter(): PlatformAdapter {
        return when (config.selectedAdapterKind()) {
            PlatformAdapterKind.NAPCAT -> {
                val oneBotConfig = config.selectedOneBot11Config()
                NapCatAdapter(NapCatClient(oneBotConfig))
            }
            PlatformAdapterKind.ONEBOT11 -> {
                val oneBotConfig = config.selectedOneBot11Config()
                GenericOneBot11Adapter(NapCatAdapter.transport(NapCatClient(oneBotConfig)))
            }
            PlatformAdapterKind.QQ_OFFICIAL -> QQOfficialAdapter(config.platform.qqOfficial)
        }
    }
}
