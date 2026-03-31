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
}
