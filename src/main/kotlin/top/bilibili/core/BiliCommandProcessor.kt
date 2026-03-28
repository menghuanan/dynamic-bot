package top.bilibili.core

/**
 * `/bili` 命令执行器的能力约定。
 */
interface BiliCommandExecutor {
    /** 处理添加订阅命令。 */
    suspend fun add()
    /** 处理移除订阅命令。 */
    suspend fun remove()
    /** 处理列表查询命令。 */
    suspend fun list()
    /** 处理颜色配置命令。 */
    suspend fun color()
    /** 处理群组列表命令。 */
    suspend fun groups()
    /** 处理单个群组命令。 */
    suspend fun group()
    /** 处理过滤器命令。 */
    suspend fun filter()
    /** 处理模板命令。 */
    suspend fun template()
    /** 处理全体提醒配置命令。 */
    suspend fun atall()
    /** 处理配置查看或修改命令。 */
    suspend fun config()
    /** 处理管理员命令。 */
    suspend fun admin()
    /** 处理黑名单命令。 */
    suspend fun blacklist()
    /** 处理帮助命令。 */
    suspend fun help()
    /** 处理未知子命令。 */
    suspend fun unknown(command: String)
}

/**
 * 负责解析 `/bili` 指令并分派到具体执行器。
 */
object BiliCommandProcessor {
    /**
     * 解析消息内容并调用对应的命令处理逻辑。
     */
    suspend fun process(message: String, executor: BiliCommandExecutor) {
        val args = message.substringAfter("/bili ").trim().split(Regex("\\s+"))
        if (args.isEmpty() || args.first().isBlank()) {
            executor.help()
            return
        }

        when (args[0].lowercase()) {
            "add" -> executor.add()
            "remove", "rm" -> executor.remove()
            "list", "ls" -> executor.list()
            "color" -> executor.color()
            "groups" -> executor.groups()
            "group" -> executor.group()
            "filter" -> executor.filter()
            "template", "tpl" -> executor.template()
            "atall", "aa" -> executor.atall()
            "config", "cfg" -> executor.config()
            "admin" -> executor.admin()
            "blacklist", "bl" -> executor.blacklist()
            "help" -> executor.help()
            else -> executor.unknown(args[0])
        }
    }
}
