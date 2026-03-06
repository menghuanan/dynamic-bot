package top.bilibili.core

interface BiliCommandExecutor {
    suspend fun add()
    suspend fun remove()
    suspend fun list()
    suspend fun color()
    suspend fun groups()
    suspend fun group()
    suspend fun filter()
    suspend fun template()
    suspend fun atall()
    suspend fun config()
    suspend fun admin()
    suspend fun blacklist()
    suspend fun help()
    suspend fun unknown(command: String)
}

object BiliCommandProcessor {
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
