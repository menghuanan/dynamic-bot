package top.bilibili.core.resource

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import top.bilibili.skia.SkiaManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

@EnabledIfSystemProperty(named = "skia.native.memory.evidence", matches = "true")
class SkiaNativeMemoryEvidenceTest {
    @BeforeTest
    fun setupRuntimeConfig() = runBlocking {
        SkiaDrawSceneFixtures.prepareEnvironment()
    }

    @Test
    fun `repeated full-scene draw batches should not ratchet native committed memory after cleanup`() = runBlocking {
        val scenes = SkiaDrawSceneFixtures.sceneCases()

        runSceneBatch(scenes)
        SkiaManager.performCleanup()
        settleJvm()
        val firstPostCleanup = captureNativeMemorySnapshot()

        runSceneBatch(scenes)
        SkiaManager.performCleanup()
        settleJvm()
        val secondPostCleanup = captureNativeMemorySnapshot()

        runSceneBatch(scenes)
        SkiaManager.performCleanup()
        settleJvm()
        val thirdPostCleanup = captureNativeMemorySnapshot()

        println(
            "SkiaNativeMemoryEvidenceTest full-scene snapshots: " +
                "sceneCount=${scenes.size}, " +
                "firstCommitted=${firstPostCleanup.committedKb}KB, " +
                "secondCommitted=${secondPostCleanup.committedKb}KB, " +
                "thirdCommitted=${thirdPostCleanup.committedKb}KB, " +
                "firstReserved=${firstPostCleanup.reservedKb}KB, " +
                "secondReserved=${secondPostCleanup.reservedKb}KB, " +
                "thirdReserved=${thirdPostCleanup.reservedKb}KB"
        )

        val growthKb = thirdPostCleanup.committedKb - secondPostCleanup.committedKb
        assertTrue(
            growthKb <= 24 * 1024,
            "native committed memory grew by ${growthKb}KB between equal full-scene post-cleanup batches; first=${firstPostCleanup.committedKb}KB second=${secondPostCleanup.committedKb}KB third=${thirdPostCleanup.committedKb}KB\n${thirdPostCleanup.rawSummary}",
        )
    }

    private suspend fun runSceneBatch(scenes: List<SkiaDrawSceneFixtures.DrawSceneCase>) {
        scenes.forEach { scene ->
            val bytes = scene.render()
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            assertTrue(image != null, "scene ${scene.name} should stay decodable during NMT evidence run")
        }
    }

    private suspend fun settleJvm() {
        repeat(3) {
            System.gc()
            System.runFinalization()
            delay(100)
        }
    }

    private fun captureNativeMemorySnapshot(): NativeMemorySnapshot {
        val pid = ProcessHandle.current().pid().toString()
        val process = ProcessBuilder("jcmd", pid, "VM.native_memory", "summary")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            fail("jcmd exited with $exitCode for pid=$pid\n$output")
        }
        if (output.contains("Native memory tracking is not enabled", ignoreCase = true)) {
            fail("Native memory tracking is not enabled for pid=$pid\n$output")
        }

        val totalMatch = Regex("""Total:\s+reserved=(\d+)KB,\s+committed=(\d+)KB""")
            .find(output)
            ?: fail("failed to parse VM.native_memory summary for pid=$pid\n$output")

        return NativeMemorySnapshot(
            reservedKb = totalMatch.groupValues[1].toLong(),
            committedKb = totalMatch.groupValues[2].toLong(),
            rawSummary = output,
        )
    }

    private data class NativeMemorySnapshot(
        val reservedKb: Long,
        val committedKb: Long,
        val rawSummary: String,
    )
}
