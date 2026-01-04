package top.bilibili.tasker

import kotlinx.coroutines.withTimeout
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.BiliBiliBot.liveUsers
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.api.getLive
import top.bilibili.api.getLiveStatus
import top.bilibili.data.LiveDetail
import top.bilibili.data.LiveInfo
import top.bilibili.utils.sendAll
import top.bilibili.utils.logger
import java.time.Instant

object LiveCheckTasker : BiliCheckTasker("Live") {
    override var interval = BiliConfigManager.config.checkConfig.liveInterval
    private val liveCloseEnable = BiliConfigManager.config.enableConfig.liveCloseNotifyEnable

    private val liveChannel by BiliBiliBot::liveChannel
    private val dynamic by BiliData::dynamic

    // 初始化为 10 分钟前，避免遗漏已开播的直播
    private var lastLive: Long = Instant.now().epochSecond - 600

    override suspend fun main() = withTimeout(180003) {
        logger.debug("开始直播检查...")

        // 获取订阅列表中的 UP 主 UID
        val followingUsers = dynamic.filter { it.value.contacts.isNotEmpty() }.map { it.key }
        logger.debug("订阅的UP主 UID: ${followingUsers.joinToString()}")
        logger.debug("当前 lastLive = $lastLive")

        // 收集所有正在直播的 UP 主
        val allLiveRooms = mutableMapOf<Long, LiveInfo>()

        // 1. 获取账号关注列表中的直播（保留原有功能）
        val followedLiveList = client.getLive()
        if (followedLiveList != null) {
            logger.debug("✅ 从关注列表获取到 ${followedLiveList.rooms.size} 个直播间")
            followedLiveList.rooms.forEach { room ->
                allLiveRooms[room.uid] = room
                logger.debug("  关注列表直播: ${room.uname} (UID: ${room.uid}), 标题: ${room.title}, liveTime: ${room.liveTime}")
            }
        } else {
            logger.warn("⚠️ 获取关注列表直播失败")
        }

        // 2. 查询订阅列表中 UP 主的直播状态（新增功能）
        if (followingUsers.isNotEmpty()) {
            val subscribedLiveStatus = client.getLiveStatus(followingUsers)
            if (subscribedLiveStatus != null) {
                val liveCount = subscribedLiveStatus.values.count { it.liveStatus == 1 }
                logger.debug("✅ 从订阅列表查询到 ${liveCount} 个正在直播的UP主（共查询 ${followingUsers.size} 个）")
                subscribedLiveStatus.forEach { (uid, liveInfo) ->
                    if (liveInfo.liveStatus == 1) {
                        // 只添加正在直播的（liveStatus == 1）
                        allLiveRooms[uid] = liveInfo
                        logger.debug("  订阅列表直播: ${liveInfo.uname} (UID: ${uid}), 标题: ${liveInfo.title}, liveTime: ${liveInfo.liveTime}, status: ${liveInfo.liveStatus}")
                    }
                }
            } else {
                logger.warn("⚠️ 查询订阅列表直播状态失败")
            }
        }

        // 合并结果后的总数
        logger.debug("📊 合并后共 ${allLiveRooms.size} 个正在直播的UP主（已去重）")

        // 过滤：新开播的 + 在订阅列表中的
        val lives = allLiveRooms.values
            .filter {
                val isNew = it.liveTime > lastLive
                logger.debug("检查直播时间: ${it.uname} (${it.uid}), liveTime=${it.liveTime}, lastLive=$lastLive, isNew=$isNew")
                isNew
            }.filter {
                val isFollowing = followingUsers.contains(it.uid)
                logger.debug("检查是否订阅: ${it.uname} (${it.uid}), isFollowing=$isFollowing")
                isFollowing
            }.sortedBy {
                it.liveTime
            }

        logger.debug("过滤后新开播的直播数: ${lives.size}")

        if (lives.isNotEmpty()) {
            logger.info("检测到 ${lives.size} 个新开播直播")
            lives.forEach {
                logger.info("新直播: ${it.uname} (${it.uid}) - ${it.title}")
            }
            lastLive = lives.last().liveTime
            logger.debug("更新 lastLive 为: $lastLive")

            logger.debug("发送 ${lives.size} 个直播到 liveChannel...")
            liveChannel.sendAll(lives.map { LiveDetail(it) })
            logger.debug("直播已发送到 liveChannel")

            if (liveCloseEnable) {
                liveUsers.putAll(lives.map { it.uid to it.liveTime })
                logger.debug("已记录 ${lives.size} 个直播用于下播检测")
            }
        }
    }
}