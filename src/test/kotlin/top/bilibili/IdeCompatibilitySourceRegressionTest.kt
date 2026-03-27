package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeCompatibilitySourceRegressionTest {
    private fun read(path: String): String =
        Files.readString(Path.of(path), StandardCharsets.UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n')

    @Test
    fun `bili client should cache proxies before indexing`() {
        val source = read("src/main/kotlin/top/bilibili/client/BiliClient.kt")

        assertTrue(source.contains("val proxies = proxys"))
        assertTrue(source.contains("if (!proxies.isNullOrEmpty() && BiliConfigManager.config.enableConfig.proxyEnable)"))
        assertFalse(source.contains("if (proxyEnabled) {"))
        assertFalse(source.contains("client.engineConfig.proxy = proxys[proxyIndex]"))
    }

    @Test
    fun `business lifecycle manager should cache timeout before withTimeout`() {
        val source = read("src/main/kotlin/top/bilibili/core/resource/BusinessLifecycleManager.kt")

        assertTrue(source.contains("val hardTimeoutMs = strictness.businessHardTimeoutMs"))
        assertTrue(source.contains("withTimeout(hardTimeoutMs)"))
    }

    @Test
    fun `dynamic module draw should avoid java stream code point APIs`() {
        val source = read("src/main/kotlin/top/bilibili/draw/DynamicModuleDraw.kt")

        assertTrue(source.contains("toCodePointStrings()"))
        assertTrue(source.contains("toEmojiCodePointKey()"))
        assertFalse(source.contains(".codePoints()"))
        assertFalse(source.contains("Collectors"))
    }

    @Test
    fun `napcat client should use reified json encoding for message segments`() {
        val source = read("src/main/kotlin/top/bilibili/connector/onebot11/vendors/napcat/NapCatClient.kt")

        assertTrue(source.contains("json.encodeToJsonElement(segmentsToSend)"))
        assertFalse(source.contains("ListSerializer(MessageSegment.serializer())"))
    }

    @Test
    fun `translation and config serialization should use reified overloads`() {
        val transApi = read("src/main/kotlin/top/bilibili/utils/translate/TransApi.kt")
        val configManager = read("src/main/kotlin/top/bilibili/BiliConfigManager.kt")

        assertTrue(transApi.contains("json.decodeFromString<TransResult>(it)"))
        assertFalse(transApi.contains("TransResult.serializer()"))

        assertTrue(configManager.contains("yaml.decodeFromString<BiliConfig>(content)"))
        assertTrue(configManager.contains("yaml.decodeFromString<BiliDataWrapper>(content)"))
        assertTrue(configManager.contains("yaml.encodeToString(configToSave)"))
        assertTrue(configManager.contains("yaml.encodeToString(wrapper)"))
        assertFalse(configManager.contains("BiliConfig.serializer()"))
        assertFalse(configManager.contains("BiliDataWrapper.serializer()"))
    }

    @Test
    fun `tasker and drawing session warning fixes should be explicit`() {
        val tasker = read("src/main/kotlin/top/bilibili/tasker/BiliTasker.kt")
        val drawingSession = read("src/main/kotlin/top/bilibili/skia/DrawingSession.kt")

        assertTrue(tasker.contains("@OptIn(InternalForInheritanceCoroutinesApi::class)"))
        assertTrue(tasker.contains("// 预留未使用变量 policy: val policy = TaskResourcePolicyRegistry.policyOf(taskDisplayName)"))

        assertTrue(drawingSession.contains("return Image.makeFromEncoded(bytes).track()"))
        assertFalse(drawingSession.contains("?: throw IllegalArgumentException(\"Failed to decode image from bytes\")"))
    }

    @Test
    fun `napcat and utility search helpers should avoid warning patterns`() {
        val napcat = read("src/main/kotlin/top/bilibili/connector/onebot11/vendors/napcat/NapCatClient.kt")
        val general = read("src/main/kotlin/top/bilibili/utils/General.kt")

        assertFalse(napcat.contains("as List<MessageSegment>"))
        assertTrue(general.contains("biliClient.searchUser(target)"))
        assertTrue(general.contains("// 预留未使用变量 users: val users = biliClient.searchUser(target)"))
    }

    @Test
    fun `compatibility shims should suppress intentional unused parameters`() {
        val biliClient = read("src/main/kotlin/top/bilibili/client/BiliClient.kt")
        val liveDraw = read("src/main/kotlin/top/bilibili/draw/LiveDraw.kt")
        val configService = read("src/main/kotlin/top/bilibili/service/ConfigService.kt")

        assertTrue(biliClient.contains("@Suppress(\"UNUSED_PARAMETER\")\nfun buildRetryLogMessage("))
        assertTrue(biliClient.contains("@Suppress(\"UNUSED_PARAMETER\")\nfun buildRetryExhaustedLogMessage("))
        assertTrue(liveDraw.contains("@Suppress(\"UNUSED_PARAMETER\")\nfun Canvas.drawLiveOrnament("))
        assertTrue(configService.contains("@Suppress(\"UNUSED_PARAMETER\")\n    suspend fun config("))
    }

    @Test
    fun `low risk cleanup batch should remove shadowing and redundant temporaries`() {
        val pgc = read("src/main/kotlin/top/bilibili/api/Pgc.kt")
        val pgcService = read("src/main/kotlin/top/bilibili/service/PgcService.kt")
        val liveDraw = read("src/main/kotlin/top/bilibili/draw/LiveDraw.kt")
        val httpGet = read("src/main/kotlin/top/bilibili/utils/translate/HttpGet.kt")
        val md5 = read("src/main/kotlin/top/bilibili/utils/translate/MD5.kt")
        val init = read("src/main/kotlin/top/bilibili/Init.kt")

        assertTrue(pgc.contains("val parsedId = regex.destructured.component2().toLong()"))
        assertFalse(pgc.contains("val id = regex.destructured.component2().toLong()"))

        assertTrue(pgcService.contains("val parsedId = regex.destructured.component2().toLong()"))
        assertFalse(pgcService.contains("val id = regex.destructured.component2().toLong()"))

        assertTrue(liveDraw.contains("// 预留未使用变量 liveUid: val liveUid = uid"))

        assertFalse(httpGet.contains("var line: String? = null"))
        assertTrue(httpGet.contains("val line = br.readLine() ?: break"))

        assertFalse(md5.contains("var read = 0"))
        assertTrue(md5.contains("val read = `in`.read(buffer)"))

        assertTrue(init.contains("// 预留未使用变量 fontFolder: val fontFolder = BiliBiliBot.dataFolder.resolve(\"font\")"))
    }
}
