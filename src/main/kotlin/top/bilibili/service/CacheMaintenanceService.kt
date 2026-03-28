package top.bilibili.service

import top.bilibili.core.BiliBiliBot
import java.io.File

/**
 * 提供手动缓存清理入口，便于启动流程和运维操作复用同一实现。
 */
object CacheMaintenanceService {
    /**
     * 统一清理缓存目录中的文件，并把失败明细保留在日志里。
     */
    fun clearAllCache() {
        try {
            val cacheDir = File("data/cache")
            if (!cacheDir.exists()) {
                BiliBiliBot.logger.info("缓存目录不存在，跳过清理")
                return
            }

            var totalDeleted = 0
            cacheDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    try {
                        if (file.delete()) {
                            totalDeleted++
                        }
                    } catch (e: Exception) {
                        BiliBiliBot.logger.error("删除缓存文件失败: ${file.path}, ${e.message}")
                    }
                }
            }

            BiliBiliBot.logger.info("缓存清理完成，共删除 $totalDeleted 个文件")
        } catch (e: Exception) {
            BiliBiliBot.logger.error("清理缓存时发生错误: ${e.message}", e)
        }
    }
}
