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
// TODO: 移除 Mirai 依赖，需要实现自定义字体下载功能
// import xyz.cssxsh.mirai.skia.downloadTypeface
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.name

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
            BiliBiliBot.logger.error("解析 cookies.json 失败")
        }
    }
    if (BiliBiliBot.cookie.isEmpty()) BiliBiliBot.cookie.parse(accountConfig.cookie)

    try {
        BiliBiliBot.uid = biliClient.userInfo()?.mid!!
        BiliBiliBot.logger.info("BiliBili UID: ${BiliBiliBot.uid}")
    } catch (e: Exception) {
        BiliBiliBot.logger.error(e.message)
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
            val res = biliClient.createGroup(accountConfig.followGroup) ?: throw Exception()
            BiliBiliBot.tagid = res.tagId
        } catch (e: Exception) {
            BiliBiliBot.logger.error("初始化分组失败 ${e.message}")
        }

    }
}

suspend fun loadFonts() {
    val fontFolder = BiliBiliBot.dataFolder.resolve("font")
    val fontFolderPath = BiliBiliBot.dataFolderPath.resolve("font")
    val LXGW = fontFolder.resolve("LXGWWenKai-Bold.ttf")

    fontFolderPath.apply {
        if (!exists()) createDirectory()
        if (fontFolder.listFiles().none { it.isFile } || !LXGW.exists()) {
            try {
                // TODO: 实现字体下载功能（原使用 xyz.cssxsh.mirai.skia.downloadTypeface）
                // 可以使用 Ktor HTTP 客户端下载并解压字体文件
                // downloadTypeface(fontFolder, "https://file.zfont.cn/d/file/font_cn_file/霞鹜文楷-v1.235.2.zip")
                BiliBiliBot.logger.warn("字体下载功能暂未实现，请手动下载字体文件到 data/font 目录")
                /*
                val f = fontFolder.resolve("霞鹜文楷-v1.235.2")
                f.resolve("LXGWWenKai-Bold.ttf").copyTo(LXGW)
                try {
                    f.walkBottomUp().onLeave { it.delete() }
                }catch (_: Exception) { }
                */
            }catch (e: Throwable) {
                BiliBiliBot.logger.error("下载字体失败! $e")
            }
        }

        // 从 data/font 目录加载字体
        var fontLoaded = false
        forEachDirectoryEntry {
            if (it.toFile().isFile) {
                loadTypeface(it.toString(), it.name.split(".").first())
                fontLoaded = true
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
}