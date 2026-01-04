package top.bilibili.utils

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import top.bilibili.core.BiliBiliBot
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * 图片缓存管理器
 * 负责下载网络图片并保存到本地，返回 file:// 协议路径供 NapCat 使用
 */
object ImageCache {
    private val logger = LoggerFactory.getLogger(ImageCache::class.java)

    /** 缓存目录 */
    private val cacheDir = File(BiliBiliBot.dataFolder, "image_cache").apply {
        if (!exists()) mkdirs()
    }

    /** HTTP 客户端（用于下载图片） */
    private val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                followRedirects(true)
            }
        }
    }

    /**
     * 从 URL 下载图片到本地缓存
     * @param url 图片 URL
     * @return file:// 协议的绝对路径，失败返回 null
     */
    suspend fun cacheImage(url: String): String? {
        if (url.isBlank()) return null

        return try {
            // 如果已经是本地文件，直接返回
            if (url.startsWith("file://")) {
                return url
            }

            // 生成缓存文件名（使用 URL 的 MD5 哈希值）
            val hash = md5(url)
            val extension = url.substringAfterLast('.', "png").take(4)
            val cacheFile = File(cacheDir, "$hash.$extension")

            // 如果缓存已存在且未过期（24小时），直接返回
            if (cacheFile.exists()) {
                val fileAge = Instant.now().epochSecond - cacheFile.lastModified() / 1000
                if (fileAge < 86400) { // 24 小时
                    logger.debug("使用已缓存的图片: ${cacheFile.name}")
                    return "file:///${cacheFile.absolutePath.replace("\\", "/")}"
                }
            }

            // 下载图片
            logger.debug("正在下载图片: $url")
            val imageBytes = downloadImage(url)

            if (imageBytes.isEmpty()) {
                logger.warn("下载图片失败（空内容）: $url")
                return null
            }

            // 保存到本地
            withContext(Dispatchers.IO) {
                cacheFile.writeBytes(imageBytes)
            }

            logger.debug("图片已缓存: ${cacheFile.name} (${imageBytes.size / 1024} KB)")

            // 返回 file:// 协议路径
            "file:///${cacheFile.absolutePath.replace("\\", "/")}"

        } catch (e: Exception) {
            logger.error("缓存图片失败: $url - ${e.message}", e)
            null
        }
    }

    /**
     * 将本地文件路径转换为 file:// 协议
     * @param path 本地文件路径（可以是相对路径或绝对路径）
     * @return file:// 协议的绝对路径
     */
    fun toFileUrl(path: String): String {
        val file = File(path)
        val absolutePath = if (file.isAbsolute) {
            file.absolutePath
        } else {
            File(BiliBiliBot.dataFolder, path).absolutePath
        }
        return "file:///${absolutePath.replace("\\", "/")}"
    }

    /**
     * 下载图片字节数据
     */
    private suspend fun downloadImage(url: String): ByteArray {
        return try {
            val response = httpClient.get(url)

            if (response.status == HttpStatusCode.OK) {
                response.readRawBytes()
            } else {
                logger.warn("下载图片失败，HTTP状态码: ${response.status} - $url")
                ByteArray(0)
            }
        } catch (e: Exception) {
            logger.error("下载图片异常: $url - ${e.message}")
            ByteArray(0)
        }
    }

    /**
     * 计算字符串的 MD5 哈希值
     */
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 清理过期缓存（超过 7 天的文件）
     */
    fun cleanExpiredCache() {
        try {
            val now = Instant.now().epochSecond
            var cleanedCount = 0
            var freedSpace = 0L

            // 获取清理前的统计
            val beforeStats = getCacheStats()
            logger.info("开始清理图片缓存，当前: ${beforeStats.fileCount} 个文件，${beforeStats.totalSizeMB} MB")

            cacheDir.listFiles()?.forEach { file ->
                val fileAge = now - file.lastModified() / 1000
                if (fileAge > 604800) { // 7 天
                    val fileSize = file.length()
                    if (file.delete()) {
                        freedSpace += fileSize
                        cleanedCount++
                        logger.debug("删除过期缓存: ${file.name} (${fileAge / 86400} 天前)")
                    }
                }
            }

            // 获取清理后的统计
            val afterStats = getCacheStats()

            if (cleanedCount > 0) {
                logger.info("清理了 $cleanedCount 个过期图片缓存，释放 ${freedSpace / 1024 / 1024} MB 空间")
                logger.info("清理后: ${afterStats.fileCount} 个文件，${afterStats.totalSizeMB} MB")
            } else {
                logger.info("没有需要清理的过期图片缓存 (${beforeStats.fileCount} 个文件在保留期内)")
            }
        } catch (e: Exception) {
            logger.error("清理图片缓存失败: ${e.message}", e)
        }
    }

    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): CacheStats {
        return try {
            val files = cacheDir.listFiles() ?: emptyArray()
            val totalSize = files.sumOf { it.length() }
            CacheStats(
                fileCount = files.size,
                totalSizeBytes = totalSize,
                cacheDir = cacheDir.absolutePath
            )
        } catch (e: Exception) {
            CacheStats(0, 0, cacheDir.absolutePath)
        }
    }

    data class CacheStats(
        val fileCount: Int,
        val totalSizeBytes: Long,
        val cacheDir: String
    ) {
        val totalSizeMB: Long get() = totalSizeBytes / 1024 / 1024
    }
}
