package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.FilterMode
import top.bilibili.FilterType
import top.bilibili.connector.OutgoingPart

object FilterCommandService {
    suspend fun handle(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (!CommandPermission.isSuperAdmin(userId) && (!isGroup || !CommandPermission.isGroupAdmin(contactId, userId))) return

        if (args.size < 2) {
            send(
                contactId,
                isGroup,
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
            "add" -> add(contactId, args, isGroup)
            "list", "ls" -> list(contactId, args, isGroup)
            "del", "delete", "rm" -> del(contactId, args, isGroup)
            else -> send(contactId, isGroup, "未知的过滤器命令: ${args[1]}\n使用 /bili filter 查看帮助")
        }
    }

    private suspend fun add(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 6) {
            send(
                contactId,
                isGroup,
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
            send(contactId, isGroup, "UID 格式错误")
            return
        }

        val filterType = when (args[3].lowercase()) {
            "type" -> FilterType.TYPE
            "regex" -> FilterType.REGULAR
            else -> {
                send(contactId, isGroup, "过滤器类型错误，只能是 type 或 regex")
                return
            }
        }

        val mode = when (args[4].lowercase()) {
            "black", "blacklist", "黑名单" -> FilterMode.BLACK_LIST
            "white", "whitelist", "白名单" -> FilterMode.WHITE_LIST
            else -> {
                send(contactId, isGroup, "模式错误，只能是 black 或 white")
                return
            }
        }

        val content = args.drop(5).joinToString(" ")
        val subject = if (isGroup) "group:$contactId" else "private:$contactId"
        val result = FilterService.addFilter(filterType, mode, content, uid, subject)
        BiliConfigManager.saveData()
        send(contactId, isGroup, result)
    }

    private suspend fun list(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 3) {
            send(contactId, isGroup, "用法: /bili filter list <UID>")
            return
        }
        val uid = args[2].toLongOrNull()
        if (uid == null) {
            send(contactId, isGroup, "UID 格式错误")
            return
        }
        val subject = if (isGroup) "group:$contactId" else "private:$contactId"
        send(contactId, isGroup, FilterService.listFilter(uid, subject))
    }

    private suspend fun del(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 4) {
            send(contactId, isGroup, "用法: /bili filter del <UID> <索引>\n示例: /bili filter del 123456 t0")
            return
        }
        val uid = args[2].toLongOrNull()
        if (uid == null) {
            send(contactId, isGroup, "UID 格式错误")
            return
        }
        val index = args[3]
        val subject = if (isGroup) "group:$contactId" else "private:$contactId"
        val result = FilterService.delFilter(index, uid, subject)
        BiliConfigManager.saveData()
        send(contactId, isGroup, result)
    }

    private suspend fun send(contactId: Long, isGroup: Boolean, msg: String) {
        if (isGroup) MessageGatewayProvider.require().sendGroupMessage(contactId, listOf(OutgoingPart.text(msg)))
        else MessageGatewayProvider.require().sendPrivateMessage(contactId, listOf(OutgoingPart.text(msg)))
    }
}
