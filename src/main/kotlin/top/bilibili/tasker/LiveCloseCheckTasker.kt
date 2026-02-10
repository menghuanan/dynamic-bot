package top.bilibili.tasker

import top.bilibili.core.BiliBiliBot
import top.bilibili.BiliConfigManager
import top.bilibili.api.getLiveStatus
import top.bilibili.data.LIVE_LINK
import top.bilibili.data.LiveCloseMessage
import top.bilibili.utils.formatDuration
import top.bilibili.utils.formatRelativeTime
import top.bilibili.utils.formatTime
import top.bilibili.utils.logger
import java.time.Instant


object LiveCloseCheckTasker : BiliCheckTasker("LiveClose")  {

    override var interval: Int = BiliConfigManager.config.checkConfig.liveInterval

    override var lowSpeedEnable = false
    override var checkReportEnable = false

    private val liveUsers by BiliBiliBot::liveUsers
    private var nowTime = Instant.now().epochSecond

    // ✅ 新增：超时时间 24 小时（正常直播不会超过这个时间）
    private const val LIVE_USER_TIMEOUT = 86400L

    override suspend fun main() {
        nowTime = Instant.now().epochSecond

        // ✅ 新增：清理超时的直播记录，防止 liveUsers 无限增长
        cleanExpiredLiveUsers()

        if (liveUsers.isNotEmpty()) {
            val liveStatusMap = client.getLiveStatus(liveUsers.map { it.key })
            val liveStatusList = liveStatusMap?.map { it.value }?.filter { it.liveStatus != 1 }

            liveStatusList?.forEach { info ->
                val liveTime = liveUsers[info.uid]!!
                BiliBiliBot.messageChannel.send(LiveCloseMessage(
                    info.roomId,
                    info.uid,
                    info.uname,
                    liveTime.formatRelativeTime,
                    0,
                    nowTime.formatTime,
                    (nowTime - liveTime).formatDuration(),
                    info.title,
                    info.area,
                    LIVE_LINK(info.roomId.toString())
                ))
                liveUsers.remove(info.uid)
            }
        }
    }

    /**
     * ✅ 新增：清理超时的直播记录
     * 防止因 API 调用失败或用户长期直播导致 liveUsers 无限增长
     */
    private fun cleanExpiredLiveUsers() {
        val expiredUsers = liveUsers.filter { (_, startTime) ->
            nowTime - startTime > LIVE_USER_TIMEOUT
        }.keys.toList()

        if (expiredUsers.isNotEmpty()) {
            logger.info("清理 ${expiredUsers.size} 个超时的直播记录（超过 ${LIVE_USER_TIMEOUT / 3600} 小时）")
            expiredUsers.forEach { liveUsers.remove(it) }
        }
    }

}