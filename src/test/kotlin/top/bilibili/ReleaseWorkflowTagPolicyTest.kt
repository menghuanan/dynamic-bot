package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseWorkflowTagPolicyTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `tag release workflow should publish only exact tag and latest`() {
        val text = read(".github/workflows/release.yml")

        assertTrue(
            text.contains("type=raw,value=\${{ github.ref_name }}"),
            "workflow should publish the exact git tag",
        )
        assertTrue(
            text.contains("type=raw,value=latest"),
            "workflow should publish latest for release tags",
        )
        assertFalse(
            text.contains("MAJOR_MINOR_TAG"),
            "workflow should not derive a movable major-minor Docker tag",
        )
        assertFalse(
            text.contains("type=raw,value=\${{ env.MAJOR_MINOR_TAG }}"),
            "workflow should not publish a major-minor Docker tag alias",
        )
        // 发布构建必须输出详细 Gradle 日志，避免在 test 阶段静默失败且缺少定位信息。
        assertTrue(
            text.contains("name: Compile with Gradle") &&
                text.contains("name: Test with Gradle") &&
                text.contains("name: Package with Gradle"),
            "workflow should split release build into compile/test/package stages for clearer diagnostics",
        )
        assertTrue(
            text.contains("--stacktrace") && text.contains("--info") && text.contains("--warning-mode all"),
            "workflow should run Gradle with detailed diagnostic flags in release builds",
        )
    }
}
