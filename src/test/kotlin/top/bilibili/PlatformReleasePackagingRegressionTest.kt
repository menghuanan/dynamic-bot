package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformReleasePackagingRegressionTest {
    // 发行链路回归测试只读取仓库文本，避免把 Gradle 任务执行副作用带入断言。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    // 锁定 GitHub Release 对普通用户暴露的按平台资产名称，避免重新退回裸 jar 发布路径。
    @Test
    fun `release workflow should publish platform specific distribution assets`() {
        val workflow = read(".github/workflows/release.yml")

        assertTrue(
            workflow.contains("dynamic-bot-windows-x64-v"),
            "release workflow should upload a Windows-specific release asset",
        )
        assertTrue(
            workflow.contains("dynamic-bot-linux-x64-v"),
            "release workflow should upload a Linux-specific release asset",
        )
        assertFalse(
            workflow.contains("files: build/libs/*.jar"),
            "release workflow should stop exposing the bare jar as the primary release asset",
        )
    }

    // 锁定 Gradle 平台发行任务的公开名称与归档命名，防止混合 distribution 再次成为发布资产。
    @Test
    fun `build script should define platform specific distribution packaging`() {
        val buildScript = read("build.gradle.kts")

        assertTrue(
            buildScript.contains("windowsReleaseDistZip"),
            "build script should register a dedicated Windows distribution task",
        )
        assertTrue(
            buildScript.contains("linuxReleaseDistTar"),
            "build script should register a dedicated Linux distribution task",
        )
        assertTrue(
            buildScript.contains("dynamic-bot-windows-x64-v"),
            "Windows distribution should use a platform-specific archive name",
        )
        assertTrue(
            buildScript.contains("dynamic-bot-linux-x64-v"),
            "Linux distribution should use a platform-specific archive name",
        )
    }
}
