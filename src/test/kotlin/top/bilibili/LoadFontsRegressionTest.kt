package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class LoadFontsRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `loadFonts should create nested data font directory recursively`() {
        val text = read("src/main/kotlin/top/bilibili/Init.kt")

        assertTrue(
            text.contains("if (!exists()) createDirectories()"),
            "loadFonts should create the data/font path recursively for clean CI workspaces",
        )
    }
}
