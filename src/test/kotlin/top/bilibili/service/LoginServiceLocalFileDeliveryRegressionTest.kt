package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginServiceLocalFileDeliveryRegressionTest {
    // 统一按 UTF-8 读取源码，避免 source regression 受宿主默认编码影响。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `login service should persist qr bytes and send local file without new user info request`() {
        val source = read("src/main/kotlin/top/bilibili/service/LoginService.kt")

        // /login 发送链路必须改为本地文件源，避免业务层继续直接承担适配器编码细节。
        assertTrue(
            source.contains("OutgoingPart.image(ImageSource.LocalFile("),
            "login service should send qr images through ImageSource.LocalFile after persisting temp files",
        )
        // 旧的二进制直发路径必须移除，确保回归测试能卡住错误的传输责任划分。
        assertFalse(
            source.contains("ImageSource.Binary("),
            "login service should no longer send qr images through ImageSource.Binary",
        )
        // 登录成功路径不得补发额外 userInfo 请求，身份信息只能来自现有登录回调载荷。
        assertFalse(
            source.contains("client.userInfo()"),
            "login success path should stay within the original Bilibili API envelope",
        )
    }
}
