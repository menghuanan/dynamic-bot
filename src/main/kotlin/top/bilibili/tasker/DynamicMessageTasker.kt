package top.bilibili.tasker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Color
import top.bilibili.core.BiliBiliBot
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.data.*
import top.bilibili.data.DynamicType.*
import top.bilibili.draw.drawBlockedDefault
import top.bilibili.draw.drawGeneral
import top.bilibili.draw.makeDrawDynamic
import top.bilibili.draw.makeRGB
import top.bilibili.skia.SkiaManager
import top.bilibili.utils.*
import top.bilibili.service.DynamicService
import top.bilibili.service.FeatureSwitchService
import top.bilibili.service.normalizeArticleOpusDisplayTree
import top.bilibili.service.parseGradientColors
import top.bilibili.service.resolveGradientPalette

/**
 * 将动态详情转换为可发送消息。
 */
object DynamicMessageTasker : BiliTasker() {

    override var interval: Int = 0
    override val wrapMainInBusinessLifecycle = false

    private val dynamicChannel by BiliBiliBot::dynamicChannel
    private val messageChannel by BiliBiliBot::messageChannel

    private val dynamic by BiliData::dynamic
    private val bangumi by BiliData::bangumi

    override suspend fun main() {
        val dynamicDetail = dynamicChannel.receiveCatching().getOrNull()
            ?: throw CancellationException("动态通道已关闭")
        runBusinessOperation("process-message") {
            withTimeout(180002) {
            val dynamicItem = dynamicDetail.item
            logger.info("开始处理动态: ${dynamicItem.modules.moduleAuthor.name} (${dynamicItem.modules.moduleAuthor.mid}) - ${dynamicItem.typeStr}")
            try {
                syncSubscriptionName(dynamicItem.modules.moduleAuthor.mid, dynamicItem.modules.moduleAuthor.name)
                val message = dynamicItem.buildMessage(dynamicDetail.contact)
                logger.info("动态消息构建成功，准备发送到 messageChannel")
                messageChannel.send(message)
                logger.info("动态消息已发送到 messageChannel")
            } catch (e: Exception) {
                logger.error("处理动态失败: ${e.message}", e)
            }
            }
        }
    }

    /**
     * 同步更新订阅记录中的昵称。
     *
     * @param uid 用户 ID
     * @param latestName 最新昵称
     */
    internal fun syncSubscriptionName(uid: Long, latestName: String) {
        val subData = dynamic[uid] ?: return
        if (subData.name != latestName) {
            subData.name = latestName
        }
    }

    /**
     * 判断当前动态是否为仅限专属展示的动态。
     */
    fun DynamicItem.isUnlocked(): Boolean =
        modules.moduleDynamic.major?.type != "MAJOR_TYPE_BLOCKED"
            && modules.moduleAuthor.iconBadge?.text == "专属动态"

    /**
     * 构建动态推送消息。
     *
     * @param contact 指定投递联系人
     */
    suspend fun DynamicItem.buildMessage(contact: String? = null): DynamicMessage {
        // 先规整专栏/opus 展示树，可减少后续取标题、摘要时的分支差异。
        normalizeArticleOpusDisplayTree()

        val pgcSeasonId = if (type == DYNAMIC_TYPE_PGC || type == DYNAMIC_TYPE_PGC_UNION) {
            modules.moduleDynamic.major?.pgc?.seasonId?.toLong()
        } else null

        return DynamicMessage(
            did,
            modules.moduleAuthor.mid,
            modules.moduleAuthor.name,
            type,
            pgcSeasonId,
            formatRelativeTime,
            time.toInt(),
            textContent(),
            dynamicImages(),
            dynamicLinks(),
            makeDynamic(contact),
            contact
        )
    }

