package top.bilibili.service

import top.bilibili.core.BiliBiliBot
import top.bilibili.utils.ImageCache

/**
 * 负责启动期数据初始化与缓存预清理，避免主启动流程夹杂大量细节。
 */
object StartupDataInitService {
    /**
     * 初始化 B 站数据并顺带清理过期图片缓存，保证启动后的基础状态干净可用。
     */
    suspend fun initBiliData() {
        try {
            BiliBiliBot.logger.info("正在初始化 B站数据...")
            top.bilibili.initData()

            BiliBiliBot.logger.info("清理过期的图片缓存...")
            try {
                ImageCache.cleanExpiredCache()
            } catch (e: Exception) {
                BiliBiliBot.logger.warn("清理图片缓存时出错: ${e.message}")
            }

            BiliBiliBot.logger.info("B站数据初始化完成")
        } catch (e: Exception) {
            BiliBiliBot.logger.error("初始化 B站数据失败: ${e.message}", e)
        }
    }
}
