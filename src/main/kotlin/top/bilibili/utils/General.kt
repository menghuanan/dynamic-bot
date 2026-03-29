package top.bilibili.utils

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import org.jetbrains.skia.Image
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.BiliBiliBot.dataFolderPath
import top.bilibili.BiliConfigManager
import top.bilibili.core.ContactId
import top.bilibili.BiliData
import top.bilibili.TimeDisplayMode
import top.bilibili.api.searchUser
import top.bilibili.client.BiliClient
import top.bilibili.data.DynamicItem
import top.bilibili.data.DynamicType.*
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.io.path.*
import kotlin.math.max
import kotlin.math.min

/**
 * 安全清理文件名，防止路径遍历攻击
 * 移除路径分隔符和相对路径字符
 */
private fun sanitizeFileName(fileName: String): String {
    // 移除路径遍历字符
    var safe = fileName.replace("..", "")
        .replace("/", "")
        .replace("\\", "")
        .replace("\u0000", "")

    // 如果清理后为空，使用默认名称
    if (safe.isBlank()) {
        // 回退到时间戳命名，是为了在拒绝危险文件名的同时仍让缓存流程可继续。
        safe = "file_${System.currentTimeMillis()}"
    }

    // 限制文件名长度
    if (safe.length > 200) {
        // 这里截断尾部长度，是为了兼容深层缓存目录时的文件系统路径限制。
        safe = safe.takeLast(200)
    }

    return safe
}

internal val logger by lazy {
    BiliBiliBot.logger
}

val biliClient = BiliClient()

/**
 * 判断字符串列表中的元素是否全部为空串。
 */
fun List<String>.isBlank(): Boolean {
    if (size == 0) return true
    forEach { if (it != "") return false }
    return true
}

/**
 * 判断字符串列表中是否至少存在一个非空串元素。
 */
fun List<String>.isNotBlank(): Boolean = !isBlank()

/**
 * 将集合中的元素依次发送到协程通道。
 */
suspend fun <E> Channel<E>.sendAll(list: Collection<E>) = list.forEach { send(it) }

/**
 * 当元素非空时追加到集合副本中；为空时尽量复用原列表。
 */
fun <T> Collection<T>.plusOrNull(element: T?): List<T> {
    return if (element != null) {
        val result = ArrayList<T>(size + 1)
        result.addAll(this)
        result.add(element)
        result
    } else {
        this as List
    }
}

/**
 * 为 Ktor 请求体追加 `application/x-www-form-urlencoded` 参数。
 */
fun HttpRequestBuilder.bodyParameter(key: String, value: Any) {
    headers.append("Content-Type", "application/x-www-form-urlencoded")
    setBody(if (body is EmptyContent) "$key=$value" else "$body&$key=$value")
}

/**
 * 返回动态作者的 UID。
 */
val DynamicItem.mid: Long
    get() = modules.moduleAuthor.mid

/**
 * 根据动态 ID 计算近似发布时间戳。
 */
val DynamicItem.time: Long
    get() = (did.toLong() shr 32) + 1498838400L

/**
 * 将动态发布时间格式化为默认中文时间字符串。
 */
val DynamicItem.formatTime: String
    get() = time.formatTime

/**
 * 将时间戳格式化为默认中文时间字符串。
 */
val Long.formatTime: String
    get() = formatTime()

/**
 * 按给定模板格式化秒级时间戳。
 */
fun Long.formatTime(template: String = "yyyy年MM月dd日 HH:mm"): String = DateTimeFormatter.ofPattern(template)
    .format(LocalDateTime.ofEpochSecond(this, 0, OffsetDateTime.now().offset))

/**
 * 根据当前图片配置统一返回绘图链路使用的时间文案。
 * 这里收口配置分支，是为了让绘图调用方只依赖一个入口，避免再次散落绝对/相对时间判断。
 */
val Long.displayTime: String
    get() = formatDisplayTime()

/**
 * 根据指定模式或当前图片配置统一格式化绘图链路使用的时间文案。
 * 允许少量场景显式覆盖配置，是为了在保持统一入口的前提下处理直播绘图这类固定绝对时间需求。
 */
fun Long.formatDisplayTime(mode: TimeDisplayMode? = null): String = when (
    mode ?: runCatching { BiliConfigManager.config.imageConfig.timeDisplayMode }.getOrDefault(TimeDisplayMode.ABSOLUTE)
) {
        TimeDisplayMode.ABSOLUTE -> formatTime
        TimeDisplayMode.RELATIVE -> formatRelativeTime
}

