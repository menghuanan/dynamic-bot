package top.bilibili.tasker

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData

class ListenerTaskerBlacklistMigrationTest {
    private val dataFile = Path.of("config", "BiliData.yml")
    private var originalDataFile: String? = null

    @AfterTest
    fun cleanup() {
        BiliData.linkParseBlacklist.clear()
        BiliData.linkParseBlacklistContacts.clear()
        if (originalDataFile == null) {
            Files.deleteIfExists(dataFile)
        } else {
            Files.createDirectories(dataFile.parent)
            Files.writeString(dataFile, originalDataFile!!, StandardCharsets.UTF_8)
        }
    }

    @Test
    fun `listener tasker should only consult normalized blacklist subjects at runtime`() {
        val listenerSource = Files.readString(
            Path.of("src", "main", "kotlin", "top", "bilibili", "tasker", "ListenerTasker.kt"),
            StandardCharsets.UTF_8,
        )

        // 运行期判断必须只读 namespaced subject 黑名单，避免 legacy numeric 集合重新进入 live path。
        assertFalse(listenerSource.contains("linkParseBlacklist::contains"))
        assertTrue(listenerSource.contains("linkParseBlacklistContacts.contains"))
    }

    @Test
    fun `startup migration should normalize legacy numeric blacklist entries and clear the old set`() {
        originalDataFile = if (Files.exists(dataFile)) {
            Files.readString(dataFile, StandardCharsets.UTF_8)
        } else {
            null
        }
        Files.createDirectories(dataFile.parent)
        Files.writeString(
            dataFile,
            """
            dataVersion: 0
            dynamic:
              0:
                name: ALL
                last: 0
                lastLive: 0
                contacts: []
                sourceRefs: []
                banList: {}
            filter: {}
            dynamicPushTemplate: {}
            livePushTemplate: {}
            liveCloseTemplate: {}
            dynamicPushTemplateByUid: {}
            livePushTemplateByUid: {}
            liveCloseTemplateByUid: {}
            dynamicColorByUid: {}
            atAll: {}
            group: {}
            bangumi: {}
            linkParseBlacklist:
              - 20002
            linkParseBlacklistContacts: []
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        BiliConfigManager.reloadData()

        assertEquals(emptySet(), BiliData.linkParseBlacklist)
        assertEquals(setOf("onebot11:private:20002"), BiliData.linkParseBlacklistContacts)
    }
}
