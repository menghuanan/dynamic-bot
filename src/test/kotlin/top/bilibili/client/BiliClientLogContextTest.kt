package top.bilibili.client

import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BiliClientLogContextTest {
    @Test
    fun `retry log should include caller and api context`() {
        val trace = ApiRequestTrace(
            source = "DynamicCheckTasker.poll",
            api = "NEW_DYNAMIC",
            url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all"
        )

        val message = buildRetryLogMessage(
            trace = trace,
            retryNumber = 1,
            maxAttempts = 2,
            clientIndex = 0,
            proxyEnabled = false,
            throwable = HttpRequestTimeoutException(
                "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all",
                10_000,
                cause = null
            )
        )

        assertTrue(message.contains("任务=动态轮询"))
        assertTrue(message.contains("接口=动态列表"))
        assertTrue(message.contains("异常=请求超时"))
        assertTrue(message.contains("原因=10秒内未收到响应"))
        assertFalse(message.contains("url="))
        assertFalse(message.contains("clientIndex="))
        assertFalse(message.contains("proxyEnabled="))
    }

    @Test
    fun `retry exhausted log should include caller and api context`() {
        val trace = ApiRequestTrace(
            source = "LiveCheckTasker.followed-live-list",
            api = "LIVE_LIST",
            url = "https://api.live.bilibili.com/xlive/web-ucenter/v1/xfetter/GetWebList"
        )

        val message = buildRetryExhaustedLogMessage(
            trace = trace,
            attemptsUsed = 2,
            maxAttempts = 2,
            clientIndex = 1,
            proxyEnabled = true,
            throwable = HttpRequestTimeoutException(
                "https://api.live.bilibili.com/xlive/web-ucenter/v1/xfetter/GetWebList",
                10_000,
                cause = null
            )
        )

        assertTrue(message.contains("任务=直播轮询(关注列表)"))
        assertTrue(message.contains("接口=关注直播列表"))
        assertTrue(message.contains("重试=2/2"))
        assertTrue(message.contains("异常=请求超时"))
        assertTrue(message.contains("原因=10秒内未收到响应"))
        assertFalse(message.contains("url="))
        assertFalse(message.contains("clientIndex="))
        assertFalse(message.contains("proxyEnabled="))
    }
}
