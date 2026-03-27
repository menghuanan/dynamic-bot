package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class DockerfileLoginDependencyRegressionTest {
    // 统一按 UTF-8 读取 Dockerfile，避免宿主默认编码影响依赖回归判断。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `docker image should keep awt x11 runtime libraries required by login qr rendering`() {
        val dockerfile = read("Dockerfile")

        // /login 二维码绘制会经由 ZXing 的 BufferedImage 路径触发 libawt_xawt.so，因此需要保留这组运行库。
        listOf(
            "libx11-6",
            "libxext6",
            "libxrender1",
            "libxtst6",
            "libxi6",
            "libgl1-mesa-dri",
            "libglu1-mesa",
            "libegl1",
            "libgles2",
        ).forEach { packageName ->
            assertTrue(
                dockerfile.contains(packageName),
                "Dockerfile should keep $packageName for login qr rendering in container",
            )
        }
    }
}
