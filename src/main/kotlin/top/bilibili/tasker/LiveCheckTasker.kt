package top.bilibili.tasker

import kotlinx.coroutines.withTimeout
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.api.getLive
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.BiliBiliBot.liveUsers
import top.bilibili.service.PushFanoutService
import top.bilibili.utils.logger
import top.bilibili.utils.sendAll
import java.time.Instant

/**
 * 轮询关注列表中的新开播直播并投递到消息流水线。
 */
object LiveCheckTasker : BiliCheckTasker("LiveCheckTasker") {
    // 真实轮询间隔由 BiliCheckTasker 按 normalRange/lowSpeedRange 动态重算；这里仅保留初始化回退值。
    override var interval = 60
    private val liveCloseEnable = BiliConfigManager.config.enableConfig.liveCloseNotifyEnable

    private val liveChannel by BiliBiliBot::liveChannel
    private val dynamic by BiliData::dynamic

    private var lastLive: Long = Instant.now().epochSecond

    override suspend fun main() = withTimeout(180003) {
        logger.debug("开始直播检查...")

        val followingUsers = dynamic.filter { it.value.contacts.isNotEmpty() }.map { it.key }
        if (followingUsers.isEmpty()) {
            logger.debug("没有任何订阅，跳过直播检查")
            return@withTimeout
        }

        logger.debug("订阅的UP主UID: ${followingUsers.joinToString()}")
        logger.debug("当前 lastLive = $lastLive")

        val liveList = client.getLive(source = "LiveCheckTasker.followed-live-list")
        if (liveList == null) {
            logger.warn("获取关注列表直播失败")
            return@withTimeout
        }

        val lives = liveList.rooms
            .filter {
                val isNew = it.liveTime > lastLive
                logger.debug("检查直播时间: ${it.uname} (${it.uid}), liveTime=${it.liveTime}, lastLive=$lastLive, isNew=$isNew")
                isNew
            }
            .filter {
                val isFollowing = followingUsers.contains(it.uid)
                logger.debug("检查是否已订阅: ${it.uname} (${it.uid}), isFollowing=$isFollowing")
                isFollowing
            }
            .sortedBy { it.liveTime }

        logger.debug("过滤后新开播的直播数: ${lives.size}")

        if (lives.isNotEmpty()) {
            logger.info("检测到 ${lives.size} 个新开播直播")
            lives.forEach {
                logger.info("新直播 ${it.uname} (${it.uid}) - ${it.title}")
            }

            lastLive = lives.last().liveTime
            logger.debug("更新 lastLive 为 $lastLive")

            logger.debug("发送 ${lives.size} 个直播到 liveChannel...")
            val details = lives.flatMap { live ->
                PushFanoutService.liveDetailsForContacts(live, PushFanoutService.resolveLiveContacts(live.uid, dynamic))
            }
            liveChannel.sendAll(details)
            logger.debug("直播已发送到 liveChannel")

            if (liveCloseEnable) {
                // 仅记录已实际推送过的直播，避免下播提醒覆盖到未订阅或未通知的直播间。
                liveUsers.putAll(lives.map { it.uid to it.liveTime })
                logger.debug("已记录 ${lives.size} 个直播用于下播检测")
            }
        }
    }
}
