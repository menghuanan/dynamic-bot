package top.bilibili.service

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsCommandServiceAtAllParsingTest {
    // 解析层需要同时兼容文档写法、旧 set 别名，以及用户直接输入类型的旧式快捷写法。
    @Test
    fun `parseAtAllCommandArgs 应兼容 add set 与直接类型写法`() {
        val cases = listOf(
            listOf("atall", "add", "all", "6194785") to SettingsCommandService.AtAllParsedCommand("add", "all", "6194785", "add"),
            listOf("atall", "set", "dynamic", "6194785") to SettingsCommandService.AtAllParsedCommand("add", "dynamic", "6194785", "set"),
            listOf("atall", "all", "6194785") to SettingsCommandService.AtAllParsedCommand("add", "all", "6194785", "all"),
            listOf("atall", "d", "6194785") to SettingsCommandService.AtAllParsedCommand("add", "d", "6194785", "d"),
            listOf("atall", "dynamic", "6194785") to SettingsCommandService.AtAllParsedCommand("add", "dynamic", "6194785", "dynamic"),
            listOf("atall", "list", "6194785") to SettingsCommandService.AtAllParsedCommand("list", null, "6194785", "list"),
            listOf("atall", "rm", "live", "6194785") to SettingsCommandService.AtAllParsedCommand("del", "live", "6194785", "rm"),
            listOf("atall", "unknown", "6194785") to SettingsCommandService.AtAllParsedCommand("unknown", null, null, "unknown"),
        )

        cases.forEach { (args, expected) ->
            assertEquals(expected, SettingsCommandService.parseAtAllCommandArgs(args), "args=$args")
        }
    }
}
