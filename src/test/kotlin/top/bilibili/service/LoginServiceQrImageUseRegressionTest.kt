package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class LoginServiceQrImageUseRegressionTest {
    // 统一按 UTF-8 读取源码，避免 source regression 受宿主默认编码影响。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `login service should render qr code bytes before writing temp file and sending local image`() {
        val source = read("src/main/kotlin/top/bilibili/service/LoginService.kt")

        // 二维码绘制必须先产出 PNG 字节，后续 temp 文件落盘与发送都应复用这一份结果。
        assertTrue(
            source.contains("val qrImageBytes = loginQrCodeBytes(loginData.url)"),
            "login service should render qr code bytes before persisting and sending the login image",
        )
        // temp 目录应继续作为登录二维码的统一落盘位置，避免在业务层四处分散临时文件策略。
        assertTrue(
            source.contains("BiliBiliBot.tempPath"),
            "login service should persist the rendered qr code under the shared temp directory",
        )
        // 图片能力降级入口仍应保留在发送前，保证 QQ Official 等平台继续走文本回退。
        assertTrue(
            source.contains("sendPartsWithCapabilityFallback("),
            "login service should keep using the capability-aware image send helper",
        )
    }
}
