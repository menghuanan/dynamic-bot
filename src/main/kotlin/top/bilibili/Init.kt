package top.bilibili

import top.bilibili.BiliConfigManager
import top.bilibili.core.BiliBiliBot
import top.bilibili.api.createGroup
import top.bilibili.api.followGroup
import top.bilibili.api.userInfo
import top.bilibili.data.EditThisCookie
import top.bilibili.data.toCookie
import top.bilibili.utils.FontUtils.loadTypeface
import top.bilibili.utils.biliClient
import top.bilibili.utils.decode
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.name

private val fontsInitialized = AtomicBoolean(false)

suspend fun initData() {
    checkCookie()
    initTagid()
    loadFonts()
}

suspend fun checkCookie() {
    val accountConfig = BiliConfigManager.config.accountConfig
    val cookieFile = BiliBiliBot.dataFolder.resolve("cookies.json")
    if (cookieFile.exists()) {
        try {
            val cookie = cookieFile.readText().decode<List<EditThisCookie>>().toCookie()
            if (!cookie.isEmpty()) {
                BiliBiliBot.cookie = cookie
            } else {
                BiliBiliBot.logger.error("cookies.json 中缺少必要的值 [SESSDATA] [bili_jct]")
            }
        } catch (e: Exception) {
            BiliBiliBot.logger.error("解析 cookies.json 失败", e)
        }
    }
    if (BiliBiliBot.cookie.isEmpty()) BiliBiliBot.cookie.parse(accountConfig.cookie)

    try {
        BiliBiliBot.uid = biliClient.userInfo()?.mid!!
        BiliBiliBot.logger.info("BiliBili UID: ${BiliBiliBot.uid}")
    } catch (e: Exception) {
        BiliBiliBot.logger.error("获取用户信息失败: ${e.message}", e)
        BiliBiliBot.logger.error("如未登录，请bot管理员在聊天环境内发送 /login 或 登录 进行登录")
        return
    }
}

suspend fun initTagid() {
    val accountConfig = BiliConfigManager.config.accountConfig
    if (accountConfig.autoFollow && accountConfig.followGroup.isNotEmpty()) {
        try {
            biliClient.followGroup()?.forEach {
                if (it.name == accountConfig.followGroup) {
                    BiliBiliBot.tagid = it.tagId
                    return
                }
            }
            val res = biliClient.createGroup(accountConfig.followGroup) ?: throw Exception("创建分组失败: 返回结果为空")
            BiliBiliBot.tagid = res.tagId
        } catch (e: Exception) {
            BiliBiliBot.logger.error("初始化分组失败", e)
        }

    }
}

suspend fun loadFonts() {
    if (!fontsInitialized.compareAndSet(false, true)) {
        BiliBiliBot.logger.debug("字体已初始化，跳过重复加载")
        return
    }

    val fontFolder = BiliBiliBot.dataFolder.resolve("font")
    val fontFolderPath = BiliBiliBot.dataFolderPath.resolve("font")

    try {
        fontFolderPath.apply {
            if (!exists()) createDirectory()


            // 从 data/font 目录加载字体
            var fontLoaded = false
            forEachDirectoryEntry { fontPath ->
                if (!fontPath.toFile().isFile) return@forEachDirectoryEntry

                val extension = fontPath.name.substringAfterLast('.', "")
                if (extension.lowercase() in setOf("ttf", "otf", "ttc")) {
                    runCatching {
                        loadTypeface(fontPath.toString(), fontPath.name.substringBeforeLast('.'))
                    }.onSuccess {
                        fontLoaded = true
                    }.onFailure { err ->
                        BiliBiliBot.logger.warn("加载字体失败: ${fontPath.name}: ${err.message}")
                    }
                } else {
                    BiliBiliBot.logger.debug("跳过非字体文件: ${fontPath.name}")
                }
            }

            // 如果 data/font 目录没有字体文件，尝试从 resources/font 目录加载
            if (!fontLoaded) {
                BiliBiliBot.logger.info("data/font 目录为空，尝试从 resources/font 目录加载字体...")
                // 只加载实际存在于 resources/font 目录的字体文件
                val resourceFonts = listOf(
                    "/font/SourceHanSansSC-Regular.otf" to "Source Han Sans SC",
                    "/font/FansCard.ttf" to "FansCard"
                )

                for ((path, alias) in resourceFonts) {
                    top.bilibili.utils.FontUtils.loadTypefaceFromResource(path, alias)
                }
            }
        }
    } catch (e: Throwable) {
        fontsInitialized.set(false)
        throw e
    }
}
