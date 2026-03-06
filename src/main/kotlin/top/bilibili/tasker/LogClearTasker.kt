package top.bilibili.tasker

import org.slf4j.LoggerFactory
import java.io.File

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

            if (file.delete()) {
                deleted++
            } else {
                logger.warn("删除 $tag 失败: ${file.path}")
            }
        }
        return deleted
    }
}