/**
 * 相对时间显示
 * 年份不同        → yyyy年MM月dd日
 * < 1 分钟        → 刚刚
 * < 1 小时        → X 分钟前
 * < 24 小时且同天 → X 小时前
 * 自然日差 = 1    → 昨天 HH:mm
 * 自然日差 = 2    → 两天前
 * 自然日差 = 3    → 三天前
 * 其他（同年）    → MM月dd日
 */
val Long.formatRelativeTime: String
    get() {
        val now = Instant.now().epochSecond
        val diff = now - this

        val zoneOffset = OffsetDateTime.now().offset
        val nowDateTime = LocalDateTime.ofEpochSecond(now, 0, zoneOffset)
        val targetDateTime = LocalDateTime.ofEpochSecond(this, 0, zoneOffset)

        // 1. 年份不同 → yyyy年MM月dd日
        if (nowDateTime.year != targetDateTime.year) {
            return targetDateTime.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))
        }

        // 未来时间使用绝对时间
        if (diff < 0) return targetDateTime.format(DateTimeFormatter.ofPattern("MM月dd日"))

        // 2. < 1 分钟 → 刚刚
        if (diff < 60) return "刚刚"

        // 3. < 1 小时 → X 分钟前
        if (diff < 3600) return "${diff / 60}分钟前"

        // 计算自然日差
        val daysDiff = ChronoUnit.DAYS.between(targetDateTime.toLocalDate(), nowDateTime.toLocalDate())

        // 4. < 24 小时且同一天 → X 小时前
        if (diff < 86400 && daysDiff == 0L) {
            return "${diff / 3600}小时前"
        }

        // 5-7. 根据自然日差显示
        return when (daysDiff) {
            1L -> "昨天 ${targetDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
            2L -> "两天前"
            3L -> "三天前"
            else -> targetDateTime.format(DateTimeFormatter.ofPattern("MM月dd日"))
        }
    }

/**
 * 将动态发布时间格式化为相对时间文案。
 */
val DynamicItem.formatRelativeTime: String
    get() = time.formatRelativeTime

/**
 * 根据当前图片配置统一返回动态绘图链路使用的时间文案。
 */
val DynamicItem.displayTime: String
    get() = time.displayTime

/**
 * 将秒数格式化为文本或时分秒样式的时长字符串。
 */
fun Long.formatDuration(isText: Boolean = true): String {
    val duration = Duration.ofSeconds(this)
    val day = duration.toDays()
    val hour = duration.minusDays(day).toHours()
    val minute = duration.minusDays(day).minusHours(hour).toMinutes()
    val second = duration.minusDays(day).minusHours(hour).minusMinutes(minute).toSeconds()
    return if (isText) buildString {
        if (day > 0) append("${day}天 ")
        if (hour > 0) append("${hour}小时 ")
        if (minute > 0) append("${minute}分钟 ")
        if (second > 0) append("${second}秒")
    }else buildString {
        if (day > 0) append("${day.formatZero}:")
        if (hour > 0) append("${hour.formatZero}:")
        append("${minute.formatZero}:")
        append(second.formatZero)
    }
}

/**
 * 将整型秒数格式化为时长字符串。
 */
fun Int.formatDuration(isText: Boolean = true): String = this.toLong().formatDuration(isText)

/**
 * 将一位数字补零为两位字符串。
 */
val Long.formatZero: String get() = if (this in 1..9) "0$this" else this.toString()

/**
 * 将一位数字补零为两位字符串。
 */
val Int.formatZero: String get() = this.toLong().formatZero

/**
 * 规范化图片来源地址，只保留当前下载链路支持的协议与路径。
 */
fun normalizeImageSourceUrl(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return null
    // 这里显式拒绝 `@` 前缀，是为了避免把业务占位符误当作真实图片地址继续传播。
    if (trimmed.startsWith("cache/") || trimmed.startsWith("file://")) return trimmed
    if (trimmed.startsWith("@")) return null
    if (trimmed.startsWith("//")) return "https:$trimmed"
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    return null
}

/**
 * 为 B 站图片地址追加缩放参数；本地缓存路径不参与该转换。
 */
fun imgApi(imgUrl: String, width: Int, height: Int): String {
    val normalized = normalizeImageSourceUrl(imgUrl) ?: return ""
    if (normalized.startsWith("cache/") || normalized.startsWith("file://")) return ""
    return "${normalized}@${width}w_${height}h_1e_1c.png"
}

