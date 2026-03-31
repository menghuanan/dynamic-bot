package top.bilibili.connector

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformTransportObservabilitySourceRegressionTest {
    // 平台层源码回归统一按 UTF-8 读取，避免跨平台默认编码导致关键字匹配失真。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `platform connector contracts should expose dedicated transport observability snapshots`() {
        val models = read("src/main/kotlin/top/bilibili/connector/PlatformModels.kt")
        val adapter = read("src/main/kotlin/top/bilibili/connector/PlatformAdapter.kt")
        val manager = read("src/main/kotlin/top/bilibili/connector/PlatformConnectorManager.kt")
        val oneBotTransport = read("src/main/kotlin/top/bilibili/connector/onebot11/core/OneBot11Transport.kt")
        val oneBotAdapter = read("src/main/kotlin/top/bilibili/connector/onebot11/OneBot11Adapter.kt")

        assertTrue(
            models.contains("data class PlatformHttpClientSnapshot"),
            "PlatformModels should define a dedicated transport HttpClient snapshot model",
        )
        assertTrue(
            models.contains("data class PlatformObservabilitySnapshot"),
            "PlatformModels should define a platform observability snapshot distinct from PlatformRuntimeStatus",
        )
        assertTrue(
            adapter.contains("fun runtimeObservability(): PlatformObservabilitySnapshot"),
            "PlatformAdapter should expose a runtimeObservability entry point",
        )
        assertTrue(
            manager.contains("fun runtimeObservability(): PlatformObservabilitySnapshot"),
            "PlatformConnectorManager should expose a runtimeObservability entry point",
        )
        assertTrue(
            oneBotTransport.contains("fun runtimeObservability(): PlatformObservabilitySnapshot"),
            "OneBot11Transport should expose transport runtime observability",
        )
        assertTrue(
            oneBotAdapter.contains("override fun runtimeObservability(): PlatformObservabilitySnapshot"),
            "OneBot11Adapter should pass through transport runtime observability",
        )
        assertTrue(
            oneBotAdapter.contains("transport.runtimeObservability()"),
            "OneBot11Adapter should delegate runtime observability to the underlying transport",
        )
    }

    @Test
    fun `platform transports should hold explicit okhttp resources and expose runtime snapshots`() {
        val genericTransport = read("src/main/kotlin/top/bilibili/connector/onebot11/core/KtorOneBot11Transport.kt")
        val napCatClient = read("src/main/kotlin/top/bilibili/connector/onebot11/vendors/napcat/NapCatClient.kt")
        val napCatAdapter = read("src/main/kotlin/top/bilibili/connector/onebot11/vendors/napcat/NapCatAdapter.kt")
        val llBotTransport = read("src/main/kotlin/top/bilibili/connector/onebot11/vendors/llbot/LlBotTransport.kt")
        val llBotClient = read("src/main/kotlin/top/bilibili/connector/onebot11/vendors/llbot/LlBotClient.kt")
        val qqOfficialTransport = read("src/main/kotlin/top/bilibili/connector/qqofficial/QQOfficialTransport.kt")

        assertTrue(genericTransport.contains("private val connectionPool = ConnectionPool("))
        assertTrue(
            genericTransport.contains("private val dispatcher = Dispatcher()") ||
                genericTransport.contains("private val okHttpDispatcher = Dispatcher()"),
        )
        assertTrue(genericTransport.contains("override fun runtimeObservability(): PlatformObservabilitySnapshot"))
        assertFalse(
            genericTransport.contains("connectionPool(\n                    ConnectionPool(") ||
                genericTransport.contains("connectionPool(ConnectionPool("),
            "generic transport should not inline anonymous ConnectionPool instances inside HttpClient config",
        )

        assertTrue(napCatClient.contains("private val connectionPool = ConnectionPool("))
        assertTrue(
            napCatClient.contains("private val dispatcher = Dispatcher()") ||
                napCatClient.contains("private val okHttpDispatcher = Dispatcher()"),
        )
        assertTrue(napCatAdapter.contains("override fun runtimeObservability(): PlatformObservabilitySnapshot"))
        assertFalse(
            napCatClient.contains("connectionPool(ConnectionPool("),
            "NapCat client should not inline anonymous ConnectionPool instances inside HttpClient config",
        )

        assertTrue(llBotTransport.contains("private val connectionPool = ConnectionPool("))
        assertTrue(
            llBotTransport.contains("private val dispatcher = Dispatcher()") ||
                llBotTransport.contains("private val okHttpDispatcher = Dispatcher()"),
        )
        assertTrue(llBotTransport.contains("fun runtimeObservability(): PlatformObservabilitySnapshot"))
        assertTrue(llBotClient.contains("override fun runtimeObservability(): PlatformObservabilitySnapshot"))
        assertFalse(
            llBotTransport.contains("connectionPool(\n                    ConnectionPool(") ||
                llBotTransport.contains("connectionPool(ConnectionPool("),
            "llbot transport should not inline anonymous ConnectionPool instances inside HttpClient config",
        )

        assertTrue(qqOfficialTransport.contains("private val connectionPool = ConnectionPool("))
        assertTrue(
            qqOfficialTransport.contains("private val dispatcher = Dispatcher()") ||
                qqOfficialTransport.contains("private val okHttpDispatcher = Dispatcher()"),
        )
        assertTrue(qqOfficialTransport.contains("fun runtimeObservability(): PlatformObservabilitySnapshot"))
        assertTrue(
            qqOfficialTransport.contains("private val transportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())"),
            "QQ Official transport should keep a single transport scope for gateway sessions",
        )
        assertFalse(
            qqOfficialTransport.contains("connectionPool(\n                    ConnectionPool(") ||
                qqOfficialTransport.contains("connectionPool(ConnectionPool("),
            "QQ Official transport should not inline anonymous ConnectionPool instances inside HttpClient config",
        )
    }
}
