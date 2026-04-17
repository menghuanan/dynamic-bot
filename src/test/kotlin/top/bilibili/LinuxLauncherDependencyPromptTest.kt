package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxLauncherDependencyPromptTest {
    // 该测试以源码文本锁定发行脚本和 README 约束，避免需要真实安装 jemalloc 才能验证分支。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    // Linux 裸机依赖缺失时必须先询问用户，且只能走系统官方包管理器安装路径。
    @Test
    fun `linux start script generation should offer official package manager installation`() {
        val buildScript = read("build.gradle.kts")

        assertTrue(
            buildScript.contains("Install jemalloc via the system package manager now? [y/N]"),
            "Linux launcher should ask before installing jemalloc",
        )
        assertTrue(
            buildScript.contains("apt-get install -y libjemalloc2"),
            "Linux launcher should support apt-get based installation",
        )
        assertTrue(
            buildScript.contains("dnf install -y jemalloc"),
            "Linux launcher should support dnf based installation",
        )
        assertTrue(
            buildScript.contains("yum install -y jemalloc"),
            "Linux launcher should support yum based installation",
        )
        assertTrue(
            buildScript.contains("[ -t 0 ]"),
            "Linux launcher should only prompt in interactive terminals",
        )
    }

    // README 面向普通用户只保留发行包脚本入口，避免绕过脚本内的 JVM 参数和依赖检查。
    @Test
    fun `readme should direct users to the packaged launcher scripts`() {
        val readme = read("README.md")

        assertTrue(
            readme.contains("bin/start.bat"),
            "README should document the Windows launcher path",
        )
        assertTrue(
            readme.contains("bin/start.sh"),
            "README should document the Linux launcher path",
        )
        assertFalse(
            readme.contains("java -jar"),
            "README should stop recommending bare jar startup for end users",
        )
    }
}