/**
 * 返回动态对应的页面链接。
 */
val DynamicItem.link: String
    get() = when (type) {
        DYNAMIC_TYPE_WORD,
        DYNAMIC_TYPE_DRAW,
        DYNAMIC_TYPE_FORWARD,
        DYNAMIC_TYPE_COMMON_SQUARE,
        DYNAMIC_TYPE_COMMON_VERTICAL,
        DYNAMIC_TYPE_UNKNOWN -> "https://t.bilibili.com/$idStr"

        DYNAMIC_TYPE_ARTICLE -> "https://www.bilibili.com/read/cv${modules.moduleDynamic.major?.article?.id}"
        DYNAMIC_TYPE_AV -> "https://www.bilibili.com/video/${modules.moduleDynamic.major?.archive?.bvid}"
        DYNAMIC_TYPE_MUSIC -> "https://www.bilibili.com/audio/au${modules.moduleDynamic.major?.music?.id}"
        DYNAMIC_TYPE_LIVE -> "https://live.bilibili.com/${modules.moduleDynamic.major?.live?.id}"
        DYNAMIC_TYPE_LIVE_RCMD -> "https://live.bilibili.com/${modules.moduleDynamic.major?.live?.id}"
        DYNAMIC_TYPE_PGC -> "https://www.bilibili.com/bangumi/play/ep${modules.moduleDynamic.major?.pgc?.epid}"
        DYNAMIC_TYPE_PGC_UNION -> "https://www.bilibili.com/bangumi/play/ep${modules.moduleDynamic.major?.pgc?.epid}"
        DYNAMIC_TYPE_UGC_SEASON -> "https://www.bilibili.com/video/av${modules.moduleDynamic.major?.ugcSeason?.aid}"
        DYNAMIC_TYPE_NONE -> ""
    }


/**
 * 从类路径加载资源文件并返回本地路径字符串。
 */
fun loadResource(file: String) =
    BiliBiliBot::class.java.getResource(file)?.path!!
//BiliBiliBot::class.java.getResource(file)!!.openStream().use { it.readBytes() }

/**
 * 从类路径读取资源字节数组。
 */
fun loadResourceBytes(path: String): ByteArray {
    // 确保路径以 / 开头，从 classpath 根目录开始查找
    val resourcePath = if (path.startsWith("/")) path else "/$path"
    // ✅ 使用 use 确保资源正确关闭
    val stream = BiliBiliBot.javaClass.classLoader.getResourceAsStream(resourcePath.substring(1))
    if (stream == null) {
        throw IllegalArgumentException("无法找到资源文件: $path (查找路径: $resourcePath)")
    }
    return stream.use { it.readBytes() }
}

/**
 * 图片及其他缓存文件的根目录。
 */
val cachePath: Path by lazy {
    dataFolderPath.resolve("cache")
}

/**
 * 返回指定缓存类型对应的目录路径，不存在时自动创建。
 */
fun CacheType.cachePath(): Path {
    return cachePath.resolve(path).apply {
        if (notExists()) createDirectories()
    }
}

/**
 * 根据相对文件路径返回指定缓存类型下的缓存文件位置。
 */
fun CacheType.cacheFile(filePath: String): Path {
    val split = filePath.split("/")
    val path = split.dropLast(1).joinToString("/")
    val file = split.last()
    return cachePath().resolve(path).apply {
        if (notExists()) createDirectories()
    }.resolve(file)
}

/**
 * 统一定义项目内可用的缓存目录分类。
 */
enum class CacheType(val path: String) {
    DRAW("draw"),
    DRAW_DYNAMIC("draw/dynamic"),
    DRAW_LIVE("draw/live"),
    DRAW_SEARCH("draw/search"),
    IMAGES("images"),
    EMOJI("emoji"),
    USER("user"),
    OTHER("other"),
    UNKNOWN(""),
}

/**
 * 在目录树中递归查找指定文件名。
 */
fun Path.findFile(file: String): Path? {
    forEachDirectoryEntry {
        if (it.isDirectory()) {
            val path = it.findFile(file)
            if (path != null) return path
        } else {
            if (it.name == file) return it
        }
    }
    return null
}

/**
 * 缓存图片到文件（不关闭 Image，由调用方负责关闭）
 * 注意：此函数不会关闭传入的 Image，调用方需要自行管理 Image 的生命周期
 */
