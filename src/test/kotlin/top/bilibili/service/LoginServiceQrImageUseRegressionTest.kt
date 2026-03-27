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
    fun `login service should close qr image through use block`() {
        val source = read("src/main/kotlin/top/bilibili/service/LoginService.kt")

        assertTrue(
            source.contains("loginQrCode(loginData.url).use { qrImage ->"),
            "login service should wrap the drawToImage result with use so Image always closes",
        )
    }
}
