package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadmeDeploymentRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `docker deployment docs should stop container before editing mounted config`() {
        val readme = read("README.md")

        assertTrue(
            readme.contains("docker-compose down") || readme.contains("docker stop dynamic-bot"),
            "README should instruct users to stop the container before editing mounted config",
        )
        assertFalse(
            readme.contains("docker-compose restart") || readme.contains("docker restart dynamic-bot"),
            "README should not keep restart-based guidance for mounted config edits",
        )
    }
}
