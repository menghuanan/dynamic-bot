package top.bilibili.docker

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class DockerRuntimeConfigRegressionTest {
    // Docker 相关源码回归统一按 UTF-8 读取，避免宿主平台默认编码影响字符串断言。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `docker runtime should use jdk image and enable native memory tracking summary by default`() {
        val dockerfile = read("Dockerfile")

        assertTrue(
            dockerfile.contains("FROM eclipse-temurin:17-jdk"),
            "Dockerfile should use a JDK runtime image so jcmd and NMT stay available in container",
        )
        assertTrue(
            dockerfile.contains("-XX:NativeMemoryTracking=summary"),
            "Dockerfile should enable NativeMemoryTracking summary mode by default",
        )
    }

    @Test
    fun `docker documentation should describe summary as default and detail as troubleshooting only`() {
        val readme = read("README.md")

        assertTrue(
            readme.contains("NativeMemoryTracking=summary") || readme.contains("NMT(summary)") || readme.contains("NMT summary"),
            "README should document that Docker defaults to NativeMemoryTracking summary mode",
        )
        assertTrue(
            readme.contains("detail"),
            "README should explain that NativeMemoryTracking detail mode is reserved for troubleshooting",
        )
    }
}
