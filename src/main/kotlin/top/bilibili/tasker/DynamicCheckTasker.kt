package top.bilibili.tasker

import java.io.File
import java.time.Instant
import kotlinx.coroutines.withTimeout
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.api.getNewDynamic
import top.bilibili.core.BiliBiliBot
import top.bilibili.data.DynamicDetail
import top.bilibili.data.DynamicList
import top.bilibili.data.DynamicType
import top.bilibili.service.PushFanoutService
import top.bilibili.utils.logger
import top.bilibili.utils.sendAll
import top.bilibili.utils.time

object DynamicCheckTasker : BiliCheckTasker("Dynamic") {

    override var interval = BiliConfigManager.config.checkConfig.interval

    private val dynamicChannel by BiliBiliBot::dynamicChannel

    private val dynamic by BiliData::dynamic
    private val bangumi by BiliData::bangumi

    private val listenAllDynamicMode = false

    private val banType = listOf(
        DynamicType.DYNAMIC_TYPE_LIVE,
        DynamicType.DYNAMIC_TYPE_LIVE_RCMD,
        // DynamicType.DYNAMIC_TYPE_PGC,
        // DynamicType.DYNAMIC_TYPE_PGC_UNION
    )

    private const val HISTORY_CAPACITY = 200
    private val historyDynamic = ArrayDeque<String>(HISTORY_CAPACITY)
    private val historyFile = File("data/dynamic_history.txt")

    // Initialize to now - 10 minutes to avoid pushing too many old dynamics on startup.
    private var lastDynamic: Long = Instant.now().epochSecond - 600

    override fun init() {
        super.init()
        if (!historyFile.exists()) return
        try {
            historyFile.readLines()
                .takeLast(HISTORY_CAPACITY)
                .forEach { historyDynamic.addLast(it) }
            logger.info("loaded ${historyDynamic.size} dynamic history entries")
        } catch (e: Exception) {
            logger.error("failed to load dynamic history", e)
        }
    }

    override suspend fun main() = withTimeout(180001) {
        // Skip API call if there are no subscriptions.
        val followingUsers = dynamic.filter { it.value.contacts.isNotEmpty() }.map { it.key }
        if (followingUsers.isEmpty() && bangumi.isEmpty()) {
            logger.debug("skip dynamic check: no active subscriptions")
            return@withTimeout
        }

        val dynamicList = client.getNewDynamic(source = "DynamicCheckTasker.poll")
        if (dynamicList == null) {
            logger.warn("failed to fetch dynamic list")
            return@withTimeout
        }

        val dynamics = dynamicList.items
            .filter { !banType.contains(it.type) }
            .filter { it.time > lastDynamic }
            .filter { !historyDynamic.contains(it.did) }
            .filter {
                if (listenAllDynamicMode) {
                    true
                } else if (it.type == DynamicType.DYNAMIC_TYPE_PGC || it.type == DynamicType.DYNAMIC_TYPE_PGC_UNION) {
                    bangumi.contains(it.modules.moduleAuthor.mid)
                } else {
                    followingUsers.contains(it.modules.moduleAuthor.mid)
                }
            }
            .sortedBy { it.time }

        if (dynamics.isNotEmpty()) {
            logger.info("detected ${dynamics.size} new dynamic item(s)")
            dynamics.forEach {
                logger.info(
                    "new dynamic ${it.modules.moduleAuthor.name} - " +
                        (it.modules.moduleDynamic.desc?.text?.take(50) ?: "<no-text>")
                )
            }
        }

        dynamics.map { it.did }.forEach { did ->
            if (historyDynamic.size >= HISTORY_CAPACITY) {
                historyDynamic.removeFirst()
            }
            historyDynamic.addLast(did)
        }

        if (dynamics.isNotEmpty()) {
            lastDynamic = dynamics.last().time
            saveHistory()
        }

        val details = dynamics.flatMap { item ->
            PushFanoutService.dynamicDetailsForContacts(
                item,
                PushFanoutService.resolveDynamicContacts(item, dynamic, bangumi)
            )
        }
        dynamicChannel.sendAll(details)
    }

    /**
     * Manual check, ignores time/history limits, used by /check.
     */
    suspend fun executeManualCheck(): Int = withTimeout(180001) {
        logger.info("$taskerName manual check triggered")
        val dynamicList = client.getNewDynamic(source = "DynamicCheckTasker.manual-check")
        return@withTimeout handleManualCheckResult(dynamicList, "manual-check")
    }

    private suspend fun handleManualCheckResult(dynamicList: DynamicList?, source: String): Int {
        if (dynamicList == null) {
            logger.warn("$source failed to fetch dynamic list")
            return 0
        }

        val followingUsers = dynamic.filter { it.value.contacts.isNotEmpty() }.map { it.key }

        val dynamics = dynamicList.items
            .filter { !banType.contains(it.type) }
            .filter {
                if (listenAllDynamicMode) {
                    true
                } else if (it.type == DynamicType.DYNAMIC_TYPE_PGC || it.type == DynamicType.DYNAMIC_TYPE_PGC_UNION) {
                    bangumi.contains(it.modules.moduleAuthor.mid)
                } else {
                    followingUsers.contains(it.modules.moduleAuthor.mid)
                }
            }
            .sortedByDescending { it.time }
            .take(1)

        if (dynamics.isEmpty()) {
            logger.info("$source no dynamic item detected")
            return 0
        }

        logger.info("$source detected ${dynamics.size} dynamic item(s)")
        dynamics.forEach {
            logger.info("dynamic ${it.modules.moduleAuthor.name} - ${it.modules.moduleDynamic.desc?.text?.take(50) ?: "<no-text>"}")
        }
        val details = dynamics.flatMap { item ->
            PushFanoutService.dynamicDetailsForContacts(
                item,
                PushFanoutService.resolveDynamicContacts(item, dynamic, bangumi)
            )
        }
        dynamicChannel.sendAll(details)
        return dynamics.size
    }

    private fun saveHistory() {
        try {
            historyFile.parentFile?.mkdirs()
            historyFile.writeText(historyDynamic.joinToString("\n"))
        } catch (e: Exception) {
            logger.error("failed to save dynamic history", e)
        }
    }
}
