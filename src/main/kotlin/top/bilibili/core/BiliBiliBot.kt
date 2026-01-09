package top.bilibili.core

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import top.bilibili.BiliConfigManager
import top.bilibili.config.ConfigManager
import top.bilibili.napcat.MessageEvent
import top.bilibili.napcat.MessageSegment
import top.bilibili.napcat.NapCatClient
import top.bilibili.data.BiliCookie
import top.bilibili.data.DynamicDetail
import top.bilibili.data.LiveDetail
import top.bilibili.data.BiliMessage
import top.bilibili.api.userInfo
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.Path

/**
 * BiliBili 动态推送 Bot 核心类
 * 替代原来的 BiliBiliBot 插件对象
 */
object BiliBiliBot : CoroutineScope {
    val logger = LoggerFactory.getLogger(BiliBiliBot::class.java)
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.Default + job

    /** 数据文件夹 */
    val dataFolder = File("data")
    val dataFolderPath: Path = Path("data")

    /** 临时文件目录 */
    val tempPath: Path = Path("temp").also { path ->
        if (!path.toFile().exists()) {
            path.toFile().mkdirs()
        }
    }

    /** B站 Cookie */
    var cookie = BiliCookie()

    /** NapCat WebSocket 客户端 */
    lateinit var napCat: NapCatClient
        private set

    /** Bot 配置 */
    lateinit var config: top.bilibili.config.BotConfig
        private set

    /** B站用户 UID */
    var uid: Long = 0L

    /** B站关注分组 ID */
    var tagid: Int = 0

    /** 命令行参数：Debug模式 */
    private var commandLineDebugMode: Boolean? = null

    /** 数据 Channel */
    val dynamicChannel = Channel<DynamicDetail>(20)
    val liveChannel = Channel<LiveDetail>(20)
    val messageChannel = Channel<BiliMessage>(20)
    val missChannel = Channel<BiliMessage>(10)
    val liveUsers = mutableMapOf<Long, Long>()

    /** 获取资源文件流 */
    fun getResourceAsStream(path: String): InputStream? {
        // 使用 classLoader 加载资源，确保从 classpath 根目录开始查找
        val resourcePath = if (path.startsWith("/")) path.substring(1) else path
        return this::class.java.classLoader.getResourceAsStream(resourcePath)
    }

    /** 启动 Bot */
    fun start(enableDebug: Boolean? = null) {
        // 保存命令行参数
        if (enableDebug != null) {
            commandLineDebugMode = enableDebug
        }

        // 先加载配置以确定日志级别
        try {
            BiliConfigManager.init()
            // 根据配置或命令行参数设置日志级别（命令行参数优先）
            val debugMode = commandLineDebugMode ?: BiliConfigManager.config.enableConfig.debugMode
            if (debugMode) {
                System.setProperty("APP_LOG_LEVEL", "DEBUG")
                ch.qos.logback.classic.LoggerContext::class.java.cast(
                    org.slf4j.LoggerFactory.getILoggerFactory()
                ).let { context ->
                    context.getLogger("top.bilibili").level = ch.qos.logback.classic.Level.DEBUG
                    context.getLogger("ROOT").level = ch.qos.logback.classic.Level.DEBUG
                }
                val source = if (commandLineDebugMode == true) "命令行参数" else "配置文件"
                logger.debug("Debug 模式已启用（来源：$source）")
            }
        } catch (e: Exception) {
            logger.error("加载配置失败: ${e.message}")
            return
        }

        logger.info("========================================")
        logger.info("  BiliBili 动态推送 Bot v1.3")
        logger.info("========================================")

        try {
            // 0. 创建必要的目录
            logger.info("正在初始化目录结构...")
            listOf("config", "data", "temp", "logs").forEach { dir ->
                File(dir).apply {
                    if (!exists()) {
                        mkdirs()
                        logger.debug("创建目录: $dir")
                    }
                }
            }

            // 1. 加载配置（已在前面加载）
            logger.info("正在加载配置...")
            ConfigManager.init()
            config = ConfigManager.botConfig

            // 清理所有缓存（启动时）
            if (BiliConfigManager.config.enableConfig.cacheClearEnable) {
                logger.info("正在清理所有缓存...")
                clearAllCache()
            }

            if (!config.napcat.validate()) {
                logger.error("NapCat 配置无效，请检查 config/bot.yml")
                return
            }

            // 2. 初始化 NapCat 客户端
            logger.info("正在初始化 NapCat 客户端...")
            napCat = NapCatClient(config.napcat)

            // 3. 订阅消息事件
            launch {
                napCat.eventFlow.collect { event ->
                    handleMessageEvent(event)
                }
            }

            // 4. 启动 NapCat 客户端
            napCat.start()

            // 5. 初始化 B站数据
            launch {
                delay(3000) // 等待 WebSocket 连接
                initBiliData()
            }

            // 6. 启动任务
            launch {
                delay(5000) // 等待初始化完成
                startTasks()
            }

            logger.info("Bot 启动成功！")

        } catch (e: Exception) {
            logger.error("Bot 启动失败: ${e.message}", e)
            stop()
        }
    }

