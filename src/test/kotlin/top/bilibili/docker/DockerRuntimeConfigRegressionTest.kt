package top.bilibili.docker

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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

    @Test
    fun `docker compose should cap swap usage to container memory hard limit`() {
        val compose = read("docker-compose.yml")

        assertTrue(
            compose.contains("mem_limit: 512m"),
            "docker-compose should define a concrete memory hard limit for local runtime",
        )
        assertTrue(
            compose.contains("memswap_limit: 512m"),
            "docker-compose should cap swap to the same value as memory hard limit",
        )
    }

    @Test
    fun `docker runtime should use pooled netty allocator`() {
        val dockerfile = read("Dockerfile")

        assertTrue(
            dockerfile.contains("-Dio.netty.allocator.type=pooled"),
            "Dockerfile should switch Netty allocator type to pooled for runtime behavior alignment",
        )
        assertFalse(
            dockerfile.contains("-Dio.netty.allocator.type=unpooled"),
            "Dockerfile should no longer force Netty unpooled allocator",
        )
    }

    @Test
    fun `docker runtime should keep jemalloc decay aggressive without introducing legacy chunk tuning`() {
        val dockerfile = read("Dockerfile")

        // 约束 jemalloc 维持更积极的脏页回收，并保留当前线程与 arena 策略。
        assertTrue(
            dockerfile.contains("MALLOC_CONF=background_thread:true,dirty_decay_ms:2000,muzzy_decay_ms:2000,narenas:1,tcache:false"),
            "Dockerfile should keep jemalloc config with decay=2000 and narenas/background_thread strategy",
        )
        // lg_chunk 属于历史参数，当前镜像不应引入该调优项，避免运行时兼容风险。
        assertFalse(
            dockerfile.contains("lg_chunk"),
            "Dockerfile should not introduce legacy lg_chunk tuning in MALLOC_CONF",
        )
    }
}
