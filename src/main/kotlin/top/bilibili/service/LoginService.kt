package top.bilibili.service

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import top.bilibili.BiliConfigManager
import top.bilibili.api.getLoginQrcode
import top.bilibili.api.loginInfo
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformContact
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.BusinessLifecycleManager
import top.bilibili.core.resource.ResourceStrictness
import top.bilibili.draw.loginQrCode
import top.bilibili.initTagid
import top.bilibili.utils.toSubject
import java.io.File
import java.net.URI

object LoginService {
    suspend fun login(contact: PlatformContact) {
        BusinessLifecycleManager.run(
            owner = "LoginService",
            operation = "login:${contact.toSubject()}",
            strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
        ) {
            logger.info("开始 BiliBili QR 码登录流程，联系人 ${contact.toSubject()}")

            var qrImageFile: File? = null
            onFinally {
                runCatching { qrImageFile?.delete() }
                    .onFailure { logger.warn("删除二维码临时文件失败: ${it.message}") }
            }

            try {
                val loginData = client.getLoginQrcode()
                if (loginData == null) {
                    sendMessage(contact, "获取登录二维码失败")
                    logger.error("获取登录二维码失败")
                    return@run
                }

                val generatedQrImageFile = loginQrCode(loginData.url).use { qrImage ->
                    File(BiliBiliBot.tempPath.toFile(), "bili_qr_${System.currentTimeMillis()}.png").apply {
                        deleteOnExit()
                        val data = qrImage.encodeToData(EncodedImageFormat.PNG)
                        try {
                            writeBytes(data?.bytes ?: byteArrayOf())
                        } finally {
                            data?.close()
                        }
                    }
                }
                qrImageFile = generatedQrImageFile

                sendPartsWithCapabilityFallback(
                    contact,
                    listOf(
                        OutgoingPart.text("请使用 BiliBili 手机 APP 扫码登录（3 分钟有效）"),
                        OutgoingPart.image(generatedQrImageFile.absolutePath),
                    ),
                    fallbackText = buildString {
                        appendLine("当前平台不支持直接发送登录二维码图片。")
                        appendLine("请复制下面的二维码链接到浏览器打开后完成扫码登录：")
                        append(loginData.url)
                    },
                )

                runCatching {
                    withTimeout(180_000) {
                        while (isActive) {
                            delay(3_000)
                            val loginInfo = client.loginInfo(loginData.qrcodeKey!!)

                            when (loginInfo?.code) {
                                0 -> {
                                    val cookie = extractCookie(loginInfo.url!!)
                                    if (cookie.isNotEmpty()) {
                                        BiliConfigManager.config.accountConfig.cookie = cookie
                                        BiliConfigManager.saveConfig()
                                        BiliBiliBot.cookie.parse(cookie)
                                        initTagid()
                                        sendMessage(contact, "BiliBili 登录成功")
                                        logger.info("BiliBili 登录成功")
                                    } else {
                                        sendMessage(contact, "Cookie 解析失败")
                                        logger.error("Cookie 解析失败")
                                    }
                                    break
                                }

                                86038 -> {
                                    sendMessage(contact, "二维码已失效，请重新登录")
                                    logger.warn("二维码已失效")
                                    break
                                }

                                86090 -> logger.debug("二维码已扫描，等待确认...")
                                86101 -> logger.debug("等待扫码...")
                                else -> logger.warn("登录状态未知: ${loginInfo?.code} - ${loginInfo?.message}")
                            }
                        }
                    }
                }.onFailure {
                    val userMessage = if (it is TimeoutCancellationException) {
                        "登录超时，请重新发送 /login"
                    } else {
                        "登录失败，请稍后重试"
                    }
                    sendMessage(contact, userMessage)
                    logger.error("登录失败", it)
                }
            } catch (e: Exception) {
                logger.error("登录流程发生异常", e)
                sendMessage(contact, "登录过程出错，请稍后重试")
            } finally {
                qrImageFile?.delete()
            }
        }
    }

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

    private suspend fun sendMessage(contact: PlatformContact, message: String) {
        sendMessage(contact, listOf(OutgoingPart.text(message)))
    }

    private suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>) {
        try {
            MessageGatewayProvider.require().sendMessage(contact, message)
        } catch (e: Exception) {
            logger.error("发送消息失败", e)
        }
    }
}
