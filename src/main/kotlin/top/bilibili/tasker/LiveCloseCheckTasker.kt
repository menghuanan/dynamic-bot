package top.bilibili.tasker

import top.bilibili.core.BiliBiliBot
import top.bilibili.BiliConfigManager
import top.bilibili.api.getLiveStatus
import top.bilibili.data.LIVE_LINK
import top.bilibili.data.LiveCloseMessage
import top.bilibili.utils.formatDuration
import top.bilibili.utils.formatRelativeTime
import top.bilibili.utils.formatTime
import java.time.Instant


object LiveCloseCheckTasker : BiliCheckTasker("LiveClose")  {

    override var interval: Int = BiliConfigManager.config.checkConfig.liveInterval

    override var lowSpeedEnable = false
    override var checkReportEnable = false

    private val liveUsers by BiliBiliBot::liveUsers
    private var nowTime = Instant.now().epochSecond

    override suspend fun main() {
        if (liveUsers.isNotEmpty()) {
            nowTime = Instant.now().epochSecond

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

}