fun cacheImage(image: Image, path: String, cacheType: CacheType): String {
    val file = cacheType.cacheFile(path)
    val data = image.encodeToData()!!
    try {
        file.writeBytes(data.bytes)
    } finally {
        data.close()
    }
    return file.toFile().absolutePath
}

/**
 * 缓存图片到文件并关闭 Image，释放 Skia 原生内存
 * 这是推荐使用的方法，会自动释放 Image 占用的原生内存
 * @param image 要缓存的图片，缓存后会被关闭
 * @param path 缓存路径
 * @param cacheType 缓存类型
 * @return 缓存文件的绝对路径
 */
fun cacheImageAndClose(image: Image, path: String, cacheType: CacheType): String {
    return try {
        cacheImage(image, path, cacheType)
    } finally {
        image.close()
    }
}

/**
 * 缓存图片列表到文件并关闭所有 Image
 * @param images 要关闭的中间 Image 列表
 * @param finalImage 最终要缓存的图片
 * @param path 缓存路径
 * @param cacheType 缓存类型
 * @return 缓存文件的绝对路径
 */
fun cacheImageAndCloseAll(images: List<Image>, finalImage: Image, path: String, cacheType: CacheType): String {
    return try {
        cacheImage(finalImage, path, cacheType)
    } finally {
        // 关闭所有中间 Image
        images.forEach { runCatching { it.close() } }
        // 关闭最终 Image
        runCatching { finalImage.close() }
    }
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
        // 解析异常直接按高风险处理，是为了避免边界输入绕过 SSRF 防护。
        true
    }
}

/**
 * 从缓存读取资源，不存在时下载并按缓存策略落盘。
 */
suspend fun getOrDownload(url: String, cacheType: CacheType = CacheType.UNKNOWN): ByteArray? {
     try {
        val normalizedUrl = normalizeImageSourceUrl(url) ?: run {
            logger.warn("跳过非法资源 URL: $url")
            return null
        }

        // 安全清理文件名，防止路径遍历攻击
        val rawFileName = normalizedUrl.split("?").first().split("@").first().split("/").last()
        val fileName = sanitizeFileName(rawFileName)

        val filePath = if (cacheType == CacheType.UNKNOWN) {
            cachePath.findFile(fileName) ?: CacheType.OTHER.cacheFile(fileName)
        } else {
            cacheType.cacheFile(fileName)
        }
         if (filePath.exists()) {
            // 命中缓存时刷新时间戳，是为了让仍在使用的文件不要被清理策略过早回收。
            filePath.setLastModifiedTime(FileTime.from(Instant.now()))
            return prepareDownloadedImageBytes(filePath)
         } else if(normalizedUrl.startsWith("cache/")){
             return null
         }
         else {
             // 修复：拒绝内网地址访问
             if (isPrivateNetwork(normalizedUrl)) {
                 logger.warn("拒绝下载内网 URL (SSRF 防护): $normalizedUrl")
                 return null
             }

             var retryCount = 0
             val maxRetries = 1

             while (retryCount <= maxRetries) {
                 try {
                    biliClient.useHttpClient {
                        it.get(normalizedUrl).body<ByteArray>().apply {
                            filePath.writeBytes(this)
                        }
                    }
                    if (filePath.exists()) {
                        filePath.setLastModifiedTime(FileTime.from(Instant.now()))
                        return prepareDownloadedImageBytes(filePath)
                    }
                }catch (t: Throwable) {
                    logger.warn("下载资源失败 (尝试 ${retryCount + 1}/${maxRetries + 1}): $normalizedUrl")
                    if (retryCount < maxRetries) {
                        // 失败后短暂等待，是为了给临时网络抖动和 CDN 回源留出恢复时间。
                        kotlinx.coroutines.delay(3000)
                        retryCount++
                    } else {
                        logger.error("下载资源彻底失败! $normalizedUrl\n$t")
                        return null
                    }
                }
             }
             return null
        }
    }catch (t: Throwable) {
        logger.error("获取图片失败! $url\n$t")
        return null
    }
}

/**
 * 获取并解析图片资源，失败时返回 `null`。
 */
suspend fun getOrDownloadImage(url: String, cacheType: CacheType = CacheType.UNKNOWN) = try {
    getOrDownload(url, cacheType)?.let { Image.makeFromEncoded(it) }
}catch (t: Throwable){
    logger.error("解析图片失败! $url\n$t")
    null
}

