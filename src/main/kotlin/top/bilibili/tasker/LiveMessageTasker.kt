package top.bilibili.tasker

import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Color
import top.bilibili.core.BiliBiliBot
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.data.LIVE_LINK
import top.bilibili.data.LiveInfo
import top.bilibili.data.LiveMessage
import top.bilibili.draw.makeDrawLive
import top.bilibili.draw.makeRGB
import top.bilibili.utils.formatRelativeTime
import top.bilibili.utils.logger

object LiveMessageTasker : BiliTasker() {
    override var interval: Int = 0

    private val liveChannel by BiliBiliBot::liveChannel
    private val messageChannel by BiliBiliBot::messageChannel

    override suspend fun main() {
        val liveDetail = liveChannel.receive()
        withTimeout(180004) {
            val liveInfo = liveDetail.item
            logger.info("开始处理直播: ${liveInfo.uname} (${liveInfo.uid}) - ${liveInfo.title}")
            try {
                val message = liveInfo.buildMessage(liveDetail.contact)
                logger.info("直播消息构建成功，准备发送到 messageChannel")
                messageChannel.send(message)
                logger.info("直播消息已发送到 messageChannel")
            } catch (e: Exception) {
                logger.error("处理直播失败: ${e.message}", e)
            }
        }
    }

    suspend fun LiveInfo.buildMessage(contact: String? = null): LiveMessage {
        return LiveMessage(
            roomId,
            uid,
            this.uname,
            liveTime.formatRelativeTime,
            liveTime.toInt(),
            title,
            cover,
            area,
            LIVE_LINK(roomId.toString()),
            makeLive(),
            contact
        )
    }

    suspend fun LiveInfo.makeLive(): String? {
        return if (BiliConfigManager.config.enableConfig.drawEnable) {
            logger.info("开始生成直播封面图片...")
            val color = BiliData.dynamic[uid]?.color ?: BiliConfigManager.config.imageConfig.defaultColor
            val colors = color.split(";", "；").map { Color.makeRGB(it.trim()) }
            val drawPath = makeDrawLive(colors)
            logger.info("直播封面图片生成完成: $drawPath")
            drawPath
        } else {
            logger.info("图片生成已禁用，跳过")
            null
        }
    }

}