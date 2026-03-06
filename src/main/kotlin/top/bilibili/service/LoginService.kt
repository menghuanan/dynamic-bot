package top.bilibili.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.BusinessLifecycleManager
import top.bilibili.core.resource.ResourceStrictness
import top.bilibili.BiliConfigManager
import top.bilibili.api.getLoginQrcode
import top.bilibili.api.loginInfo
import top.bilibili.draw.loginQrCode
import top.bilibili.initTagid
import top.bilibili.napcat.MessageSegment
import top.bilibili.utils.ImageCache
import java.io.File
import java.net.URI

object LoginService {
    /**
     * 执行 BiliBili QR 码登录
     * @param isGroup 是否为群聊（true: 群聊, false: 私聊）
     * @param contactId 联系人 ID（群号或用户 QQ 号）
     */
    suspend fun login(isGroup: Boolean, contactId: Long) {
        BusinessLifecycleManager.run(
            owner = "LoginService",
            operation = "login:${if (isGroup) "group" else "private"}:$contactId",
            strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
        ) {
            logger.info("开始 BiliBili QR 码登录流程，联系人: $contactId")

            var qrImageFile: File? = null
            onFinally {
                runCatching { qrImageFile?.delete() }
                    .onFailure { logger.warn("删除二维码临时文件失败: ${it.message}") }
            }

            try {
                // 1. 获取登录二维码
                logger.info("正在获取登录二维码...")
                val loginData = client.getLoginQrcode()
                if (loginData == null) {
                    sendMessage(isGroup, contactId, "获取登录二维码失败")
                    logger.error("获取登录二维码失败")
                    return@run
                }
                logger.info("成功获取登录二维码: ${loginData.qrcodeKey}")

                // 2. 生成二维码图片
                logger.info("正在生成二维码图片...")
                val qrImage = loginQrCode(loginData.url)
                logger.info("二维码图片生成成功")

                logger.info("正在保存二维码到临时文件...")
                qrImageFile = File(BiliBiliBot.tempPath.toFile(), "bili_qr_${System.currentTimeMillis()}.png").apply {
                    deleteOnExit()
                    val data = qrImage.encodeToData(EncodedImageFormat.PNG)
                    try {
                        writeBytes(data?.bytes ?: byteArrayOf())
                    } finally {
                        data?.close()
                    }
                }
                // 关闭二维码 Image
                qrImage.close()
                logger.info("二维码已保存到: ${qrImageFile.absolutePath}")

                val qrImageUrl = ImageCache.toFileUrl(qrImageFile.absolutePath)
                logger.info("二维码文件 URL: $qrImageUrl")

                // 3. 发送二维码和提示消息
                logger.info("正在发送二维码到联系人...")
                sendMessage(
                    isGroup,
                    contactId,
                    listOf(
                        MessageSegment.text("请使用 BiliBili 手机 APP 扫码登录（3 分钟有效）"),
                        MessageSegment.image(qrImageUrl)
                    )
                )
                logger.info("二维码已发送")

                // 4. 轮询登录状态
                logger.info("开始轮询登录状态...")
                runCatching {
                    withTimeout(180000) { // 3 分钟超时
                        while (isActive) {
                            delay(3000) // 每 3 秒检查一次
                            val loginInfo = client.loginInfo(loginData.qrcodeKey!!)

                            when (loginInfo?.code) {
                                0 -> {
                                    // 登录成功
                                    val cookie = extractCookie(loginInfo.url!!)
                                    if (cookie.isNotEmpty()) {
                                        BiliConfigManager.config.accountConfig.cookie = cookie
                                        BiliConfigManager.saveConfig()
                                        BiliBiliBot.cookie.parse(cookie)
                                        initTagid()
                                        sendMessage(isGroup, contactId, "BiliBili 登录成功！")
                                        logger.info("BiliBili 登录成功")
                                    } else {
                                        sendMessage(isGroup, contactId, "Cookie 解析失败")
                                        logger.error("Cookie 解析失败")
                                    }
                                    break
                                }
                                86038 -> {
                                    // 二维码已失效
                                    sendMessage(isGroup, contactId, "二维码已失效，请重新登录")
                                    logger.warn("二维码已失效")
                                    break
                                }
                                86090 -> {
                                    // 已扫码未确认
                                    logger.debug("二维码已扫描，等待确认...")
                                }
                                86101 -> {
                                    // 未扫码
                                    logger.debug("等待扫码...")
                                }
                                else -> {
                                    logger.warn("登录状态未知: ${loginInfo?.code} - ${loginInfo?.message}")
                                }
                            }
                        }
                    }
                }.onFailure {
                    val userMessage = if (it is TimeoutCancellationException) {
                        "登录超时，请重新发送 /login"
                    } else {
                        "登录失败，请稍后重试"
                    }
                    sendMessage(isGroup, contactId, userMessage)
                    logger.error("登录失败", it)
                }
            } catch (e: Exception) {
                logger.error("登录流程发生异常", e)
                sendMessage(isGroup, contactId, "登录过程出错，请稍后重试")
            } finally {
                // 双重兜底：确保临时文件在业务结束后释放
                qrImageFile?.delete()
            }
        }
    }

    /**
     * 从登录回调 URL 中提取 Cookie
     */
    private fun extractCookie(url: String): String {
        return try {
            val querys = URI(url).query.split("&")
            buildString {
                querys.forEach { param ->
                    if (param.contains("SESSDATA") || param.contains("bili_jct")) {
                        append("${param.replace(",", "%2C").replace("*", "%2A")}; ")
                    }
                }
            }.trim()
        } catch (e: Exception) {
            logger.error("提取 Cookie 失败", e)
            ""
        }
    }

    /**
     * 发送消息到指定联系人
     */
    private suspend fun sendMessage(isGroup: Boolean, contactId: Long, message: String) {
        sendMessage(isGroup, contactId, listOf(MessageSegment.text(message)))
    }

    /**
     * 发送消息到指定联系人（支持多段消息）
     */
    private suspend fun sendMessage(isGroup: Boolean, contactId: Long, message: List<MessageSegment>) {
        try {
            if (isGroup) {
                BiliBiliBot.napCat.sendGroupMessage(contactId, message)
            } else {
                BiliBiliBot.napCat.sendPrivateMessage(contactId, message)
            }
        } catch (e: Exception) {
            logger.error("发送消息失败", e)
        }
    }
}
