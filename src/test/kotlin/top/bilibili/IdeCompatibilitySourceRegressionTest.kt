package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeCompatibilitySourceRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

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
        val source = read("src/main/kotlin/top/bilibili/napcat/NapCatClient.kt")

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
}