    /**
     * 提取动态的主要文本内容。
     */
    fun DynamicItem.textContent(): String {
        if(isUnlocked()){
            return "此动态为专属动态\n请自行查看详情内容"
        }
        return when (type) {
            DYNAMIC_TYPE_FORWARD -> "${modules.moduleDynamic.desc?.text}\n\n 转发 ${orig?.modules?.moduleAuthor?.name} 的动态:\n${orig?.textContent()}"
            DYNAMIC_TYPE_WORD,
            DYNAMIC_TYPE_DRAW -> modules.moduleDynamic.desc?.text
                ?: modules.moduleDynamic.major?.blocked?.hintMessage
                ?: (modules.moduleDynamic.major?.opus?.title + "\n" + modules.moduleDynamic.major?.opus?.summary?.text)
            DYNAMIC_TYPE_ARTICLE -> modules.moduleDynamic.major?.article?.title
                ?: modules.moduleDynamic.major?.opus?.title!!
            DYNAMIC_TYPE_AV -> modules.moduleDynamic.major?.archive?.title!!
            DYNAMIC_TYPE_MUSIC -> modules.moduleDynamic.major?.music?.title!!
            DYNAMIC_TYPE_PGC -> modules.moduleDynamic.major?.pgc?.title!!
            DYNAMIC_TYPE_PGC_UNION -> modules.moduleDynamic.major?.pgc?.title!!
            DYNAMIC_TYPE_UGC_SEASON -> modules.moduleDynamic.major?.ugcSeason?.title!!
            DYNAMIC_TYPE_COMMON_VERTICAL,
            DYNAMIC_TYPE_COMMON_SQUARE -> modules.moduleDynamic.major?.common?.title!!
            DYNAMIC_TYPE_LIVE -> modules.moduleDynamic.major?.live?.title!!
            DYNAMIC_TYPE_LIVE_RCMD -> modules.moduleDynamic.major?.liveRcmd?.liveInfo?.livePlayInfo?.title!!
            DYNAMIC_TYPE_NONE -> modules.moduleDynamic.major?.none?.tips!!
            DYNAMIC_TYPE_UNKNOWN -> "未知的动态类型: $typeStr"
        }
    }

    /**
     * 提取动态相关图片列表。
     */
    suspend fun DynamicItem.dynamicImages(): List<String>? {
        if (isUnlocked()) {
            if (!FeatureSwitchService.canRenderPushDraw()) {
                return emptyList()
            }
            val path = SkiaManager.executeDrawing {
                val blockedImg = drawBlockedDefault(this)
                // 专属动态原图不可直接获取，缓存默认占位图可保持推送结构稳定。
                cacheImage(blockedImg, "blocked_default.png", CacheType.IMAGES)
                // All resources automatically released when session closes
            }
            return listOf("cache/$path")
        }
        return when (type) {
            DYNAMIC_TYPE_FORWARD -> orig?.dynamicImages()
            DYNAMIC_TYPE_DRAW -> when (modules.moduleDynamic.major?.type) {
                "MAJOR_TYPE_DRAW" -> modules.moduleDynamic.major?.draw?.items?.map { it.src }
                "MAJOR_TYPE_BLOCKED" -> {
                    if (!FeatureSwitchService.canRenderPushDraw()) {
                        listOf()
                    } else {
                        val path = modules.moduleDynamic.major.blocked?.let {
                            SkiaManager.executeDrawing {
                                val blockedImg = it.drawGeneral(this)
                                cacheImage(blockedImg, "blocked_$idStr.png", CacheType.IMAGES)
                            }
                        }
                        listOfNotNull(path?.let { "cache/$it" })
                    }
                }
                else -> listOf()
            }
            DYNAMIC_TYPE_ARTICLE -> modules.moduleDynamic.major?.article?.covers
            DYNAMIC_TYPE_AV -> listOf(modules.moduleDynamic.major?.archive?.cover!!)
            DYNAMIC_TYPE_MUSIC -> listOf(modules.moduleDynamic.major?.music?.cover!!)
            DYNAMIC_TYPE_PGC -> listOf(modules.moduleDynamic.major?.pgc?.cover!!)
            DYNAMIC_TYPE_UGC_SEASON -> listOf(modules.moduleDynamic.major?.ugcSeason?.cover!!)
            DYNAMIC_TYPE_COMMON_SQUARE -> listOf(modules.moduleDynamic.major?.common?.cover!!)
            DYNAMIC_TYPE_LIVE -> listOf(modules.moduleDynamic.major?.live?.cover!!)
            DYNAMIC_TYPE_LIVE_RCMD -> listOf(modules.moduleDynamic.major?.liveRcmd?.liveInfo?.livePlayInfo?.cover!!)
            else -> listOf()
        }
    }

