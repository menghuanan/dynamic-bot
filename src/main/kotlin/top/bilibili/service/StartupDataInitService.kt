package top.bilibili.service

import top.bilibili.core.BiliBiliBot
import top.bilibili.utils.ImageCache

object StartupDataInitService {
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
