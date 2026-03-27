package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectorBoundaryRegressionTest {
    // 使用 UTF-8 读取源码，确保不同 Windows 环境下的断言结果稳定。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    // 只扫描业务层目录，防止 connector 自身对 vendor 依赖被误报为边界泄漏。
    private fun kotlinSourcesUnder(path: String): List<Path> {
        return Files.walk(Path.of(path)).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .toList()
        }
    }

    @Test
    fun `business service and tasker code should not reference vendor clients or concrete adapters`() {
        val forbiddenMarkers = listOf(
            "NapCatClient",
            "NapCatAdapter",
            "GenericOneBot11Adapter",
            "QQOfficialAdapter",
        )

        kotlinSourcesUnder("src/main/kotlin/top/bilibili/service")
            .plus(kotlinSourcesUnder("src/main/kotlin/top/bilibili/tasker"))
            .forEach { file ->
                val source = Files.readString(file, StandardCharsets.UTF_8)
                forbiddenMarkers.forEach { marker ->
                    assertFalse(source.contains(marker), "${file.toString()} should not reference $marker")
                }
            }
    }

    @Test
    fun `message gateway implementation naming should stay platform neutral`() {
        assertFalse(Files.exists(Path.of("src/main/kotlin/top/bilibili/service/NapCatMessageGateway.kt")))
        assertTrue(Files.exists(Path.of("src/main/kotlin/top/bilibili/service/DefaultMessageGateway.kt")))

        val botSource = read("src/main/kotlin/top/bilibili/core/BiliBiliBot.kt")
        assertFalse(botSource.contains("NapCatMessageGateway("))
        assertTrue(botSource.contains("DefaultMessageGateway("))
    }

    @Test
    fun `bot should not expose vendor specific runtime accessors`() {
        val botSource = read("src/main/kotlin/top/bilibili/core/BiliBiliBot.kt")

        assertFalse(botSource.contains("fun requireNapCat("))
        // 运行期边界必须收口到 connector manager，业务层不能再取到 raw adapter。
        assertFalse(botSource.contains("val platformAdapter"))
        assertFalse(botSource.contains("fun requirePlatformAdapter("))
    }
}