    /**
     * 构建动态消息中的相关跳转链接。
     */
    suspend fun DynamicItem.dynamicLinks(): List<DynamicMessage.Link> {
        return when (type) {
            DYNAMIC_TYPE_FORWARD -> {
                listOf(
                    DynamicMessage.Link("动态", DYNAMIC_LINK(did)),
                    DynamicMessage.Link("原动态", DYNAMIC_LINK(orig!!.did)),
                )
            }

            DYNAMIC_TYPE_NONE,
            DYNAMIC_TYPE_WORD,
            DYNAMIC_TYPE_DRAW,
            DYNAMIC_TYPE_COMMON_VERTICAL,
            DYNAMIC_TYPE_COMMON_SQUARE,
            DYNAMIC_TYPE_UGC_SEASON,
            DYNAMIC_TYPE_UNKNOWN -> {
                listOf(
                    DynamicMessage.Link("动态", DYNAMIC_LINK(did))
                )
            }

            DYNAMIC_TYPE_ARTICLE -> {
                listOf(
                    DynamicMessage.Link(
                        DYNAMIC_TYPE_ARTICLE.text,
                        if (this.modules.moduleDynamic.major?.type == "MAJOR_TYPE_OPUS") OPUS_LINK(did) else
                            ARTICLE_LINK(this.modules.moduleDynamic.major?.article?.id ?: this.did)
                    ),
                    DynamicMessage.Link("动态", DYNAMIC_LINK(did))
                )
            }

            DYNAMIC_TYPE_AV -> {
                listOf(
                    DynamicMessage.Link(
                        DYNAMIC_TYPE_AV.text,
                        VIDEO_LINK(this.modules.moduleDynamic.major?.archive?.aid.toString())
                    ),
                    DynamicMessage.Link("动态", DYNAMIC_LINK(did))
                )
            }

            DYNAMIC_TYPE_MUSIC -> {
                listOf(
                    DynamicMessage.Link(
                        DYNAMIC_TYPE_MUSIC.text,
                        MUSIC_LINK(this.modules.moduleDynamic.major?.music?.id!!.toString())
                    ),
                    DynamicMessage.Link("动态", DYNAMIC_LINK(did))
                )
            }

            DYNAMIC_TYPE_PGC,
            DYNAMIC_TYPE_PGC_UNION -> {
                listOf(
                    DynamicMessage.Link(type.text, EPISODE_LINK(this.modules.moduleDynamic.major?.pgc?.epid!!.toString())),
                    DynamicMessage.Link("动态", DYNAMIC_LINK(did))
                )
            }

            DYNAMIC_TYPE_LIVE -> {
                listOf(
                    DynamicMessage.Link(
                        DYNAMIC_TYPE_LIVE.text,
                        LIVE_LINK(this.modules.moduleDynamic.major?.live?.id!!.toString())
                    ),
                    DynamicMessage.Link("动态", DYNAMIC_LINK(did))
                )
            }

            DYNAMIC_TYPE_LIVE_RCMD -> {
                listOf(
                    DynamicMessage.Link(
                        DYNAMIC_TYPE_LIVE_RCMD.text,
                        LIVE_LINK(this.modules.moduleDynamic.major?.liveRcmd?.liveInfo?.livePlayInfo?.roomId!!.toString())
                    ),
                    DynamicMessage.Link("动态", DYNAMIC_LINK(did))
                )
            }

        }

    }

    /**
     * 生成动态卡片图片。
     *
     * @param contact 指定投递联系人
     */
    suspend fun DynamicItem.makeDynamic(contact: String? = null): String? {
        return if (FeatureSwitchService.canRenderPushDraw()) {
            if (this.type == DYNAMIC_TYPE_PGC) {
                // 番剧订阅可能配置了专属主题色，优先沿用可让推送风格与订阅来源保持一致。
                val color = bangumi[mid]?.color ?: BiliConfigManager.config.imageConfig.defaultColor
                val colors = parseGradientColors(color)
                makeDrawDynamic(colors, contact, color)
            } else {
                val palette = resolveGradientPalette(mid, contact)
                makeDrawDynamic(palette.themeColor, palette.backgroundColors, contact, palette.source.color)
            }
        } else null
    }

}
