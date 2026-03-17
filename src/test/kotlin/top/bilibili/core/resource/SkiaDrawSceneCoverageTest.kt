package top.bilibili.core.resource

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SkiaDrawSceneCoverageTest {
    @BeforeTest
    fun setup() = runBlocking {
        SkiaDrawSceneFixtures.prepareEnvironment()
    }

    @Test
    fun `draw scene matrix should render every scene to a decodable image`() = runBlocking {
        val scenes = SkiaDrawSceneFixtures.sceneCases()
        assertTrue(scenes.size >= 30, "scene matrix should stay broad enough to cover current draw surfaces")

        val failures = mutableListOf<String>()
        scenes.forEach { scene ->
            try {
                val bytes = scene.render()
                assertTrue(bytes.isNotEmpty(), "scene ${scene.name} should produce non-empty image bytes")
                val decoded = ImageIO.read(ByteArrayInputStream(bytes))
                assertTrue(decoded != null, "scene ${scene.name} should decode into an image")
                assertTrue(decoded.width > 0, "scene ${scene.name} should have positive width")
                assertTrue(decoded.height > 0, "scene ${scene.name} should have positive height")
            } catch (t: Throwable) {
                failures += "${scene.name}: ${t::class.simpleName}: ${t.message}"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }
}
