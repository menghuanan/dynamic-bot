package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginServiceQrBinaryDeliveryRegressionTest {
    // 统一按 UTF-8 读取源码，避免 source regression 受宿主默认编码影响。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `login service should persist qr bytes and send local file image payload`() {
        val source = read("src/main/kotlin/top/bilibili/service/LoginService.kt")

        // 登录二维码应先写入 temp 目录，再以本地文件形式交给发送链路，避免业务层继续内嵌二进制直发语义。
        assertTrue(
            source.contains("createLoginQrTempFile(qrImageFileName, qrImageBytes)"),
            "login service should persist qr bytes through the shared temp-file helper before sending",
        )
        // 发送入口必须改用本地文件源，确保后续 base64 转码责任停留在 OneBot11 适配器。
        assertTrue(
            source.contains("ImageSource.LocalFile("),
            "login service should send qr code as a local file payload after persisting it",
        )
        // 旧的二进制发送路径必须消失，避免同一流程并存两种 transport 责任划分。
        assertFalse(
            source.contains("ImageSource.Binary("),
            "login service should no longer send qr code as a binary image payload",
        )
    }
}
