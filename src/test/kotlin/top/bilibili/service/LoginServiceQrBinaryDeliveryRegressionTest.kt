package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class LoginServiceQrBinaryDeliveryRegressionTest {
    // 统一按 UTF-8 读取源码，避免 source regression 受宿主默认编码影响。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `login service should deliver qr code through binary image payload`() {
        val source = read("src/main/kotlin/top/bilibili/service/LoginService.kt")

        assertTrue(
            source.contains("ImageSource.Binary("),
            "login service should send qr code as binary image payload instead of a temp file path",
        )
    }
}
