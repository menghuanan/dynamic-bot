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
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool

/**
 * 图片缓存管理器
 * 负责下载网络图片并保存到本地，返回 file:// 协议路径供 NapCat 使用
 */
object ImageCache : Closeable {
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

                // 配置连接池参数
                connectionPool(ConnectionPool(
                    maxIdleConnections = 5,
                    keepAliveDuration = 5,
                    timeUnit = TimeUnit.MINUTES
                ))
            }
        }
    }

    /**
     * 关闭 HTTP 客户端，释放资源
     */
    override fun close() {
        logger.info("正在关闭 ImageCache HTTP 客户端...")
        try {
            httpClient.close()
            logger.info("ImageCache HTTP 客户端已关闭")
        } catch (e: Exception) {
            logger.error("关闭 ImageCache HTTP 客户端失败: ${e.message}", e)
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
            // 安全清理文件扩展名，使用白名单验证
            val rawExtension = url.substringAfterLast('.', "png").take(4).lowercase()
            val extension = when {
                rawExtension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> rawExtension
                else -> "png" // 默认使用 png
            }
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
     * 检测 URL 是否指向内网地址
     * 防止 SSRF 攻击
     */
    private fun isPrivateNetwork(url: String): Boolean {
        // 允许本地缓存文件
        if (url.startsWith("cache/") || url.startsWith("file://")) {
            return false
        }

        return try {
            val host = url.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()

            // 检查是否为 IP 地址
            if (host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) {
                // IPv4 私有地址段
                val parts = host.split(".").map { it.toIntOrNull() ?: 0 }
                if (parts.size == 4) {
                    // 10.0.0.0/8
                    if (parts[0] == 10) return true
                    // 172.16.0.0/12
                    if (parts[0] == 172 && parts[1] in 16..31) return true
                    // 192.168.0.0/16
                    if (parts[0] == 192 && parts[1] == 168) return true
                    // 127.0.0.0/8 (localhost)
                    if (parts[0] == 127) return true
                    // 169.254.0.0/16 (link-local)
                    if (parts[0] == 169 && parts[1] == 254) return true
                    // 0.0.0.0/8
                    if (parts[0] == 0) return true
                }
            }

            // 检查是否为 localhost 域名
            if (host == "localhost" || host.endsWith(".local")) {
                return true
            }

            // 检查是否为内网域名模式
            if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                return true
            }

            false
        } catch (e: Exception) {
            // 解析失败，保守起见认为是内网地址
            true
        }
    }

    /**
     * 下载图片字节数据
     * 修复：确保 HTTP 响应体被正确处理，避免连接池泄漏
     */
    private suspend fun downloadImage(url: String): ByteArray {
        // 修复：拒绝内网地址访问
        if (isPrivateNetwork(url)) {
            logger.warn("拒绝下载内网 URL (SSRF 防护): $url")
            return ByteArray(0)
        }

        var retryCount = 0
        val maxRetries = 1

        while (retryCount <= maxRetries) {
            try {
                // ✅ 修复：使用 let 确保响应体在作用域内被完全消费
                val result = httpClient.get(url).let { response ->
                    if (response.status == HttpStatusCode.OK) {
                        response.readRawBytes()
                    } else {
                        logger.warn("下载图片失败，HTTP状态码: ${response.status} - $url")
                        // ✅ 确保非 OK 响应也消费响应体
                        try { response.readRawBytes() } catch (_: Exception) {}
                        null
                    }
                }
                if (result != null) {
                    return result
                }
            } catch (e: Exception) {
                logger.warn("下载图片异常 (尝试 ${retryCount + 1}/${maxRetries + 1}): $url - ${e.message}")
            }

            if (retryCount < maxRetries) {
                kotlinx.coroutines.delay(3000)
                retryCount++
            } else {
                break
            }
        }

        logger.error("图片下载彻底失败 (已重试 $retryCount 次): $url")
        return ByteArray(0)
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
     * 基于大小的 LRU 清理策略
     * @param maxSizeMB 最大缓存大小（MB），默认 1024 MB (1 GB)
     */
    fun cleanBySize(maxSizeMB: Long = 1024L) {
        try {
            val maxBytes = maxSizeMB * 1024 * 1024
            val files = cacheDir.listFiles()
                ?.sortedBy { it.lastModified() }  // 按修改时间排序（最旧的在前）
                ?: return

            var totalSize = files.sumOf { it.length() }

            if (totalSize <= maxBytes) {
                logger.debug("缓存大小 ${totalSize / 1024 / 1024} MB，未超过限制 $maxSizeMB MB")
                return
            }

            var deletedCount = 0
            var freedBytes = 0L

            // 从最旧的文件开始删除，直到低于限制
            for (file in files) {
                if (totalSize <= maxBytes) break

                val fileSize = file.length()
                if (file.delete()) {
                    totalSize -= fileSize
                    freedBytes += fileSize
                    deletedCount++
                    logger.debug("LRU 删除: ${file.name} (${fileSize / 1024} KB)")
                }
            }

            if (deletedCount > 0) {
                logger.info("LRU 清理完成: 删除 $deletedCount 个最旧文件，释放 ${freedBytes / 1024 / 1024} MB，当前缓存 ${totalSize / 1024 / 1024} MB")
            }
        } catch (e: Exception) {
            logger.error("基于大小清理缓存失败: ${e.message}", e)
        }
    }

    /**
     * 组合清理策略：先按时间清理过期文件，再按大小清理
     */
    fun cleanCache() {
        try {
            logger.info("开始执行图片缓存清理...")

            // 先清理 7 天前的文件
            cleanExpiredCache()

            // 再确保总大小不超过 1 GB
            cleanBySize()

            // 输出最终统计
            val finalStats = getCacheStats()
            logger.info("缓存清理完成，最终状态: ${finalStats.fileCount} 个文件，${finalStats.totalSizeMB} MB")
        } catch (e: Exception) {
            logger.error("组合清理缓存失败: ${e.message}", e)
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