    /** 停止 Bot */
    fun stop() {
        logger.info("正在停止 Bot...")

        try {
            // 停止 NapCat 客户端
            if (::napCat.isInitialized) {
                napCat.stop()
            }

            // 保存配置和数据
            if (::config.isInitialized) {
                BiliConfigManager.saveAll()
            }

            // 取消所有协程
            job.cancel()

            logger.info("Bot 已停止")
        } catch (e: Exception) {
            logger.error("停止 Bot 时发生错误: ${e.message}", e)
        }
    }

    /** 清理所有缓存 */
    private fun clearAllCache() {
        try {
            val cacheDir = java.io.File("data/cache")
            if (!cacheDir.exists()) {
                logger.info("缓存目录不存在，跳过清理")
                return
            }

            var totalDeleted = 0
            // 递归删除所有子文件夹中的文件
            cacheDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    try {
                        if (file.delete()) {
                            totalDeleted++
                        }
                    } catch (e: Exception) {
                        logger.error("删除缓存文件失败: ${file.path}, ${e.message}")
                    }
                }
            }

            logger.info("缓存清理完成，共删除 $totalDeleted 个文件")
        } catch (e: Exception) {
            logger.error("清理缓存时发生错误: ${e.message}", e)
        }
    }

    /** 处理 QQ 消息事件 */
    private suspend fun handleMessageEvent(event: MessageEvent) {
        try {
            when (event.messageType) {
                "group" -> handleGroupMessage(event)
                "private" -> handlePrivateMessage(event)
                else -> logger.debug("收到未知类型的消息: ${event.messageType}")
            }
        } catch (e: Exception) {
            logger.error("处理消息事件失败: ${e.message}", e)
        }
    }

    /** 简化消息内容用于日志显示 */
    private fun simplifyMessageForLog(rawMessage: String): String {
        // 检查是否包含 CQ 码
        if (!rawMessage.contains("[CQ:")) {
            // 纯文本消息，直接返回（限制长度）
            return if (rawMessage.length > 100) {
                rawMessage.take(100) + "..."
            } else {
                rawMessage
            }
        }

        // 包含 CQ 码，需要简化
        val result = StringBuilder()
        val cqPattern = """\[CQ:([^,\]]+).*?\]""".toRegex()

        var lastIndex = 0
        cqPattern.findAll(rawMessage).forEach { match ->
            // 添加 CQ 码之前的文本
            if (match.range.first > lastIndex) {
                result.append(rawMessage.substring(lastIndex, match.range.first))
            }

            // 简化 CQ 码
            val cqType = match.groupValues[1]
            when (cqType) {
                "image" -> result.append("[图片]")
                "face" -> result.append("[表情]")
                "at" -> result.append("[提及]")
                "reply" -> result.append("[回复]")
                "video" -> result.append("[视频]")
                "record" -> result.append("[语音]")
                "file" -> result.append("[文件]")
                "json" -> result.append("[JSON消息]")
                "xml" -> result.append("[XML消息]")
                else -> result.append("[$cqType]")
            }

            lastIndex = match.range.last + 1
        }

        // 添加最后的文本
        if (lastIndex < rawMessage.length) {
            result.append(rawMessage.substring(lastIndex))
        }

        // 限制总长度
        val simplified = result.toString()
        return if (simplified.length > 100) {
            simplified.take(100) + "..."
        } else {
            simplified
        }
    }

    /** 处理群消息 */
    private suspend fun handleGroupMessage(event: MessageEvent) {
        val groupId = event.groupId ?: return
        val userId = event.userId
        val message = event.rawMessage

        logger.info("群消息 [$groupId] 来自 $userId: ${simplifyMessageForLog(message)}")

        // 处理登录命令（仅管理员可用）
        if (isAdmin(userId) && (message.trim() == "/login" || message.trim() == "登录")) {
            logger.info("触发登录命令，准备发送二维码...")
            launch {
                top.bilibili.service.LoginService.login(isGroup = true, contactId = groupId)
            }
            return
        }

        // 处理订阅命令（仅管理员可用）
        if (isAdmin(userId) && message.trim().startsWith("/subscribe ")) {
            val uid = message.trim().removePrefix("/subscribe ").trim().toLongOrNull()
            if (uid != null) {
                handleSubscribe(groupId, uid, isGroup = true)
            } else {
                sendGroupMessage(groupId, listOf(MessageSegment.text("UID 格式错误")))
            }
            return
        }

        // 处理取消订阅命令（仅管理员可用）
        if (isAdmin(userId) && message.trim().startsWith("/unsubscribe ")) {
            val uid = message.trim().removePrefix("/unsubscribe ").trim().toLongOrNull()
            if (uid != null) {
                handleUnsubscribe(groupId, uid, isGroup = true)
            } else {
                sendGroupMessage(groupId, listOf(MessageSegment.text("UID 格式错误")))
            }
            return
        }

        // 处理订阅列表查询命令（仅管理员可用）
        if (isAdmin(userId) && message.trim() == "/list") {
            handleListSubscriptions(groupId, isGroup = true)
            return
        }

        // 处理手动触发检查命令（仅管理员可用，用于测试）
        if (isAdmin(userId) && message.trim() == "/check") {
            sendGroupMessage(groupId, listOf(MessageSegment.text("正在检查订阅...")))
            launch {
                try {
                    // 触发一次检查，忽略时间限制，获取最新动态
                    val result = top.bilibili.tasker.DynamicCheckTasker.executeManualCheck()
                    if (result > 0) {
                        sendGroupMessage(groupId, listOf(MessageSegment.text("检查完成！检测到 $result 条动态，正在处理...")))
                    } else {
                        sendGroupMessage(groupId, listOf(MessageSegment.text("检查完成！暂无新动态")))
                    }
                } catch (e: Exception) {
                    sendGroupMessage(groupId, listOf(MessageSegment.text("检查失败: ${e.message}")))
                    logger.error("手动检查失败", e)
                }
            }
            return
        }

        // 处理 /bili 命令系统（仅管理员可用）
        if (isAdmin(userId) && message.trim().startsWith("/bili ")) {
            handleBiliCommand(groupId, userId, message.trim(), isGroup = true)
            return
        }
    }

    /** 处理私聊消息 */
    private suspend fun handlePrivateMessage(event: MessageEvent) {
        val userId = event.userId
        val message = event.rawMessage

        logger.info("私聊消息 来自 $userId: ${simplifyMessageForLog(message)}")

        // 处理登录命令（仅管理员可用）
        if (isAdmin(userId) && (message.trim() == "/login" || message.trim() == "登录")) {
            logger.info("触发登录命令，准备发送二维码...")
            launch {
                top.bilibili.service.LoginService.login(isGroup = false, contactId = userId)
            }
            return
        }

        // TODO: 实现其他私聊命令处理
    }

    /** 检查是否为管理员 */
    private fun isAdmin(userId: Long): Boolean {
        return userId == BiliConfigManager.config.admin
    }

    /** 处理订阅 */
    private suspend fun handleSubscribe(contactId: Long, uid: Long, isGroup: Boolean) {
        try {
            val contactStr = if (isGroup) "group:$contactId" else "private:$contactId"

            // 获取用户信息
            val userInfo = top.bilibili.utils.biliClient.userInfo(uid)
            if (userInfo == null) {
                val msg = "无法获取用户信息，UID 可能不存在: $uid"
                if (isGroup) {
                    sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
                } else {
                    sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
                }
                return
            }

            // 添加订阅
            val subData = top.bilibili.BiliData.dynamic.getOrPut(uid) {
                top.bilibili.SubData(
                    name = userInfo.name ?: "未知用户",
                    contacts = mutableSetOf(),
                    banList = mutableMapOf()
                )
            }

            if (contactStr in subData.contacts) {
                val userName = userInfo.name ?: "未知用户"
                val msg = "已经订阅过 $userName (UID: $uid) 了"
                if (isGroup) {
                    sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
                } else {
                    sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
                }
                return
            }

            subData.contacts.add(contactStr)
            top.bilibili.BiliConfigManager.saveData()

            val userName = userInfo.name ?: "未知用户"
            val msg = "订阅成功！\n用户: $userName\nUID: $uid"
            if (isGroup) {
                sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            } else {
                sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            }

        } catch (e: Exception) {
            logger.error("处理订阅失败: ${e.message}", e)
            val msg = "订阅失败: ${e.message}"
            if (isGroup) {
                sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            } else {
                sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            }
        }
    }

    /** 处理取消订阅 */
    private suspend fun handleUnsubscribe(contactId: Long, uid: Long, isGroup: Boolean) {
        try {
            val contactStr = if (isGroup) "group:$contactId" else "private:$contactId"

            val subData = top.bilibili.BiliData.dynamic[uid]
            if (subData == null || contactStr !in subData.contacts) {
                val msg = "没有订阅过 UID: $uid"
                if (isGroup) {
                    sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
                } else {
                    sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
                }
                return
            }

            subData.contacts.remove(contactStr)
            top.bilibili.BiliConfigManager.saveData()

            val msg = "取消订阅成功！UID: $uid"
            if (isGroup) {
                sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            } else {
                sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            }

        } catch (e: Exception) {
            logger.error("处理取消订阅失败: ${e.message}", e)
            val msg = "取消订阅失败: ${e.message}"
            if (isGroup) {
                sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            } else {
                sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            }
        }
    }

    /** 处理查询订阅列表 */
    private suspend fun handleListSubscriptions(contactId: Long, isGroup: Boolean) {
        try {
            val contactStr = if (isGroup) "group:$contactId" else "private:$contactId"

            val subscriptions = top.bilibili.BiliData.dynamic
                .filter { contactStr in it.value.contacts }
                .map { "${it.value.name} (UID: ${it.key})" }

            val msg = if (subscriptions.isEmpty()) {
                "当前没有任何订阅"
            } else {
                "订阅列表:\n${subscriptions.joinToString("\n")}"
            }

            if (isGroup) {
                sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            } else {
                sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            }

        } catch (e: Exception) {
            logger.error("查询订阅列表失败: ${e.message}", e)
        }
    }

    /** 处理 /bili 命令 */
    private suspend fun handleBiliCommand(contactId: Long, userId: Long, message: String, isGroup: Boolean) {
        try {
            val args = message.substringAfter("/bili ").trim().split(Regex("\\s+"))
            if (args.isEmpty()) {
                sendHelpMessage(contactId, isGroup)
                return
            }

            when (args[0].lowercase()) {
                "add" -> handleBiliAdd(contactId, args, isGroup)
                "remove", "rm" -> handleBiliRemove(contactId, args, isGroup)
                "list", "ls" -> handleBiliList(contactId, args, isGroup)
                "groups" -> handleBiliListGroups(contactId, args, isGroup)
                "group" -> handleBiliGroupCommand(contactId, userId, args, isGroup)
                "filter" -> handleBiliFilterCommand(contactId, args, isGroup)
                "help" -> sendHelpMessage(contactId, isGroup)
                else -> {
                    val msg = "未知命令: ${args[0]}\n使用 /bili help 查看帮助"
                    if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
                    else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
                }
            }
        } catch (e: Exception) {
            logger.error("处理 /bili 命令失败: ${e.message}", e)
            val msg = "命令执行失败: ${e.message}"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
        }
    }

    /** 发送帮助信息 */
    private suspend fun sendHelpMessage(contactId: Long, isGroup: Boolean) {
        val msg = """
            /bili 命令帮助:

            订阅管理:
            /bili add <UID> <群号> - 添加订阅到指定群
            /bili remove <UID> <群号> - 从指定群移除订阅
            /bili list - 查看当前群的订阅
            /bili list <UID> - 查看UID推送到哪些群

            分组管理:
            /bili group create <分组名> - 创建分组
            /bili group delete <分组名> - 删除分组
            /bili group add <分组名> <群号> - 将群加入分组
            /bili group remove <分组名> <群号> - 从分组移除群
            /bili group list [分组名] - 查看分组信息
            /bili group subscribe <分组名> <UID> - 订阅到分组
            /bili groups - 查看所有分组

            过滤器管理（支持黑名单与白名单）:
            /bili filter add <UID> <type|regex> <模式> <内容> - 添加过滤器
              type模式: /bili filter add <UID> type <black|white> <动态|转发动态|视频|音乐|专栏|直播>
              regex模式: /bili filter add <UID> regex <black|white> <正则表达式>
            /bili filter list <UID> - 查看过滤器
            /bili filter del <UID> <索引> - 删除过滤器(如 t0, r1)

            其他:
            /bili help - 显示此帮助
        """.trimIndent()

        if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
        else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
    }

    /** 处理 /bili add */
    private suspend fun handleBiliAdd(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 3) {
            val msg = "用法: /bili add <UID> <群号>\n示例: /bili add 123456 987654321"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val uid = args[1].toLongOrNull()
        val targetGroupId = args[2].toLongOrNull()

        if (uid == null || targetGroupId == null) {
            val msg = "UID 或群号格式错误"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        // 使用 DynamicService 添加订阅（会自动处理关注逻辑）
        val targetContactStr = "group:$targetGroupId"
        val result = top.bilibili.service.DynamicService.addSubscribe(uid, targetContactStr, isSelf = false)

        top.bilibili.BiliConfigManager.saveData()

        if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(result)))
        else sendPrivateMessage(contactId, listOf(MessageSegment.text(result)))
    }

    /** 处理 /bili remove */
    private suspend fun handleBiliRemove(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 3) {
            val msg = "用法: /bili remove <UID> <群号>\n示例: /bili remove 123456 987654321"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val uid = args[1].toLongOrNull()
        val targetGroupId = args[2].toLongOrNull()

        if (uid == null || targetGroupId == null) {
            val msg = "UID 或群号格式错误"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        // 使用 DynamicService 移除订阅（会自动处理取消关注逻辑）
        val targetContactStr = "group:$targetGroupId"
        val result = top.bilibili.service.DynamicService.removeSubscribe(uid, targetContactStr, isSelf = false)

        top.bilibili.BiliConfigManager.saveData()

        if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(result)))
        else sendPrivateMessage(contactId, listOf(MessageSegment.text(result)))
    }

    /** 处理 /bili list */
    private suspend fun handleBiliList(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size == 1) {
            // 查看当前群的订阅
            val contactStr = "group:$contactId"
            val subscriptions = top.bilibili.BiliData.dynamic
                .filter { contactStr in it.value.contacts }
                .map { "${it.value.name} (UID: ${it.key})" }

            val msg = if (subscriptions.isEmpty()) {
                "当前群没有任何订阅"
            } else {
                "当前群订阅列表:\n${subscriptions.joinToString("\n")}"
            }

            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
        } else {
            // 查看指定 UID 推送到哪些群
            val uid = args[1].toLongOrNull()
            if (uid == null) {
                val msg = "UID 格式错误"
                if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
                else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
                return
            }

            val subData = top.bilibili.BiliData.dynamic[uid]
            if (subData == null) {
                val msg = "没有订阅过 UID: $uid"
                if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
                else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
                return
            }

            val groups = subData.contacts
                .filter { it.startsWith("group:") }
                .map { it.removePrefix("group:") }

            val msg = if (groups.isEmpty()) {
                "${subData.name} (UID: $uid)\n没有推送到任何群"
            } else {
                "${subData.name} (UID: $uid)\n推送到以下群:\n${groups.joinToString("\n")}"
            }

            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
        }
    }

    /** 处理 /bili groups */
    private suspend fun handleBiliListGroups(contactId: Long, args: List<String>, isGroup: Boolean) {
        val groups = top.bilibili.BiliData.group

        val msg = if (groups.isEmpty()) {
            "当前没有任何分组"
        } else {
            val groupList = groups.map { (name, group) ->
                "$name (${group.contacts.size} 个群)"
            }.joinToString("\n")
            "分组列表:\n$groupList\n\n使用 /bili group list <分组名> 查看详情"
        }

        if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
        else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
    }

    /** 处理 /bili group 命令 */
    private suspend fun handleBiliGroupCommand(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 2) {
            val msg = "用法: /bili group <create|delete|add|remove|list|subscribe>"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        when (args[1].lowercase()) {
            "create" -> handleBiliGroupCreate(contactId, userId, args, isGroup)
            "delete", "del" -> handleBiliGroupDelete(contactId, args, isGroup)
            "add" -> handleBiliGroupAdd(contactId, args, isGroup)
            "remove", "rm" -> handleBiliGroupRemove(contactId, args, isGroup)
            "list", "ls" -> handleBiliGroupList(contactId, args, isGroup)
            "subscribe", "sub" -> handleBiliGroupSubscribe(contactId, args, isGroup)
            else -> {
                val msg = "未知子命令: ${args[1]}"
                if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
                else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            }
        }
    }

    /** 创建分组 */
    private suspend fun handleBiliGroupCreate(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 3) {
            val msg = "用法: /bili group create <分组名>"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val groupName = args[2]

        if (groupName in top.bilibili.BiliData.group) {
            val msg = "分组 $groupName 已存在"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        top.bilibili.BiliData.group[groupName] = top.bilibili.Group(
            name = groupName,
            creator = userId,
            admin = mutableSetOf(userId),
            contacts = mutableSetOf()
        )
        top.bilibili.BiliConfigManager.saveData()

        val msg = "成功创建分组: $groupName"
        if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
        else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
    }

    /** 删除分组 */
    private suspend fun handleBiliGroupDelete(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 3) {
            val msg = "用法: /bili group delete <分组名>"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val groupName = args[2]

        if (groupName !in top.bilibili.BiliData.group) {
            val msg = "分组 $groupName 不存在"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        top.bilibili.BiliData.group.remove(groupName)
        top.bilibili.BiliConfigManager.saveData()

        val msg = "成功删除分组: $groupName"
        if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
        else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
    }

    /** 将群加入分组 */
    private suspend fun handleBiliGroupAdd(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 4) {
            val msg = "用法: /bili group add <分组名> <群号>"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val groupName = args[2]
        val targetGroupId = args[3].toLongOrNull()

        if (targetGroupId == null) {
            val msg = "群号格式错误"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val group = top.bilibili.BiliData.group[groupName]
        if (group == null) {
            val msg = "分组 $groupName 不存在"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val contactStr = "group:$targetGroupId"
        if (contactStr in group.contacts) {
            val msg = "群 $targetGroupId 已在分组 $groupName 中"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        group.contacts.add(contactStr)
        top.bilibili.BiliConfigManager.saveData()

        val msg = "成功将群 $targetGroupId 加入分组 $groupName"
        if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
        else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
    }

    /** 从分组移除群 */
    private suspend fun handleBiliGroupRemove(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 4) {
            val msg = "用法: /bili group remove <分组名> <群号>"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val groupName = args[2]
        val targetGroupId = args[3].toLongOrNull()

        if (targetGroupId == null) {
            val msg = "群号格式错误"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val group = top.bilibili.BiliData.group[groupName]
        if (group == null) {
            val msg = "分组 $groupName 不存在"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val contactStr = "group:$targetGroupId"
        if (contactStr !in group.contacts) {
            val msg = "群 $targetGroupId 不在分组 $groupName 中"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        group.contacts.remove(contactStr)
        top.bilibili.BiliConfigManager.saveData()

        val msg = "成功从分组 $groupName 移除群 $targetGroupId"
        if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
        else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
    }

    /** 查看分组信息 */
    private suspend fun handleBiliGroupList(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 3) {
            // 查看所有分组
            handleBiliListGroups(contactId, args, isGroup)
            return
        }

        val groupName = args[2]
        val group = top.bilibili.BiliData.group[groupName]

        if (group == null) {
            val msg = "分组 $groupName 不存在"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val groups = group.contacts
            .filter { it.startsWith("group:") }
            .map { it.removePrefix("group:") }

        val msg = if (groups.isEmpty()) {
            "分组: $groupName\n创建者: ${group.creator}\n当前没有任何群"
        } else {
            "分组: $groupName\n创建者: ${group.creator}\n包含群:\n${groups.joinToString("\n")}"
        }

        if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
        else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
    }

    /** 订阅到分组 */
    private suspend fun handleBiliGroupSubscribe(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 4) {
            val msg = "用法: /bili group subscribe <分组名> <UID>"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val groupName = args[2]
        val uid = args[3].toLongOrNull()

        if (uid == null) {
            val msg = "UID 格式错误"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val group = top.bilibili.BiliData.group[groupName]
        if (group == null) {
            val msg = "分组 $groupName 不存在"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        // 为分组中的所有联系人添加订阅
        // 使用 DynamicService 添加第一个订阅（会自动处理关注逻辑）
        if (group.contacts.isEmpty()) {
            val msg = "分组 $groupName 中没有任何联系人"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        var addedCount = 0
        var firstError: String? = null

        for (contact in group.contacts) {
            try {
                val result = top.bilibili.service.DynamicService.addSubscribe(uid, contact, isSelf = false)
                // 如果包含"成功"或者不包含错误提示，则认为添加成功
                if (!result.contains("订阅过") && (result.contains("成功") || !result.contains("失败") && !result.contains("错误"))) {
                    addedCount++
                } else if (firstError == null && result.contains("失败")) {
                    firstError = result
                }
            } catch (e: Exception) {
                if (firstError == null) {
                    firstError = e.message
                }
            }
        }

        top.bilibili.BiliConfigManager.saveData()

        val msg = if (firstError != null && addedCount == 0) {
            "订阅失败：$firstError"
        } else {
            buildString {
                appendLine("订阅操作完成！")
                appendLine("UID: $uid")
                appendLine("分组: $groupName")
                appendLine("成功添加: $addedCount 个联系人")
                if (firstError != null) {
                    appendLine("部分失败: $firstError")
                }
            }.trim()
        }

        if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
        else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
    }

    /** 处理 /bili filter 命令 */
    private suspend fun handleBiliFilterCommand(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 2) {
            val msg = """
                用法:
                /bili filter add <UID> <type|regex> <black|white> <内容>
                /bili filter list <UID>
                /bili filter del <UID> <索引>
            """.trimIndent()
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        when (args[1].lowercase()) {
            "add" -> handleBiliFilterAdd(contactId, args, isGroup)
            "list", "ls" -> handleBiliFilterList(contactId, args, isGroup)
            "del", "delete", "rm" -> handleBiliFilterDel(contactId, args, isGroup)
            else -> {
                val msg = "未知的过滤器命令: ${args[1]}\n使用 /bili filter 查看帮助"
                if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
                else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            }
        }
    }

    /** 处理 /bili filter add */
    private suspend fun handleBiliFilterAdd(contactId: Long, args: List<String>, isGroup: Boolean) {
        // /bili filter add <UID> <type|regex> <black|white> <内容>
        if (args.size < 6) {
            val msg = """
                用法:
                /bili filter add <UID> type <black|white> <动态|转发动态|视频|音乐|专栏|直播>
                /bili filter add <UID> regex <black|white> <正则表达式>
            """.trimIndent()
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val uid = args[2].toLongOrNull()
        if (uid == null) {
            val msg = "UID 格式错误"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val filterTypeStr = args[3].lowercase()
        val filterType = when (filterTypeStr) {
            "type" -> top.bilibili.FilterType.TYPE
            "regex" -> top.bilibili.FilterType.REGULAR
            else -> {
                val msg = "过滤器类型错误，只能是 type 或 regex"
                if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
                else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
                return
            }
        }

        val modeStr = args[4].lowercase()
        val mode = when (modeStr) {
            "black", "blacklist", "黑名单" -> top.bilibili.FilterMode.BLACK_LIST
            "white", "whitelist", "白名单" -> top.bilibili.FilterMode.WHITE_LIST
            else -> {
                val msg = "模式错误，只能是 black 或 white"
                if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
                else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
                return
            }
        }

        val content = args.drop(5).joinToString(" ")
        val subject = if (isGroup) "group:$contactId" else "private:$contactId"

        val result = top.bilibili.service.FilterService.addFilter(filterType, mode, content, uid, subject)
        top.bilibili.BiliConfigManager.saveData()

        if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(result)))
        else sendPrivateMessage(contactId, listOf(MessageSegment.text(result)))
    }

    /** 处理 /bili filter list */
    private suspend fun handleBiliFilterList(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 3) {
            val msg = "用法: /bili filter list <UID>"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val uid = args[2].toLongOrNull()
        if (uid == null) {
            val msg = "UID 格式错误"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val subject = if (isGroup) "group:$contactId" else "private:$contactId"
        val result = top.bilibili.service.FilterService.listFilter(uid, subject)

        if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(result)))
        else sendPrivateMessage(contactId, listOf(MessageSegment.text(result)))
    }

    /** 处理 /bili filter del */
    private suspend fun handleBiliFilterDel(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 4) {
            val msg = "用法: /bili filter del <UID> <索引>\n示例: /bili filter del 123456 t0"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val uid = args[2].toLongOrNull()
        if (uid == null) {
            val msg = "UID 格式错误"
            if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
            return
        }

        val index = args[3]
        val subject = if (isGroup) "group:$contactId" else "private:$contactId"

        val result = top.bilibili.service.FilterService.delFilter(index, uid, subject)
        top.bilibili.BiliConfigManager.saveData()

        if (isGroup) sendGroupMessage(contactId, listOf(MessageSegment.text(result)))
        else sendPrivateMessage(contactId, listOf(MessageSegment.text(result)))
    }

    /** 初始化 B站数据 */
    private suspend fun initBiliData() {
        try {
            logger.info("正在初始化 B站数据...")
            // 调用 Init.kt 中的初始化函数
            top.bilibili.initData()

            // 启动时清理过期的图片缓存
            logger.info("清理过期的图片缓存...")
            try {
                top.bilibili.utils.ImageCache.cleanExpiredCache()
            } catch (e: Exception) {
                logger.warn("清理图片缓存时出错: ${e.message}")
            }

            logger.info("B站数据初始化完成")
        } catch (e: Exception) {
            logger.error("初始化 B站数据失败: ${e.message}", e)
        }
    }

    /** 启动所有任务 */
    private fun startTasks() {
        try {
            logger.info("正在启动任务...")

            // 启动链接解析任务
            logger.info("启动 ListenerTasker...")
            top.bilibili.tasker.ListenerTasker.start()

            // 启动订阅相关任务
            logger.info("启动 DynamicCheckTasker...")
            top.bilibili.tasker.DynamicCheckTasker.start()

            logger.info("启动 LiveCheckTasker...")
            top.bilibili.tasker.LiveCheckTasker.start()

            logger.info("启动 LiveCloseCheckTasker...")
            top.bilibili.tasker.LiveCloseCheckTasker.start()

            logger.info("启动 DynamicMessageTasker...")
            top.bilibili.tasker.DynamicMessageTasker.start()

            logger.info("启动 LiveMessageTasker...")
            top.bilibili.tasker.LiveMessageTasker.start()

            logger.info("启动 SendTasker...")
            top.bilibili.tasker.SendTasker.start()

            logger.info("启动 CacheClearTasker...")
            top.bilibili.tasker.CacheClearTasker.start()

            logger.info("所有任务已启动")
        } catch (e: Exception) {
            logger.error("启动任务失败: ${e.message}", e)
        }
    }

    /** 发送群消息 */
    suspend fun sendGroupMessage(groupId: Long, message: List<MessageSegment>): Boolean {
        return napCat.sendGroupMessage(groupId, message)
    }

    /** 发送私聊消息 */
    suspend fun sendPrivateMessage(userId: Long, message: List<MessageSegment>): Boolean {
        return napCat.sendPrivateMessage(userId, message)
    }

    /** 发送消息到指定联系人 */
    suspend fun sendMessage(contact: ContactId, message: List<MessageSegment>): Boolean {
        return when (contact.type) {
            "group" -> sendGroupMessage(contact.id, message)
            "private" -> sendPrivateMessage(contact.id, message)
            else -> {
                logger.warn("未知的联系人类型: ${contact.type}")
                false
            }
        }
    }
}
