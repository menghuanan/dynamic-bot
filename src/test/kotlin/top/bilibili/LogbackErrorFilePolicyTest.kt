package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class LogbackErrorFilePolicyTest {
    // 统一以 UTF-8 读取资源配置，避免平台默认编码影响断言结果。
    private fun read(path: String): String =
        Files.readString(Path.of(path), StandardCharsets.UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n')

    @Test
    fun `error file appender should persist warn and above logs`() {
        // 锁定 ERROR_FILE appender 的过滤策略，要求 WARN 及以上级别写入 error.log。
        val text = read("src/main/resources/logback.xml")

        assertTrue(
            text.contains("""<appender name="ERROR_FILE""""),
            "logback should define ERROR_FILE appender",
        )
        assertTrue(
            text.contains("""<file>${'$'}{LOG_DIR}/error.log</file>"""),
            "ERROR_FILE appender should still target error.log",
        )
        assertTrue(
            text.contains("""<filter class="ch.qos.logback.classic.filter.ThresholdFilter">"""),
            "ERROR_FILE appender should use ThresholdFilter so WARN and ERROR can both pass",
        )
        assertTrue(
            text.contains("<level>WARN</level>"),
            "ERROR_FILE appender threshold should be WARN",
        )
    }
}