/**
 * 获取原图失败时回退到兜底图片，并最终保证返回可用 [Image]。
 */
suspend fun getOrDownloadImageDefault(url: String, fallbackUrl: String, cacheType: CacheType = CacheType.UNKNOWN): Image {
    val normalizedUrl = normalizeImageSourceUrl(url)
    val normalizedFallbackUrl = normalizeImageSourceUrl(fallbackUrl)

    val original = if (BiliConfigManager.config.cacheConfig.downloadOriginal && normalizedUrl != null) {
        getOrDownloadImage(normalizedUrl, cacheType)
    } else null

    return original
        ?: normalizedFallbackUrl?.let { getOrDownloadImage(it, cacheType) }
        ?: Image.makeFromEncoded(loadResourceBytes("image/IMAGE_MISS.png"))
}


// TODO: 以下 Mirai Contact 相关函数已注释，需要使用新的消息发送机制替代
/*
suspend fun Contact.sendImage(url: String, cacheType: CacheType = CacheType.UNKNOWN) = try {
    getOrDownload(url, cacheType)?.toExternalResource()?.let { sendImage(it.toAutoCloseable()) }
}catch (e: Exception){
    logger.error("发送图片失败! $url\n$e")
    null
}

suspend fun Contact.uploadImage(url: String, cacheType: CacheType = CacheType.UNKNOWN) = try {
    getOrDownload(url, cacheType)?.toExternalResource()?.let { uploadImage(it.toAutoCloseable()) }
}catch (e: Exception){
    logger.error("上传图片失败! $url\n$e")
    null
}

suspend fun List<Contact>.uploadImage(url: String, cacheType: CacheType = CacheType.UNKNOWN): String? {
    var sc: String? = null
    for(c in this) {
        sc = c.uploadImage(url, cacheType)?.serializeToMiraiCode()
        if (sc != null) break
    }
    return sc
}

suspend fun List<Contact>.uploadImage(path: Path): String? =
    uploadImage(path.readBytes().toExternalResource().toAutoCloseable())

suspend fun List<Contact>.uploadImage(resource: ExternalResource): String? {
    for(c in this) {
        runCatching {
            c.uploadImage(resource).serializeToMiraiCode()
        }.onSuccess {
            return it
        }
    }
    return null
}

/**
 * 查找Contact
 */
fun findContact(del: String): Contact? {
    if (del.isBlank()) {
        logger.error("查找用户为空")
        return null
    }
    val delegate = try { del.toLong() } catch (e: NumberFormatException) { return null }
    try {
        for (bot in Bot.instances) {
            if (delegate < 0) {
                for (group in bot.groups) {
                    if (group.id == delegate * -1) return group
                }
            } else {
                for (friend in bot.friends) {
                    if (friend.id == delegate) return friend
                }
                for (stranger in bot.strangers) {
                    if (stranger.id == delegate) return stranger
                }
                for (group in bot.groups) {
                    for (member in group.members) {
                        if (member.id == delegate) return member
                    }
                }
            }
        }
    }catch (t: Throwable) {
        logger.error("获取用户失败")
    }
    logger.error("未找到此用户 [$del]")
    return null
}

fun findContactAll(delegate: String): Contact? {
    return try {
        findContactAll(delegate.toLong())
    }catch (e: NumberFormatException) {
        null
    }
}

fun findContactAll(delegate: Long): Contact? {
    for (bot in Bot.instances) {
        for (friend in bot.friends) {
            if (friend.id == delegate) return friend
        }
        for (group in bot.groups) {
            if (group.id == delegate) return group
        }
        for (stranger in bot.strangers) {
            if (stranger.id == delegate) return stranger
        }
        for (group in bot.groups) {
            for (member in group.members) {
                if (member.id == delegate) return member
            }
        }
    }
    return null
}

/**
 * 通过正负号区分群和用户
 * @author cssxsh
 */
val Contact.delegate get() = (if (this is Group) id * -1 else id).toString()
*/


/**
 * 按本地配置中的 UID 或昵称查找匹配候选。
 */
fun findLocalIdOrName(target: String): List<Pair<Long, Double>> {
    return try {
        listOf(Pair(target.toLong(), 1.0))
    } catch (e: NumberFormatException) {
        val list = BiliConfigManager.data.dynamic.map { Pair(it.key, it.value.name) }
        fuzzySearch(list, target)
    }
}

