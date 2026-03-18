package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class CiWorkflowObservabilityTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `ci workflow should emit richer diagnostics on success and failure`() {
        val text = read(".github/workflows/ci.yml")

        assertTrue(
            text.contains("name: Environment Diagnostics"),
            "workflow should log runner and toolchain diagnostics before the Gradle build",
        )
        assertTrue(
            text.contains("--stacktrace") && text.contains("--info") && text.contains("--warning-mode all"),
            "workflow should run Gradle with richer diagnostics flags",
        )
        assertTrue(
            text.contains("if: always()") && text.contains("build/reports") && text.contains("build/test-results"),
            "workflow should always upload reports and test results for post-failure inspection",
        )
        assertTrue(
            text.contains("GITHUB_STEP_SUMMARY"),
            "workflow should write a compact execution summary to the GitHub job summary",
        )
        assertTrue(
            text.contains("build/libs/*.jar"),
            "workflow should continue uploading build artifacts on successful runs",
        )
    }
}
