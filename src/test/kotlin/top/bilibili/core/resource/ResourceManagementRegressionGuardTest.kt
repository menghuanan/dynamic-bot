package top.bilibili.core.resource

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceManagementRegressionGuardTest {
    private fun read(path: String): String {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8)
    }

    @Test
    fun `listener and send taskers should not launch with bot global scope`() {
        val listener = read("src/main/kotlin/top/bilibili/tasker/ListenerTasker.kt")
        val send = read("src/main/kotlin/top/bilibili/tasker/SendTasker.kt")

        assertFalse(
            Regex("""BiliBiliBot\.launch\s*\{""").containsMatchIn(listener),
            "ListenerTasker should use task scope launch",
        )
        assertFalse(
            Regex("""BiliBiliBot\.launch\s*\{""").containsMatchIn(send),
            "SendTasker should use task scope launch",
        )
    }

    @Test
    fun `business lifecycle should enforce configured hard timeout`() {
        val manager = read("src/main/kotlin/top/bilibili/core/resource/BusinessLifecycleManager.kt")
        assertTrue(manager.contains("businessHardTimeoutMs"), "hard timeout config should be referenced in runtime path")
        assertTrue(manager.contains("withTimeout"), "hard timeout should be executed via withTimeout")
    }

    @Test
    fun `temporary json tool http client should be explicitly closed`() {
        val text = read("src/main/kotlin/top/bilibili/utils/Json2DataClass.kt")
        assertTrue(text.contains("client.close()"), "json2DataClass temporary HttpClient should be closed")
    }

    @Test
    fun `task resource strictness should be tightened for maintenance tasks`() {
        val policy = read("src/main/kotlin/top/bilibili/core/resource/TaskResourcePolicyRegistry.kt")
        assertTrue(
            policy.contains("\"CacheClearTasker\", ResourceStrictness.STRICT"),
            "CacheClearTasker should run under STRICT policy",
        )
        assertTrue(
            policy.contains("\"LogClearTasker\", ResourceStrictness.STRICT"),
            "LogClearTasker should run under STRICT policy",
        )
        assertTrue(
            policy.contains("\"SkiaCleanupTasker\", ResourceStrictness.STRICT"),
            "SkiaCleanupTasker should run under STRICT policy",
        )
        assertTrue(
            policy.contains("\"ProcessGuardian\", ResourceStrictness.STRICT"),
            "ProcessGuardian should run under STRICT policy",
        )
    }

    @Test
    fun `router async commands should run through managed lifecycle wrapper`() {
        val router = read("src/main/kotlin/top/bilibili/service/MessageCommandRouterService.kt")
        assertTrue(router.contains("private fun launchManaged("))
        assertTrue(router.contains("launchManaged(operation = \"group:/login:"))
        assertTrue(router.contains("launchManaged(operation = \"private:/login:"))
    }

    @Test
    fun `shutdown path should close all shared bili clients`() {
        val bot = read("src/main/kotlin/top/bilibili/core/BiliBiliBot.kt")

        assertTrue(
            bot.contains("closeServiceClient()"),
            "BiliBiliBot stop path should close service shared BiliClient",
        )
        assertTrue(
            bot.contains("BiliCheckTasker.closeSharedClient()"),
            "BiliBiliBot stop path should close BiliCheckTasker shared BiliClient",
        )
    }

    @Test
    fun `log clear tasker should include daemon logs cleanup`() {
        val tasker = read("src/main/kotlin/top/bilibili/tasker/LogClearTasker.kt")

        assertTrue(
            tasker.contains("logs/daemon"),
            "LogClearTasker should clean ProcessGuardian daemon log directory",
        )
        assertTrue(
            tasker.contains("Daemon_"),
            "LogClearTasker should match daemon log naming pattern",
        )
    }

    @Test
    fun `dynamic major draw should not rely on non-finally cover close`() {
        val draw = read("src/main/kotlin/top/bilibili/draw/DynamicMajorDraw.kt")

        assertFalse(
            draw.contains("coverImg.close()"),
            "DynamicMajorDraw should not use manual coverImg.close outside session tracking",
        )
        assertFalse(
            draw.contains("bgImage.close()"),
            "DynamicMajorDraw should not use manual bgImage.close outside session tracking",
        )
        assertFalse(
            draw.contains("lockIcon.close()"),
            "DynamicMajorDraw should not use manual lockIcon.close outside session tracking",
        )
    }

    @Test
    fun `font utils should deduplicate typeface registration`() {
        val fontUtils = read("src/main/kotlin/top/bilibili/utils/FontUtils.kt")

        assertTrue(
            fontUtils.contains("registeredTypefaceKeys"),
            "FontUtils should track registered typeface keys to avoid duplicate provider growth",
        )
        assertTrue(
            fontUtils.contains("registeredAliasKeys"),
            "FontUtils should track registered aliases to avoid duplicate alias registration",
        )
        assertTrue(
            Regex("""registeredTypefaceKeys\.add\(""").containsMatchIn(fontUtils),
            "FontUtils should gate base registration with registeredTypefaceKeys.add(...)",
        )
        assertTrue(
            Regex("""registeredAliasKeys\.add\(""").containsMatchIn(fontUtils),
            "FontUtils should gate alias registration with registeredAliasKeys.add(...)",
        )
    }

    @Test
    fun `font utils should close temporary skia data objects`() {
        val fontUtils = read("src/main/kotlin/top/bilibili/utils/FontUtils.kt")
        val closeCalls = Regex("""data\.close\(\)""").findAll(fontUtils).count()

        assertTrue(
            closeCalls >= 2,
            "FontUtils should close Data objects in both resource and direct-data loading paths",
        )
    }

    @Test
    fun `load fonts should be idempotent and only load font extensions`() {
        val init = read("src/main/kotlin/top/bilibili/Init.kt")

        assertTrue(
            init.contains("fontsInitialized"),
            "loadFonts should guard against repeated initialization in the same process",
        )
        assertTrue(
            init.contains("compareAndSet(false, true)"),
            "loadFonts should use atomic guard to prevent repeated font registration",
        )
        assertTrue(
            Regex("""extension\.lowercase\(\)\s*in\s*setOf\("ttf",\s*"otf",\s*"ttc"\)""").containsMatchIn(init),
            "loadFonts should ignore non-font files in data/font",
        )
    }
    @Test
    fun `load fonts should not auto download but should keep bundled fallback loading`() {
        val init = read("src/main/kotlin/top/bilibili/Init.kt")

        assertFalse(
            init.contains("URL("),
            "loadFonts should not fetch fonts over the network",
        )
        assertFalse(
            init.contains("ZipInputStream"),
            "loadFonts should not unzip downloaded font archives",
        )
        assertFalse(
            init.contains("下载字体失败"),
            "loadFonts should not retain download retry logic",
        )
        assertFalse(
            init.contains("LXGWWenKai-Bold.ttf"),
            "loadFonts should not special-case auto downloaded LXGW fonts",
        )
        assertTrue(
            init.contains("forEachDirectoryEntry"),
            "loadFonts should still scan data/font for user-provided overrides",
        )
        assertTrue(
            init.contains("loadTypefaceFromResource"),
            "loadFonts should still load bundled resource fonts as fallback",
        )
        assertTrue(
            init.contains("\"/font/SourceHanSansSC-Regular.otf\" to \"Source Han Sans SC\""),
            "loadFonts should still bundle Source Han Sans SC fallback",
        )
        assertTrue(
            init.contains("\"/font/FansCard.ttf\" to \"FansCard\""),
            "loadFonts should still bundle FansCard fallback",
        )
    }


    @Test
    fun `load svg should close temporary skia data buffer`() {
        val general = read("src/main/kotlin/top/bilibili/draw/General.kt")

        assertTrue(
            general.contains("Data.makeFromBytes"),
            "loadSVG should still construct SVGDOM from byte data",
        )
        assertTrue(
            general.contains("data.close()"),
            "loadSVG should close temporary Data object to prevent native buffer retention",
        )
    }

    @Test
    fun `svg dom instances should be explicitly closed in draw hot paths`() {
        val dynamicDraw = read("src/main/kotlin/top/bilibili/draw/DynamicDraw.kt")
        val dynamicModule = read("src/main/kotlin/top/bilibili/draw/DynamicModuleDraw.kt")
        val liveDraw = read("src/main/kotlin/top/bilibili/draw/LiveDraw.kt")
        val qrCodeDraw = read("src/main/kotlin/top/bilibili/draw/QrCodeDraw.kt")

        assertTrue(
            dynamicDraw.contains("svg.close()"),
            "DynamicDraw should close temporary SVGDOM instances",
        )
        assertTrue(
            dynamicModule.contains("svg.close()"),
            "DynamicModuleDraw should close temporary SVGDOM instances",
        )
        assertTrue(
            liveDraw.contains("svg.close()"),
            "LiveDraw should close temporary SVGDOM instances",
        )
        assertTrue(
            qrCodeDraw.contains("svg.close()"),
            "QrCodeDraw should close temporary SVGDOM instances",
        )
    }

    @Test
    fun `general image download path should guard invalid source urls`() {
        val general = read("src/main/kotlin/top/bilibili/utils/General.kt")

        assertTrue(
            general.contains("fun normalizeImageSourceUrl(url: String): String?"),
            "General should provide a reusable image source url normalizer",
        )
        assertTrue(
            general.contains("trimmed.startsWith(\"@\")"),
            "General should reject orphan image suffix urls like @880w_100h_1e_1c.png",
        )
        assertTrue(
            general.contains("val normalizedUrl = normalizeImageSourceUrl(url)"),
            "getOrDownload should normalize incoming url before network access",
        )
        assertTrue(
            general.contains("normalizedFallbackUrl = normalizeImageSourceUrl(fallbackUrl)"),
            "getOrDownloadImageDefault should normalize fallback url before download attempt",
        )
        assertTrue(
            general.contains("?: return \"\""),
            "imgApi should return empty fallback when base url is invalid",
        )
    }

    @Test
    fun `draw hot paths should resolve render settings via snapshot`() {
        val dynamicDraw = read("src/main/kotlin/top/bilibili/draw/DynamicDraw.kt")
        val dynamicMajor = read("src/main/kotlin/top/bilibili/draw/DynamicMajorDraw.kt")
        val dynamicModule = read("src/main/kotlin/top/bilibili/draw/DynamicModuleDraw.kt")
        val liveDraw = read("src/main/kotlin/top/bilibili/draw/LiveDraw.kt")
        val general = read("src/main/kotlin/top/bilibili/draw/General.kt")
        val fontManager = read("src/main/kotlin/top/bilibili/draw/FontManager.kt")
        val snapshot = read("src/main/kotlin/top/bilibili/draw/RenderSnapshot.kt")

        assertTrue(
            snapshot.contains("object RenderSnapshotFactory"),
            "draw rendering should provide a shared snapshot factory",
        )
        assertFalse(
            dynamicDraw.contains("BiliConfigManager.config.imageConfig"),
            "DynamicDraw should read image settings from RenderSnapshot instead of global config",
        )
        assertFalse(
            dynamicDraw.contains("BiliConfigManager.config.templateConfig.footer"),
            "DynamicDraw footer settings should come from RenderSnapshot",
        )
        assertFalse(
            dynamicMajor.contains("BiliConfigManager.config.imageConfig"),
            "DynamicMajorDraw should read badge settings from RenderSnapshot",
        )
        assertFalse(
            dynamicModule.contains("BiliConfigManager.config.imageConfig"),
            "DynamicModuleDraw should read ornament settings from RenderSnapshot",
        )
        assertFalse(
            liveDraw.contains("BiliConfigManager.config.imageConfig"),
            "LiveDraw should read image settings from RenderSnapshot",
        )
        assertFalse(
            liveDraw.contains("BiliConfigManager.config.templateConfig.footer"),
            "LiveDraw footer settings should come from RenderSnapshot",
        )
        assertFalse(
            general.contains("BiliConfigManager.config.imageConfig.colorGenerator"),
            "General color helpers should read color generator settings from RenderSnapshot",
        )
        assertFalse(
            fontManager.contains("BiliConfigManager.config.imageConfig"),
            "FontManager should not read font settings directly from global config during draw",
        )
    }
}