/**
 * 触发一次远端用户搜索后，再按本地缓存数据返回匹配候选。
 */
suspend fun findRemoteIdOrName(target: String): List<Pair<Long, Double>> {
    return try {
        listOf(Pair(target.toLong(), 1.0))
    } catch (e: NumberFormatException) {
        // 预留未使用变量 users: val users = biliClient.searchUser(target)
        biliClient.searchUser(target)
        val list = BiliConfigManager.data.dynamic.map { Pair(it.key, it.value.name) }
        fuzzySearch(list, target)
    }
}

/**
 * 对 UID 与昵称列表执行简单模糊匹配，并在结果明显时自动消歧。
 */
fun fuzzySearch(
    list: List<Pair<Long, String>>,
    target: String,
    minRate: Double = 0.2,
    matchRate: Double = 0.6,
    disambiguationRate: Double = 0.1,
): List<Pair<Long, Double>> {
    val candidates = list
        .associateWith { it.second.fuzzyMatchWith(target) }
        .filter { it.value >= minRate }
        .toList()
        .map { Pair(it.first.first, it.second) }
        .sortedByDescending { it.second }

    val bestMatches = candidates.filter { it.second >= matchRate }

    return when {
        bestMatches.isEmpty() -> candidates
        bestMatches.size == 1 -> listOf(bestMatches.single().first to 1.0)
        else -> {
            if (bestMatches.first().second - bestMatches.last().second <= disambiguationRate) {
                // resolution ambiguity
                // 分差过小时保留候选集合，是为了避免把多个相近昵称错误收敛成单一用户。
                candidates
            } else {
                listOf(bestMatches.first().first to 1.0)
            }
        }
    }
}

/**
 * 计算当前字符串与目标字符串的顺序匹配得分。
 */
internal fun String.fuzzyMatchWith(target: String): Double {
    if (this == target) {
        return 1.0
    }
    var match = 0
    for (i in 0..(max(this.lastIndex, target.lastIndex))) {
        val t = target.getOrNull(match) ?: break
        if (t == this.getOrNull(i)) {
            match++
        }
    }

    val longerLength = max(this.length, target.length)
    val shorterLength = min(this.length, target.length)

    return match.toDouble() / (longerLength + (shorterLength - match))
}

/**
 * 管理员操作通知的结构化消息体。
 */
@Serializable
data class ActionMessage(
    val operator: String,
    val target: String,
    val action: String,
    val message: String,
)

// TODO: 以下通知函数需要使用新的消息发送机制重新实现
/**
 * 按字段构造管理员操作通知并发送。
 */
suspend fun actionNotify(subject: String?, operator: String, target: String, action: String, message: String) {
    actionNotify(subject, ActionMessage(operator, target, action, message))
}

/**
 * 发送结构化管理员操作通知。
 */
suspend fun actionNotify(subject: String?, message: ActionMessage) {
    if (top.bilibili.connector.PlatformCapabilityService.canSendManagedAdminNotice(subject = subject)) {
        actionNotify(buildString {
            appendLine("操作人: ${message.operator}")
            appendLine("目标: ${message.target}")
            appendLine("操作: ${message.action}")
            appendLine("消息: ${message.message}")
        })
    }
}

/**
 * 发送纯文本管理员通知；发送失败时回退为日志记录。
 */
suspend fun actionNotify(message: String) {
    if (!top.bilibili.connector.PlatformCapabilityService.canSendManagedAdminNotice()) return
    val success = top.bilibili.service.MessageGatewayProvider.require().sendAdminMessage(message)
    if (!success) {
        // 回退到日志输出，是为了在网关不可用时仍保留关键管理审计信息。
        logger.info("通知消息: $message")
    }
}

/**
 * 根据用户输入匹配目标用户，并将唯一匹配结果交给回调处理。
 */
inline fun matchUser(user: String, matchSuccess: (uid: Long) -> String?): String? {
    val u = findLocalIdOrName(user)
    return if (u.isEmpty()) {
        "未匹配到用户哦"
    } else if (u.size == 1) {
        matchSuccess(u.first().first)
    } else {
        buildString {
            appendLine("有多个匹配项：")
            u.forEach {
                appendLine("${BiliConfigManager.data.dynamic[it.first]?.name}: ${it.second}")
            }
        }
    }
}

/**
 * 计算字符串的 MD5 摘要。
 */
fun String.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    return md.digest(toByteArray()).joinToString("") { "%02x".format(it) }
}

