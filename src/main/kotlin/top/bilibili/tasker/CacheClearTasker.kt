package top.bilibili.tasker

import org.slf4j.LoggerFactory
import top.bilibili.BiliConfigManager
import top.bilibili.utils.ImageCache
import top.bilibili.utils.cachePath
import java.nio.file.Path
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.isDirectory

object CacheClearTasker : BiliTasker() {
    private val logger = LoggerFactory.getLogger(CacheClearTasker::class.java)
    override var interval: Int = 60 * 60 * 24

    private val expires by BiliConfigManager.config.cacheConfig::expires

    override suspend fun main() {
        logger.info("开始执行定时缓存清理任务...")

        // 清理配置的缓存目录（data/cache/*）
        var totalCleared = 0
        for (e in expires) {
            if (e.value > 0) {
                val cleared = e.key.cachePath().clearExpireFile(e.value)
                if (cleared > 0) {
                    logger.info("清理 ${e.key} 缓存: $cleared 个文件")
                    totalCleared += cleared
                }
            }
        }

        // 清理图片缓存（data/image_cache/*）
        logger.info("清理图片缓存 (image_cache)...")
        try {
            ImageCache.cleanExpiredCache()
        } catch (e: Exception) {
            logger.error("清理图片缓存时出错: ${e.message}", e)
        }

        if (totalCleared > 0) {
            logger.info("定时缓存清理完成，共清理 $totalCleared 个配置缓存文件")
        } else {
            logger.info("定时缓存清理完成，无需清理配置缓存文件")
        }
    }

    private fun Path.clearExpireFile(expire: Int): Int {
        var count = 0
        val expireMillis = expire * 24L * 60 * 60 * 1000  // 天数转换为毫秒
        forEachDirectoryEntry {
            if (it.isDirectory()) {
                count += it.clearExpireFile(expire)
            } else {
                val fileAge = System.currentTimeMillis() - it.toFile().lastModified()
                if (fileAge >= expireMillis) {
                    logger.debug("删除过期文件: ${it.fileName} (${fileAge / (24 * 60 * 60 * 1000)} 天前)")
                    it.toFile().delete()
                    count++
                }
            }
        }
        return count
    }
}
