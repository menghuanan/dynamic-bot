package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerfileLoginDependencyRegressionTest {
    // 统一按 UTF-8 读取 Dockerfile，避免宿主默认编码影响依赖回归判断。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `docker image should remove mesa specific graphics runtime libraries in software rendering mode`() {
        val dockerfile = read("Dockerfile")

        // 登录专用二维码已切到纯 Skia 绘制；这些库若保留，说明必须来自当前其他图形路径或运行时证据。
        // 当前策略是容器纯软件渲染，避免额外引入 Mesa 软光栅驱动带来的常驻内存池。
        listOf(
            "libgl1-mesa-dri",
            "libglu1-mesa",
            "libegl1",
            "libgles2",
        ).forEach { packageName ->
            assertFalse(
                dockerfile.contains(packageName),
                "Dockerfile should remove $packageName in software rendering mode to avoid loading Mesa software rasterizer",
            )
        }
        // 保留通用图形运行库，避免误删当前仍在使用的 headless/AWT 相关基础依赖。
        listOf(
            "libx11-6",
            "libxext6",
            "libxrender1",
            "libxtst6",
            "libxi6",
        ).forEach { packageName ->
            assertTrue(
                dockerfile.contains(packageName),
                "Dockerfile should keep $packageName for generic graphics runtime compatibility",
            )
        }
        // Docker 注释不得继续把这组库归因到 /login 的 BufferedImage 路径，避免文档和实现失真。
        assertFalse(
            dockerfile.contains("/login 二维码经由 BufferedImage 路径"),
            "Dockerfile should no longer attribute retained graphics packages to the login qr BufferedImage path",
        )
    }
}
