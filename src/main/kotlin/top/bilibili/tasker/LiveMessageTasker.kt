package top.bilibili.tasker

import kotlinx.coroutines.CancellationException
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
import top.bilibili.service.DynamicService
import top.bilibili.service.FeatureSwitchService
import top.bilibili.service.resolveGradientPalette

/**
 * 将直播详情转换为可发送消息。
 */
object LiveMessageTasker : BiliTasker() {
    override var interval: Int = 0
    override val wrapMainInBusinessLifecycle = false

    private val liveChannel by BiliBiliBot::liveChannel
    private val messageChannel by BiliBiliBot::messageChannel

    override suspend fun main() {
        val liveDetail = liveChannel.receiveCatching().getOrNull()
            ?: throw CancellationException("直播通道已关闭")
        runBusinessOperation("process-message") {
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
    }

    /**
     * 构建直播推送消息。
     *
     * @param contact 指定投递联系人
     */
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
            makeLive(contact),
            contact
        )
    }

    /**
     * 生成直播推送配图。
     *
     * @param contact 指定投递联系人
     */
    suspend fun LiveInfo.makeLive(contact: String? = null): String? {
        return if (FeatureSwitchService.canRenderPushDraw()) {
            logger.info("开始生成直播封面图片...")
            val palette = resolveGradientPalette(uid, contact)
            val drawPath = makeDrawLive(palette.themeColor, palette.backgroundColors, contact, palette.source.color)
            logger.info("直播封面图片生成完成: $drawPath")
            drawPath
        } else {
            logger.info("图片生成已禁用，跳过")
            null
        }
    }

}
