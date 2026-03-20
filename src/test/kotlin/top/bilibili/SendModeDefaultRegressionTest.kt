package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendModeDefaultRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `napcat config default send mode should be base64`() {
        val source = read("src/main/kotlin/top/bilibili/config/NapCatConfig.kt")

        assertTrue(
            source.contains("""val sendMode: String = "base64""""),
            "NapCatConfig default send mode should be base64",
        )
        assertFalse(
            source.contains("""val sendMode: String = "file""""),
            "NapCatConfig should no longer default send mode to file",
        )
    }

    @Test
    fun `readme examples should show base64 send mode as the default`() {
        val readme = read("README.md")

        assertTrue(
            readme.contains("""send_mode: "base64""""),
            "README should show base64 send_mode examples",
        )
        assertFalse(
            readme.contains("""send_mode: "file"  # 图片发送模式：file 或 base64"""),
            "README should not keep file as the example send_mode default",
        )
        assertFalse(
            readme.contains("""send_mode: "file"          # 图片发送模式：file 或 base64"""),
            "README should not keep file as the detailed config example default",
        )
    }
}
