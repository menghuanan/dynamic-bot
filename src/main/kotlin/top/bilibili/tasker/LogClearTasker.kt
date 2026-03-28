package top.bilibili.tasker

import org.slf4j.LoggerFactory
import java.io.File

/**
 * 定时清理历史日志文件。
 */
object LogClearTasker : BiliTasker() {
    private val logger = LoggerFactory.getLogger(LogClearTasker::class.java)
    override var interval: Int = 60 * 60 * 24 * 7
    private val normalLogPattern = Regex("^bilibili-bot\\.\\d{4}-\\d{2}-\\d{2}\\.log$")
    private val daemonLogPattern = Regex("^Daemon_\\d{4}-\\d{2}-\\d{2}\\.log$")

    override suspend fun main() {
        val now = System.currentTimeMillis()
        val expireMillis = 7L * 24 * 60 * 60 * 1000
        val normalDeleted = cleanDirectory(File("logs"), normalLogPattern, now, expireMillis, "普通日志")
        val daemonDeleted = cleanDirectory(File("logs/daemon"), daemonLogPattern, now, expireMillis, "守护日志")
        logger.info("日志清理完成，删除 ${normalDeleted + daemonDeleted} 个过期日志文件（普通: $normalDeleted, 守护: $daemonDeleted）")
    }

    /**
     * 清理目录中匹配模式且已过期的日志文件。
     *
     * @param directory 待清理目录
     * @param pattern 文件名匹配规则
     * @param now 当前时间戳
     * @param expireMillis 过期时长
     * @param tag 日志类型标识
     */
    private fun cleanDirectory(
        directory: File,
        pattern: Regex,
        now: Long,
        expireMillis: Long,
        tag: String,
    ): Int {
        if (!directory.exists() || !directory.isDirectory) {
            logger.debug("$tag 目录不存在，跳过: ${directory.path}")
            return 0
        }

        var deleted = 0
        directory.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (!pattern.matches(file.name)) return@forEach

            val age = now - file.lastModified()
            if (age < expireMillis) return@forEach

            // 仅删除符合命名规则的历史文件，避免误删运行中或人工放置的其他日志。
            if (file.delete()) {
                deleted++
            } else {
                logger.warn("删除 $tag 失败: ${file.path}")
            }
        }
        return deleted
    }
}
