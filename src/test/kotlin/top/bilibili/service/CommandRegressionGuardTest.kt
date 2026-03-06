package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandRegressionGuardTest {
    private fun read(path: String): String {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8)
    }

    @Test
    fun `command-facing text should not contain mojibake markers`() {
        val files = listOf(
            "src/main/kotlin/top/bilibili/service/MessageCommandRouterService.kt",
            "src/main/kotlin/top/bilibili/service/BiliCommandDispatchService.kt",
            "src/main/kotlin/top/bilibili/service/FirstRunService.kt",
            "src/main/kotlin/top/bilibili/service/PresentationCommandService.kt",
        )

        val mojibakeMarkers = listOf("��", "锟", "�")
        files.forEach { file ->
            val text = read(file)
            mojibakeMarkers.forEach { marker ->
                assertFalse(text.contains(marker), "mojibake marker '$marker' found in $file")
            }
        }
    }

    @Test
    fun `check route should keep expected Chinese feedback`() {
        val text = read("src/main/kotlin/top/bilibili/service/MessageCommandRouterService.kt")
        assertTrue(text.contains("正在检查动态..."))
        assertTrue(text.contains("检查完成，检测到"))
        assertTrue(text.contains("检查完成，没有新动态"))
        assertTrue(text.contains("检查失败:"))
    }

    @Test
    fun `quick command branches should all exist in router`() {
        val text = read("src/main/kotlin/top/bilibili/service/MessageCommandRouterService.kt")
        val commands = listOf(
            "/login",
            "/add ",
            "/del ",
            "/list",
            "/black list",
            "/black ",
            "/unblock ",
            "/check",
            "/bili ",
        )
        commands.forEach { cmd ->
            assertTrue(text.contains(cmd), "missing quick command branch: $cmd")
        }
    }

    @Test
    fun `quick subscribe and unsubscribe should delegate to dynamic service`() {
        val text = read("src/main/kotlin/top/bilibili/service/QuickSubscriptionService.kt")
        assertTrue(text.contains("DynamicService.addSubscribe"))
        assertTrue(text.contains("DynamicService.removeSubscribe"))
    }

    @Test
    fun `template command should provide explain branch for operator guidance`() {
        val text = read("src/main/kotlin/top/bilibili/service/PresentationCommandService.kt")
        assertTrue(text.contains("\"explain\""), "template explain branch should exist")
    }

    @Test
    fun `send path should inject napcat at-all segment when policy matches`() {
        val text = read("src/main/kotlin/top/bilibili/tasker/SendTasker.kt")
        assertTrue(text.contains("MessageSegment.atAll()"), "send path should support real at-all segment injection")
        assertTrue(text.contains("canAtAllInGroup"), "send path should verify group at-all permission before injecting segment")
        assertTrue(text.contains("filterNot { it.type == \"at\""), "send path should retry downgrade without at-all segment on failure")
    }

    @Test
    fun `config command should parse optional uid argument directly`() {
        val text = read("src/main/kotlin/top/bilibili/service/SettingsCommandService.kt")
        assertTrue(
            text.contains("parseUidArg(args[1])"),
            "config uid should be validated from the first argument after config"
        )
    }

    @Test
    fun `color command should be wired through processor and dispatcher`() {
        val processor = read("src/main/kotlin/top/bilibili/core/BiliCommandProcessor.kt")
        val dispatcher = read("src/main/kotlin/top/bilibili/service/BiliCommandDispatchService.kt")
        val settings = read("src/main/kotlin/top/bilibili/service/SettingsCommandService.kt")
        assertTrue(
            processor.contains("\"color\" -> executor.color()"),
            "processor should route /bili color to executor.color()"
        )
        assertTrue(
            dispatcher.contains("override suspend fun color() = SettingsCommandService.handleColor"),
            "dispatcher should delegate color command to settings service"
        )
        assertTrue(
            settings.contains("suspend fun handleColor("),
            "settings service should provide handleColor entrypoint"
        )
    }

    @Test
    fun `config command should provide color editing usage`() {
        val text = read("src/main/kotlin/top/bilibili/service/SettingsCommandService.kt")
        assertTrue(
            text.contains("/bili config color <uid|用户名> <HEX颜色>"),
            "config usage should include color editing command"
        )
    }

    @Test
    fun `help text should mention color command`() {
        val text = read("src/main/kotlin/top/bilibili/service/PresentationCommandService.kt")
        assertTrue(
            text.contains("/bili color <uid|用户名> <HEX颜色>"),
            "help should include color command guidance"
        )
    }

    @Test
    fun `color command should notify admin when saveData fails`() {
        val text = read("src/main/kotlin/top/bilibili/service/SettingsCommandService.kt")
        assertTrue(
            text.contains("val saved = BiliConfigManager.saveData()"),
            "color command should inspect saveData result"
        )
        assertTrue(
            text.contains("if (!saved)"),
            "color command should branch on save failure"
        )
        assertTrue(
            text.contains("actionNotify("),
            "color command should notify admin when persistence fails"
        )
    }

    @Test
    fun `napcat send path should await api response instead of queue-only success`() {
        val text = read("src/main/kotlin/top/bilibili/napcat/NapCatClient.kt")
        assertTrue(text.contains("sendActionAndAwaitResponse"), "send path should wait for OneBot response")
        assertTrue(text.contains("response.retcode"), "send path should check retcode before declaring success")
    }
}
