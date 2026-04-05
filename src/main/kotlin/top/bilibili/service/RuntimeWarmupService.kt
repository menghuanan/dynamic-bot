package top.bilibili.service

import kotlinx.coroutines.withTimeout
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.api.getLive
import top.bilibili.api.getLiveStatus
import top.bilibili.api.getNewDynamic
import top.bilibili.connector.CapabilityRequest
import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.PlatformCapabilityService
import top.bilibili.connector.PlatformChatType
import top.bilibili.core.BiliBiliBot
import top.bilibili.data.DynamicMessage
import top.bilibili.data.DynamicType
import top.bilibili.data.LiveCloseMessage
import top.bilibili.data.LiveInfo
import top.bilibili.data.LiveMessage
import top.bilibili.tasker.DynamicMessageTasker
import top.bilibili.tasker.LiveMessageTasker
import top.bilibili.tasker.SendTasker
import top.bilibili.utils.biliClient
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.parsePlatformContact
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动后一次性执行运行期预热，覆盖 API 解码、消息构建、模板渲染与发送前能力检查链路。
 */
object RuntimeWarmupService {
    private const val STARTUP_WARMUP_TIMEOUT_MS = 90_000L
    private const val API_PRELOAD_SAMPLE_LIMIT = 3
    private val warmupStarted = AtomicBoolean(false)

    /**
     * 仅在进程生命周期内执行一次预热，避免重复触发造成额外干扰。
     */
    suspend fun warmupOnceAfterStartup() {
        if (!warmupStarted.compareAndSet(false, true)) {
            BiliBiliBot.logger.debug("启动预热已执行，跳过重复请求")
            return
        }

        val beginAt = System.currentTimeMillis()
        BiliBiliBot.logger.info("启动预热开始：覆盖 API/消息构建/模板渲染/发送前检查")

        runCatching {
            withTimeout(STARTUP_WARMUP_TIMEOUT_MS) {
                val context = preloadRuntimeData()
                warmupMessagePipeline(context)
                warmupFallbackBranches(context.contactSubject)
                warmupCapabilityChecks(context.contactSubject)
            }
        }.onFailure { error ->
            BiliBiliBot.logger.warn("启动预热未完整执行: ${error.message}")
        }

        val costMs = (System.currentTimeMillis() - beginAt).coerceAtLeast(0L)
        BiliBiliBot.logger.info("启动预热结束，耗时=${costMs}ms")
    }

    /**
     * 预拉取动态/直播接口数据，优先让协议解码与热点字段访问在冷启动窗口内完成。
     */
    private suspend fun preloadRuntimeData(): WarmupContext {
        val dynamicItem = runCatching {
            biliClient.getNewDynamic(
                page = 1,
                type = "all",
                source = "RuntimeWarmup.dynamic-list",
            )?.items?.firstOrNull()
        }.onFailure { error ->
            BiliBiliBot.logger.debug("预热动态列表失败: ${error.message}")
        }.getOrNull()

        val liveInfo = runCatching {
            biliClient.getLive(
                page = 1,
                pageSize = 20,
                source = "RuntimeWarmup.live-list",
            )?.rooms?.firstOrNull()
        }.onFailure { error ->
            BiliBiliBot.logger.debug("预热直播列表失败: ${error.message}")
        }.getOrNull()

        val statusUids = buildSet {
            dynamicItem?.let { item -> add(item.modules.moduleAuthor.mid) }
            liveInfo?.let { info -> add(info.uid) }
            BiliData.dynamic.keys.take(API_PRELOAD_SAMPLE_LIMIT).forEach { uid -> add(uid) }
        }.toList()

        if (statusUids.isNotEmpty()) {
            runCatching {
                biliClient.getLiveStatus(
                    uids = statusUids,
                    source = "RuntimeWarmup.live-status",
                )
            }.onFailure { error ->
                BiliBiliBot.logger.debug("预热直播状态失败: ${error.message}")
            }
        }

        return WarmupContext(
            contactSubject = resolveWarmupContactSubject(),
            dynamicItem = dynamicItem,
            liveInfo = liveInfo,
        )
    }

    /**
     * 覆盖“动态/开播/下播”三类消息从构建到发送前处理的主链路。
     */
    private suspend fun warmupMessagePipeline(context: WarmupContext) {
        val contactSubject = context.contactSubject
        val dynamicMessage = buildDynamicWarmupMessage(context.dynamicItem, contactSubject)
        val liveMessage = buildLiveWarmupMessage(context.liveInfo, contactSubject)
        val liveCloseMessage = buildLiveCloseWarmupMessage(contactSubject)

        val warmupMessages = listOf(dynamicMessage, liveMessage, liveCloseMessage)
        warmupMessages.forEach { message ->
            runCatching {
                val segments = SendTasker.warmupMessageBuildPath(message, contactSubject)
                BiliBiliBot.logger.debug(
                    "启动预热消息链路完成: type={}, contact={}, segments={}",
                    message::class.simpleName,
                    contactSubject,
                    segments.size,
                )
            }.onFailure { error ->
                BiliBiliBot.logger.debug("预热消息链路失败(${message::class.simpleName}): ${error.message}")
            }
        }
    }

