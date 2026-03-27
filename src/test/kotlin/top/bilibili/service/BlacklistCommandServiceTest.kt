package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.bilibili.BiliData
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformType

class BlacklistCommandServiceTest {
    private val dataFile = Path.of("config", "BiliData.yml")
    private var originalDataFile: String? = null

    // 记录发送文本，避免测试退化成对真实网关的集成依赖。
    private class RecordingGateway : MessageGateway {
        val messages = mutableListOf<String>()

        override suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
            messages += message.filterIsInstance<OutgoingPart.Text>().joinToString("") { it.text }
            return true
        }

        override suspend fun sendAdminMessage(message: String): Boolean {
            messages += message
            return true
        }
    }

    private lateinit var gateway: RecordingGateway

    @BeforeTest
    fun setup() {
        originalDataFile = if (Files.exists(dataFile)) {
            Files.readString(dataFile, StandardCharsets.UTF_8)
        } else {
            null
        }
        BiliData.linkParseBlacklist.clear()
        BiliData.linkParseBlacklistContacts.clear()
        gateway = RecordingGateway()
        MessageGatewayProvider.register(gateway)
    }

    @AfterTest
    fun cleanup() {
        MessageGatewayProvider.unregister()
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
    fun `quick add remove and list should use onebot11 namespaced subjects`() = runBlocking {
        val chatContact = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "10001")

        BlacklistCommandService.quickAdd(chatContact, "20002")
        BlacklistCommandService.quickList(chatContact)
        BlacklistCommandService.quickRemove(chatContact, "20002")

        assertTrue(BiliData.linkParseBlacklistContacts.none { it == "group:20002" || it == "private:20002" })
        assertTrue(gateway.messages.any { it.contains("onebot11:private:20002") })
    }

    @Test
    fun `quick add remove and list should support qq official subjects`() = runBlocking {
        val chatContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        BlacklistCommandService.quickAdd(chatContact, "user_openid_demo")
        BlacklistCommandService.quickList(chatContact)
        BlacklistCommandService.quickRemove(chatContact, "user_openid_demo")

        assertEquals(emptySet(), BiliData.linkParseBlacklistContacts)
        assertTrue(gateway.messages.any { it.contains("qq_official:private:user_openid_demo") })
    }
}
