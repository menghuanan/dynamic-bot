package top.bilibili.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoginCallbackParsingRegressionTest {
    @Test
    fun `login callback parser should extract sessdata bili jct and optional dedeuserid`() {
        val result = LoginService.parseLoginCallback(
            "https://passport.bilibili.com/x/passport-login/web/crossDomain?SESSDATA=sess%2Cvalue*bili&bili_jct=csrf_token&DedeUserID=123456&gourl=https%3A%2F%2Fwww.bilibili.com",
        )

        // 登录回调里的核心 cookie 字段必须保持当前 URL query 解析规则，并继续做 SESSDATA 特殊字符转义。
        assertEquals("SESSDATA=sess%2Cvalue%2Abili; bili_jct=csrf_token;", result.cookie)
        // DedeUserID 只允许来自现有回调 URL 载荷，避免为刷新身份额外补发 userInfo 请求。
        assertEquals("123456", result.dedeUserId)
    }

    @Test
    fun `login callback parser should tolerate missing dedeuserid`() {
        val result = LoginService.parseLoginCallback(
            "https://passport.bilibili.com/x/passport-login/web/crossDomain?SESSDATA=sess%2Cvalue&bili_jct=csrf_token",
        )

        // 缺少 DedeUserID 时仍应返回可用 cookie，保证旧载荷形态不会被新解析逻辑误判为失败。
        assertEquals("SESSDATA=sess%2Cvalue; bili_jct=csrf_token;", result.cookie)
        assertNull(result.dedeUserId)
    }
}