    /**
     * 主动覆盖模板渲染中的降级分支，确保 draw 缺失与模板回退逻辑在启动窗口完成编译。
     */
    private suspend fun warmupFallbackBranches(contactSubject: String) {
        val drawOnlyMessage = buildDynamicWarmupMessage(dynamicItem = null, contactSubject = contactSubject)
            .copy(drawPath = null, images = emptyList())

        runCatching {
            val segments = TemplateRenderService.buildSegments(
                message = drawOnlyMessage,
                contactStr = contactSubject,
                overrideTemplate = "{draw}",
            )
            BiliBiliBot.logger.debug("启动预热模板降级分支完成: contact={}, segments={}", contactSubject, segments.size)
        }.onFailure { error ->
            BiliBiliBot.logger.debug("预热模板降级分支失败: ${error.message}")
        }
    }

    /**
     * 预热发送能力 guard 与 unsupported 分支，避免首条业务消息触发额外冷路径开销。
     */
    private suspend fun warmupCapabilityChecks(contactSubject: String) {
        val contact = parsePlatformContact(contactSubject) ?: return

        runCatching {
            PlatformCapabilityService.guardMessageSend(contact)
            PlatformCapabilityService.guardReplyInContact(contact)
            if (contact.type == PlatformChatType.GROUP) {
                PlatformCapabilityService.guardAtAllInContact(contact)
            }
            // 这里显式传入空 contact 触发 unsupported 路径，预热降级分支并验证不会抛出异常。
            PlatformCapabilityService.guardCapability(
                CapabilityRequest(
                    capability = PlatformCapability.SEND_MESSAGE,
                    contact = null,
                ),
            )
        }.onFailure { error ->
            BiliBiliBot.logger.debug("预热能力检查失败: ${error.message}")
        }
    }

    /**
     * 解析预热联系人：优先使用订阅联系人，缺失时回退管理员 subject，再回退到 onebot 私聊占位。
     */
    private fun resolveWarmupContactSubject(): String {
        val candidates = buildList {
            BiliData.dynamic.values.forEach { subData ->
                addAll(subData.contacts)
            }
            BiliData.bangumi.values.forEach { bangumi ->
                addAll(bangumi.contacts)
            }
            BiliConfigManager.config.normalizedAdminSubject()?.let { adminSubject ->
                add(adminSubject)
            }
        }

        candidates.forEach { raw ->
            val normalized = normalizeContactSubject(raw) ?: raw
            if (parsePlatformContact(normalized) != null) {
                return normalized
            }
        }

        val fallbackUid = BiliBiliBot.uid.takeIf { uid -> uid > 0L } ?: 0L
        return "onebot11:private:$fallbackUid"
    }

    /**
     * 优先使用真实动态样本构建预热消息，缺失样本时回退到合成消息，保证链路可执行。
     */
    private suspend fun buildDynamicWarmupMessage(
        dynamicItem: top.bilibili.data.DynamicItem?,
        contactSubject: String,
    ): DynamicMessage {
        if (dynamicItem != null) {
            return with(DynamicMessageTasker) {
                dynamicItem.buildMessage(contact = contactSubject)
            }
        }

        val now = Instant.now().epochSecond.toInt()
        return DynamicMessage(
            did = "warmup-dynamic",
            mid = 0L,
            name = "warmup-dynamic",
            type = DynamicType.DYNAMIC_TYPE_WORD,
            time = "warmup",
            timestamp = now,
            content = "warmup dynamic content",
            images = emptyList(),
            links = listOf(DynamicMessage.Link("动态", "https://t.bilibili.com/warmup-dynamic")),
            drawPath = null,
            contact = contactSubject,
        )
    }

    /**
     * 优先使用真实直播样本构建预热消息，缺失样本时回退到合成消息，保证链路可执行。
     */
    private suspend fun buildLiveWarmupMessage(
        liveInfo: LiveInfo?,
        contactSubject: String,
    ): LiveMessage {
        if (liveInfo != null) {
            return with(LiveMessageTasker) {
                liveInfo.buildMessage(contact = contactSubject)
            }
        }

        val now = Instant.now().epochSecond.toInt()
        return LiveMessage(
            rid = 0L,
            mid = 0L,
            name = "warmup-live",
            time = "warmup",
            timestamp = now,
            title = "warmup live title",
            cover = "https://example.invalid/warmup-live-cover.png",
            area = "warmup",
            link = "https://live.bilibili.com/0",
            drawPath = null,
            contact = contactSubject,
        )
    }

    /**
     * 合成下播消息用于覆盖模板分支，避免该路径首轮运行才触发冷编译。
     */
    private fun buildLiveCloseWarmupMessage(contactSubject: String): LiveCloseMessage {
        val now = Instant.now().epochSecond.toInt()
        return LiveCloseMessage(
            rid = 0L,
            mid = 0L,
            name = "warmup-live-close",
            time = "warmup",
            timestamp = now,
            endTime = "warmup",
            duration = "1m",
            title = "warmup live close",
            area = "warmup",
            link = "https://live.bilibili.com/0",
            drawPath = null,
            contact = contactSubject,
        )
    }

    /**
     * 启动预热上下文。
     */
    private data class WarmupContext(
        val contactSubject: String,
        val dynamicItem: top.bilibili.data.DynamicItem?,
        val liveInfo: LiveInfo?,
    )
}
