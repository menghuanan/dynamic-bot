package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.connector.OutgoingPart
import top.bilibili.core.BiliBiliBot

object PresentationCommandService {
    suspend fun sendHelp(contactId: Long, userId: Long, isGroup: Boolean) {
        if (!CommandPermission.isSuperAdmin(userId) && (!isGroup || !CommandPermission.isGroupAdmin(contactId, userId))) return

        val isSuper = CommandPermission.isSuperAdmin(userId)
        val msg = if (isSuper) {
            """
            订阅管理:
            /bili add <UID|ss|md|ep> [群号] - 添加订阅
            /bili remove <UID|ss|md|ep> [群号] - 移除订阅
            /bili list - 查看当前群的订阅
            /bili list <UID|ss|md|ep> - 查看订阅推送到哪些群
            分组管理:
            /bili group create <分组名> - 创建分组
            /bili group delete <分组名> - 删除分组
            /bili group add <分组名> <群号> - 将群加入分组
            /bili group remove <分组名> <群号> - 从分组移除群
            /bili group list [分组名] - 查看分组信息
            /bili group subscribe <分组名> <UID|ss|md|ep> - 订阅到分组
            /bili group unsubscribe <分组名> <UID|ss|md|ep> - 从分组移除订阅
            /bili groups - 查看所有分组
            过滤器管理:
            /bili filter add <UID> <type|regex> <模式> <内容> - 添加过滤器
              type模式: /bili filter add <UID> type <black|white> <动态|转发动态|视频|音乐|专栏|直播>
              regex模式: /bili filter add <UID> regex <black|white> <正则表达式>
            /bili filter list <UID> - 查看过滤器
            /bili filter del <UID> <索引> - 删除过滤器（如 t0, r1）
            管理员管理:
            /bili admin add <QQ号> - 添加本群普通管理员
            /bili admin remove <QQ号> - 移除本群普通管理员
            /bili admin list - 查询本群管理员
            /bili admin all - 查询全部普通管理员

            其他:
            /bili help - 显示此帮助
            /bili color <uid|用户名> <HEX颜色> - 设置订阅主题色（仅超管）
            /bili config color <uid|用户名> <HEX颜色> - 通过 config 入口设置主题色（仅超管）
            /login 或 登录 - 扫码登录
            /check - 手动触发检查
            /add <UID> - 快速订阅
            /del <UID> - 快速取消订阅
            /list - 查看订阅列表
            /black [QQ号] - 添加黑名单
            /unblock [QQ号] - 取消黑名单
            /black list - 查看黑名单
            """.trimIndent()
        } else {
            """
            订阅管理:
            /bili add <UID|ss|md|ep> - 添加订阅
            /bili remove <UID|ss|md|ep> - 移除订阅
            /bili list - 查看当前群的订阅

            过滤器管理:
            /bili filter add <UID> <type|regex> <模式> <内容> - 添加过滤器
              type模式: /bili filter add <UID> type <black|white> <动态|转发动态|视频|音乐|专栏|直播>
              regex模式: /bili filter add <UID> regex <black|white> <正则表达式>
            /bili filter list <UID> - 查看过滤器
            /bili filter del <UID> <索引> - 删除过滤器（如 t0, r1）
            其他:
            /bili help - 显示此帮助
            """.trimIndent()
        }

        val imageName = if (isSuper) "admin_help.png" else "HELP.png"
        val imageSent = runCatching {
            val imagePath = getHelpImagePath(imageName)
            if (imagePath != null) {
                val imageSegments = listOf(OutgoingPart.image(imagePath))
                if (isGroup) MessageGatewayProvider.require().sendGroupMessage(contactId, imageSegments)
                else MessageGatewayProvider.require().sendPrivateMessage(contactId, imageSegments)
            } else {
                false
            }
        }.getOrDefault(false)

        if (!imageSent) {
            send(contactId, isGroup, msg)
        }
    }

    suspend fun handleTemplate(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (!CommandPermission.isSuperAdmin(userId) && (!isGroup || !CommandPermission.isGroupAdmin(contactId, userId))) return
        if (args.size < 2) {
            send(
                contactId,
                isGroup,
                """
                用法:
                /bili template list <d|l|le>
                /bili template preview <d|l|le> <模板名>
                /bili template set <d|l|le> <模板名> [uid]
                /bili template explain <d|l|le>
                """.trimIndent(),
            )
            return
        }

        val subject = if (isGroup) "group:$contactId" else "private:$contactId"
        when (args[1].lowercase()) {
            "list", "ls" -> {
                val type = args.getOrNull(2) ?: "d"
                send(contactId, isGroup, TemplateService.listTemplateText(type))
            }

            "preview", "pv" -> {
                if (args.size < 4) {
                    send(contactId, isGroup, "用法: /bili template preview <d|l|le> <模板名>")
                    return
                }
                send(contactId, isGroup, TemplateService.previewTemplate(args[2], args[3], subject))
            }

            "set" -> {
                if (args.size < 4) {
                    send(contactId, isGroup, "用法: /bili template set <d|l|le> <模板名> [uid]")
                    return
                }
                val uid = args.getOrNull(4)?.trim()?.let {
                    val parsed = it.toLongOrNull()
                    if (parsed == null || parsed <= 0L) {
                        send(contactId, isGroup, "UID 格式错误，请输入纯数字")
                        return
                    }
                    parsed
                }
                val result = TemplateService.setTemplate(args[2], args[3], subject, uid)
                BiliConfigManager.saveData()
                send(contactId, isGroup, result)
            }

            "explain", "exp" -> {
                val type = args.getOrNull(2) ?: "d"
                send(contactId, isGroup, TemplateService.explainTemplate(type))
            }

            else -> send(contactId, isGroup, "未知子命令: ${args[1]}")
        }
    }

    private fun getHelpImagePath(imageName: String): String? {
        val tempFile = BiliBiliBot.tempPath.resolve(imageName).toFile()
        if (!tempFile.exists()) {
            val bytes = BiliBiliBot.getResourceBytes("image/$imageName") ?: return null
            tempFile.parentFile?.mkdirs()
            runCatching { tempFile.writeBytes(bytes) }.getOrElse {
                BiliBiliBot.logger.warn("写入帮助图片失败: ${it.message}")
                return null
            }
        }
        return tempFile.absolutePath
    }

    private suspend fun send(contactId: Long, isGroup: Boolean, msg: String) {
        if (isGroup) MessageGatewayProvider.require().sendGroupMessage(contactId, listOf(OutgoingPart.text(msg)))
        else MessageGatewayProvider.require().sendPrivateMessage(contactId, listOf(OutgoingPart.text(msg)))
    }
}
