package top.bilibili.core.resource

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class BusinessLifecycleManagerObservabilityTest {
    // 源码回归测试统一使用 UTF-8，避免不同平台默认编码导致关键字匹配漂移。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    // Native 任务相关性需要业务生命周期暴露 owner/operation 维度的活动快照。
    @Test
    fun `business lifecycle manager should expose owner activity runtime snapshots`() {
        val source = read("src/main/kotlin/top/bilibili/core/resource/BusinessLifecycleManager.kt")

        assertTrue(
            source.contains("BusinessOwnerActivitySnapshot"),
            "BusinessLifecycleManager should define owner activity snapshot model",
        )
        assertTrue(
            source.contains("runtimeActivitySnapshot"),
            "BusinessLifecycleManager should expose runtime activity snapshot API",
        )
    }
}
