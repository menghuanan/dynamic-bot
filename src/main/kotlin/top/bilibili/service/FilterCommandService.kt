package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.FilterMode
import top.bilibili.FilterType
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.utils.toSubject

/**
 * 收口过滤器命令入口，避免权限判断和参数校验在多个调用点重复实现。
 */
object FilterCommandService {
    /**
     * 统一处理过滤器命令分支，让命令路由只负责把请求转进来。
     */
    suspend fun handle(chatContact: PlatformContact, senderContact: PlatformContact, args: List<String>) {
        val isGroup = chatContact.type == PlatformChatType.GROUP
        if (!CommandPermission.isSuperAdmin(senderContact) && (!isGroup || !CommandPermission.isGroupAdmin(chatContact, senderContact))) return

        if (args.size < 2) {
            sendText(
                chatContact,
                """
                用法:
                /bili filter add <UID> <type|regex> <black|white> <内容>
                /bili filter list <UID>
                /bili filter del <UID> <索引>
                """.trimIndent()
            )
            return
        }

        when (args[1].lowercase()) {
            "add" -> add(chatContact, args)
            "list", "ls" -> list(chatContact, args)
            "del", "delete", "rm" -> del(chatContact, args)
            else -> sendText(chatContact, "未知的过滤器命令: ${args[1]}\n使用 /bili filter 查看帮助")
        }
    }

    private suspend fun add(chatContact: PlatformContact, args: List<String>) {
        if (args.size < 6) {
            sendText(
                chatContact,
                """
                用法:
                /bili filter add <UID> type <black|white> <动态|转发动态|视频|音乐|专栏|直播>
                /bili filter add <UID> regex <black|white> <正则表达式>
                """.trimIndent()
            )
            return
        }

        val uid = args[2].toLongOrNull()
        if (uid == null) {
            sendText(chatContact, "UID 格式错误")
            return
        }

        val filterType = when (args[3].lowercase()) {
            "type" -> FilterType.TYPE
            "regex" -> FilterType.REGULAR
            else -> {
                sendText(chatContact, "过滤器类型错误，只能是 type 或 regex")
                return
            }
        }

        val mode = when (args[4].lowercase()) {
            "black", "blacklist", "黑名单" -> FilterMode.BLACK_LIST
            "white", "whitelist", "白名单" -> FilterMode.WHITE_LIST
            else -> {
                sendText(chatContact, "模式错误，只能是 black 或 white")
                return
            }
        }

        val content = args.drop(5).joinToString(" ")
        val subject = chatContact.toSubject()
        val result = FilterService.addFilter(filterType, mode, content, uid, subject)
        BiliConfigManager.saveData()
        sendText(chatContact, result)
    }

    private suspend fun list(chatContact: PlatformContact, args: List<String>) {
        if (args.size < 3) {
            sendText(chatContact, "用法: /bili filter list <UID>")
            return
        }
        val uid = args[2].toLongOrNull()
        if (uid == null) {
            sendText(chatContact, "UID 格式错误")
            return
        }
        val subject = chatContact.toSubject()
        sendText(chatContact, FilterService.listFilter(uid, subject))
    }

    private suspend fun del(chatContact: PlatformContact, args: List<String>) {
        if (args.size < 4) {
            sendText(chatContact, "用法: /bili filter del <UID> <索引>\n示例: /bili filter del 123456 t0")
            return
        }
        val uid = args[2].toLongOrNull()
        if (uid == null) {
            sendText(chatContact, "UID 格式错误")
            return
        }
        val index = args[3]
        val subject = chatContact.toSubject()
        val result = FilterService.delFilter(index, uid, subject)
        BiliConfigManager.saveData()
        sendText(chatContact, result)
    }
}
