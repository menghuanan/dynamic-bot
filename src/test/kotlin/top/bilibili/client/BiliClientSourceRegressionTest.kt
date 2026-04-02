package top.bilibili.client

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BiliClientSourceRegressionTest {
    private fun read(path: String): String {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8)
    }

    @Test
    fun `bili client should use structured retry log builders`() {
        val text = read("src/main/kotlin/top/bilibili/client/BiliClient.kt")
        assertTrue(text.contains("buildRetryLogMessage("))
        assertTrue(text.contains("buildRetryExhaustedLogMessage("))
    }

    @Test
    fun `dynamic and live polling should pass source markers into api wrappers`() {
        val dynamicTasker = read("src/main/kotlin/top/bilibili/tasker/DynamicCheckTasker.kt")
        val liveTasker = read("src/main/kotlin/top/bilibili/tasker/LiveCheckTasker.kt")

        assertTrue(dynamicTasker.contains("source = \"DynamicCheckTasker.poll\""))
        assertTrue(dynamicTasker.contains("source = \"DynamicCheckTasker.manual-check\""))
        assertTrue(liveTasker.contains("source = \"LiveCheckTasker.followed-live-list\""))
        assertFalse(liveTasker.contains("source = \"LiveCheckTasker.subscribed-live-status\""))
        assertFalse(liveTasker.contains("getLiveStatus("))
        assertFalse(liveTasker.contains("allLiveRooms"))
        assertFalse(liveTasker.contains("Instant.now().epochSecond - 600"))
    }

    // 轮询客户端只会在健康请求路径上长期使用一个底层实例，回归测试应阻止再次恢复成 eager 创建 3 个 OkHttp 客户端。
    @Test
    fun `bili client should create retry http clients lazily`() {
        val text = read("src/main/kotlin/top/bilibili/client/BiliClient.kt")

        assertFalse(
            text.contains("MutableList(3) { client() }"),
            "BiliClient should not eagerly allocate all retry HttpClient instances",
        )
        assertTrue(
            text.contains("getOrCreateClient("),
            "BiliClient should expose a lazy client factory for retry slots",
        )
    }

    // 全局工具客户端应延迟到真正访问相关 API 时再初始化，避免仅启动轮询链路就额外常驻一组 OkHttp 资源。
    @Test
    fun `general utility bili client should be lazy`() {
        val text = read("src/main/kotlin/top/bilibili/utils/General.kt")

        assertTrue(
            text.contains("val biliClient by lazy"),
            "General.kt should lazily initialize the shared biliClient",
        )
    }

    // 运行期排查需要直接看到已创建实例数、retry 槽位数和底层 OkHttp 连接池状态。
    @Test
    fun `bili client should expose runtime observability snapshot`() {
        val text = read("src/main/kotlin/top/bilibili/client/BiliClient.kt")

        assertTrue(
            text.contains("runtimeSnapshot("),
            "BiliClient should expose a runtime snapshot entry point for ProcessGuardian",
        )
        assertTrue(
            text.contains("ConnectionPool"),
            "BiliClient runtime snapshot should inspect OkHttp connection pools",
        )
        assertTrue(
            text.contains("queuedCallsCount") && text.contains("runningCallsCount"),
            "BiliClient runtime snapshot should include dispatcher queue state",
        )
    }

    // 长时 RSS 漂移治理需要收紧轮询客户端的连接池与调度并发，避免过多空闲连接常驻。
    @Test
    fun `bili client should tighten okhttp connection pool and dispatcher bounds`() {
        val text = read("src/main/kotlin/top/bilibili/client/BiliClient.kt")

        assertTrue(
            text.contains("RETRY_POOL_MAX_IDLE_CONNECTIONS = 2"),
            "BiliClient should cap idle connections to 2 per retry slot",
        )
        assertTrue(
            text.contains("RETRY_POOL_KEEP_ALIVE_MINUTES = 1"),
            "BiliClient should reduce keep-alive duration to 1 minute",
        )
        assertTrue(
            text.contains("RETRY_DISPATCHER_MAX_REQUESTS = 16") &&
                text.contains("maxRequests = RETRY_DISPATCHER_MAX_REQUESTS"),
            "BiliClient should cap global dispatcher parallelism",
        )
        assertTrue(
            text.contains("RETRY_DISPATCHER_MAX_REQUESTS_PER_HOST = 4") &&
                text.contains("maxRequestsPerHost = RETRY_DISPATCHER_MAX_REQUESTS_PER_HOST"),
            "BiliClient should cap per-host dispatcher parallelism",
        )
    }

    // 超时场景不应无条件轮换到底层 retry 槽位，否则会把偶发网络抖动放大成额外常驻客户端资源。
    @Test
    fun `bili client retry path should avoid slot rotation on timeout errors`() {
        val text = read("src/main/kotlin/top/bilibili/client/BiliClient.kt")

        assertTrue(
            text.contains("shouldRotateRetrySlot"),
            "BiliClient should centralize retry slot rotation policy",
        )
        assertTrue(
            text.contains("HttpRequestTimeoutException") && text.contains("SocketTimeoutException"),
            "BiliClient retry policy should explicitly classify timeout errors",
        )
    }
}
