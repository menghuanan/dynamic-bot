package top.bilibili.connector.onebot11.vendors.llbot

import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.onebot11.OneBot11Adapter

class LlBotAdapter(
    private val llBotClient: LlBotClient,
) : OneBot11Adapter(LlBotAdapterTransport(llBotClient)) {
    /**
     * llbot 首批能力面与当前 NapCat 对业务暴露的子集对齐，显式声明 @全体 走运行期探测。
     */
    override fun declaredCapabilities(): Set<PlatformCapability> {
        return super.declaredCapabilities() + PlatformCapability.AT_ALL
    }

    /**
     * 群可达性判断收口到 llbot vendor client，未连接或探测失败时统一返回 false。
     */
    override suspend fun isGroupReachable(groupId: Long): Boolean {
        return llBotClient.isBotInGroup(groupId)
    }

    /**
     * @全体 能力按 llbot 当前登录态与群内身份探测，避免继续沿用 generic OneBot11 的保守不支持语义。
     */
    override suspend fun supportsAtAllInGroup(groupId: Long): Boolean {
        return llBotClient.canAtAllInGroup(groupId)
    }
}
