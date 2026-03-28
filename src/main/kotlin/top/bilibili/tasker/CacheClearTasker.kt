package top.bilibili.tasker

import org.slf4j.LoggerFactory
import top.bilibili.BiliConfigManager
import top.bilibili.core.BiliBiliBot
import top.bilibili.utils.ImageCache
import top.bilibili.utils.actionNotify
import top.bilibili.utils.cachePath
import java.nio.file.Path
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.isDirectory

/**
 * 定时清理配置缓存与图片缓存。
 */
object CacheClearTasker : BiliTasker() {
    private val logger = LoggerFactory.getLogger(CacheClearTasker::class.java)
    override var interval: Int = 60 * 60 * 24

    private val expires by BiliConfigManager.config.cacheConfig::expires

    // ✅ P3修复: 连续失败计数器和告警阈值
    private var consecutiveFailures = 0
    private const val FAILURE_THRESHOLD = 3  // 连续失败3次才告警

    override suspend fun main() {
        logger.info("开始执行定时缓存清理任务...")

        var hasError = false
        var errorMessage: String? = null

        // 清理配置的缓存目录（data/cache/*）
        var totalCleared = 0
        for (e in expires) {
            if (e.value > 0) {
                try {
                    val cleared = e.key.cachePath().clearExpireFile(e.value)
                    if (cleared > 0) {
                        logger.info("清理 ${e.key} 缓存: $cleared 个文件")
                        totalCleared += cleared
                    }
                } catch (ex: Exception) {
                    hasError = true
                    errorMessage = ex.message
                    logger.error("清理 ${e.key} 缓存时出错: ${ex.message}", ex)
                }
            }
        }

        // 清理图片缓存（data/image_cache/*）
        logger.info("清理图片缓存 (image_cache)...")
        try {
            ImageCache.cleanExpiredCache()
        } catch (e: Exception) {
            hasError = true
            errorMessage = e.message
            logger.error("清理图片缓存时出错: ${e.message}", e)
        }

        // ✅ P3修复: 失败告警机制
        if (hasError) {
            consecutiveFailures++
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                logger.warn("缓存清理连续失败 $consecutiveFailures 次，发送管理员告警")
                actionNotify(
                    "⚠️ 缓存清理连续失败 $consecutiveFailures 次\n" +
                    "错误: $errorMessage\n" +
                    "请检查磁盘空间和文件权限"
                )
            }
        } else {
            consecutiveFailures = 0  // 成功则重置计数器
        }

        if (totalCleared > 0) {
            logger.info("定时缓存清理完成，共清理 $totalCleared 个配置缓存文件")
        } else {
            logger.info("定时缓存清理完成，无需清理配置缓存文件")
        }
    }

    /**
     * 递归删除当前目录下已过期的缓存文件。
     *
     * @param expire 过期天数
     */
    private fun Path.clearExpireFile(expire: Int): Int {
        var count = 0
        val expireMillis = expire * 24L * 60 * 60 * 1000  // 天数转换为毫秒
        forEachDirectoryEntry {
            if (it.isDirectory()) {
                count += it.clearExpireFile(expire)
            } else {
                val fileAge = System.currentTimeMillis() - it.toFile().lastModified()
                if (fileAge >= expireMillis) {
                    // 仅按最后修改时间淘汰，避免把仍在使用但路径较深的缓存误判为可删目录。
                    logger.debug("删除过期文件: ${it.fileName} (${fileAge / (24 * 60 * 60 * 1000)} 天前)")
                    it.toFile().delete()
                    count++
                }
            }
        }
        return count
    }
}
