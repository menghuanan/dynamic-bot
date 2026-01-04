package top.bilibili.tasker

import kotlinx.coroutines.withTimeout
import top.bilibili.core.BiliBiliBot
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.api.getNewDynamic
import top.bilibili.data.DynamicDetail
import top.bilibili.data.DynamicType
import top.bilibili.utils.sendAll
import top.bilibili.utils.time
import top.bilibili.utils.logger
import java.time.Instant

object DynamicCheckTasker : BiliCheckTasker("Dynamic") {

    override var interval = BiliConfigManager.config.checkConfig.interval

    private val dynamicChannel by BiliBiliBot::dynamicChannel

    private val dynamic by BiliData::dynamic
    private val bangumi by BiliData::bangumi

    private val listenAllDynamicMode = false

    private val banType = listOf(
        DynamicType.DYNAMIC_TYPE_LIVE,
        DynamicType.DYNAMIC_TYPE_LIVE_RCMD,
        //DynamicType.DYNAMIC_TYPE_PGC,
        //DynamicType.DYNAMIC_TYPE_PGC_UNION
    )

    private const val capacity = 200
    private val historyDynamic = ArrayList<String>(capacity)
    private var lastIndex = 0

    // 初始化为当前时间减去10分钟，避免首次启动推送大量旧动态
    private var lastDynamic: Long = Instant.now().epochSecond - 600

    override suspend fun main() = withTimeout(180001) {
        val dynamicList = client.getNewDynamic()
        if (dynamicList != null) {
            val followingUsers = dynamic.filter { it.value.contacts.isNotEmpty() }.map { it.key }

            val dynamics = dynamicList.items
                .filter {
                    !banType.contains(it.type)
                }.filter {
                    it.time > lastDynamic
                }.filter {
                    !historyDynamic.contains(it.did)
                }.filter {
                    if (listenAllDynamicMode) true
                    else if (it.type == DynamicType.DYNAMIC_TYPE_PGC || it.type == DynamicType.DYNAMIC_TYPE_PGC_UNION)
                        bangumi.contains(it.modules.moduleAuthor.mid)
                    else followingUsers.contains(it.modules.moduleAuthor.mid)
                }.sortedBy {
                    it.time
                }

            if (dynamics.isNotEmpty()) {
                logger.info("检测到 ${dynamics.size} 条新动态")
                dynamics.forEach {
                    logger.info("新动态: ${it.modules.moduleAuthor.name} - ${it.modules.moduleDynamic.desc?.text?.take(50) ?: "无文本"}")
                }
            }

            dynamics.map { it.did }.forEach {
                historyDynamic.add(lastIndex, it)
                lastIndex ++
                if (lastIndex >= capacity) lastIndex = 0
            }
            if (dynamics.isNotEmpty()) lastDynamic = dynamics.last().time
            dynamicChannel.sendAll(dynamics.map { DynamicDetail(it) })
        } else {
            logger.warn("获取动态列表失败")
        }
    }

}