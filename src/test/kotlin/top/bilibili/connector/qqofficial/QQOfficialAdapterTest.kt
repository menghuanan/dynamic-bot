package top.bilibili.connector.qqofficial

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import top.bilibili.config.QQOfficialConfig
import top.bilibili.connector.CapabilityGuardResult
import top.bilibili.connector.CapabilityRequest
import top.bilibili.connector.ImageSource
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformType

class QQOfficialAdapterTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `missing credentials should keep adapter unavailable`() {
        val transport = FakeTransport()
        val adapter = QQOfficialAdapter(
            config = QQOfficialConfig(),
            transport = transport,
        )

        assertFailsWith<IllegalStateException> {
            adapter.start()
        }
        assertFalse(adapter.runtimeStatus().connected)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `token request body should use app id and client secret`() {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)

        try {
            val tokenRequest = transport.requests.first { it.url.endsWith("/app/getAppAccessToken") }
            val identifyPayload = transport.lastGatewaySession.sentTexts.first().jsonObject

            assertEquals("POST", tokenRequest.method)
            assertEquals("demo-app", tokenRequest.body!!.jsonObject["appId"]!!.jsonPrimitive.content)
            assertEquals("demo-secret", tokenRequest.body!!.jsonObject["clientSecret"]!!.jsonPrimitive.content)
            assertEquals("GET", transport.requests.first { it.url.endsWith("/gateway/bot") }.method)
            assertEquals(2, identifyPayload["op"]!!.jsonPrimitive.content.toInt())
            assertEquals("QQBot access-token-demo", identifyPayload["d"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        } finally {
            adapter.stop()
        }
    }

    @Test
    fun `websocket dispatch payload should normalize group and c2c messages`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)

        try {
            val events = mutableListOf<top.bilibili.connector.PlatformInboundMessage>()
            val collectJob = launch {
                adapter.eventFlow.take(2).toList(events)
            }

            // 让 SharedFlow 收集协程先完成订阅，避免测试线程比收集线程更早发出事件。
            delay(10)
            transport.emitGatewayText(groupMessageFrame())
            transport.emitGatewayText(c2cMessageFrame())

            withTimeout(1_000) {
                collectJob.join()
            }

            val groupEvent = events[0]
            assertEquals(PlatformType.QQ_OFFICIAL, groupEvent.platform)
            assertEquals(PlatformChatType.GROUP, groupEvent.chatType)
            assertEquals("group_openid_demo", groupEvent.chatContact.id)
            assertEquals("member_openid_demo", groupEvent.senderContact.id)
            assertTrue(groupEvent.hasMention)

            val c2cEvent = events[1]
            assertEquals(PlatformChatType.PRIVATE, c2cEvent.chatType)
            assertEquals("user_openid_demo", c2cEvent.chatContact.id)
            assertEquals("user_openid_demo", c2cEvent.senderContact.id)
            assertFalse(c2cEvent.hasMention)
        } finally {
            adapter.stop()
        }
    }

    @Test
    fun `text send should build group and c2c message requests`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")
        val privateContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, "user_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            transport.emitGatewayText(c2cMessageFrame())
            waitForReachable(adapter, groupContact)
            waitForReachable(adapter, privateContact)

            assertTrue(adapter.sendMessage(groupContact, listOf(OutgoingPart.text("群消息"))))
            assertTrue(adapter.sendMessage(privateContact, listOf(OutgoingPart.text("私聊消息"))))

            val groupRequest = transport.requests.first { it.url.endsWith("/v2/groups/group_openid_demo/messages") }
            val privateRequest = transport.requests.first { it.url.endsWith("/v2/users/user_openid_demo/messages") }

            assertEquals(0, groupRequest.body!!.jsonObject["msg_type"]!!.jsonPrimitive.content.toInt())
            assertEquals("群消息", groupRequest.body!!.jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals(0, privateRequest.body!!.jsonObject["msg_type"]!!.jsonPrimitive.content.toInt())
            assertEquals("私聊消息", privateRequest.body!!.jsonObject["content"]!!.jsonPrimitive.content)
        } finally {
            adapter.stop()
        }
    }

    @Test
    fun `image send should upload media before sending rich media message`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertTrue(
                adapter.sendMessage(
                    groupContact,
                    listOf(
                        OutgoingPart.text("带图消息"),
                        OutgoingPart.image("https://example.com/demo.png"),
                    ),
                ),
            )

            val uploadRequest = transport.requests.first { it.url.endsWith("/v2/groups/group_openid_demo/files") }
            val sendRequest = transport.requests.last { it.url.endsWith("/v2/groups/group_openid_demo/messages") }

            assertEquals(1, uploadRequest.body!!.jsonObject["file_type"]!!.jsonPrimitive.content.toInt())
            assertEquals("https://example.com/demo.png", uploadRequest.body!!.jsonObject["url"]!!.jsonPrimitive.content)
            assertEquals(7, sendRequest.body!!.jsonObject["msg_type"]!!.jsonPrimitive.content.toInt())
            assertEquals("带图消息", sendRequest.body!!.jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("file-info-demo", sendRequest.body!!.jsonObject["media"]!!.jsonObject["file_info"]!!.jsonPrimitive.content)
        } finally {
            adapter.stop()
        }
    }

    @Test
    fun `capability query should expose direct send image reply and atall support`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertTrue(adapter.canSendMessage(groupContact))
            assertTrue(adapter.canSendImages(groupContact, listOf(ImageSource.RemoteUrl("https://example.com/demo.png"))))
            assertFalse(adapter.canSendImages(groupContact, listOf(ImageSource.LocalFile("temp/demo.png"))))
            assertTrue(adapter.canReply(groupContact))
            assertFalse(adapter.canAtAll(groupContact))
        } finally {
            adapter.stop()
        }
    }

    @Test
    fun `unsupported mention all should fail explicitly without silent fallback`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertFalse(adapter.sendMessage(groupContact, listOf(OutgoingPart.atAll(), OutgoingPart.text("公告"))))
            assertFalse(transport.requests.any { it.url.endsWith("/v2/groups/group_openid_demo/messages") })
        } finally {
            adapter.stop()
        }
    }

    @Test
    fun `local image should degrade to plain text while reply should keep explicit msg id`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertTrue(
                adapter.sendMessage(
                    groupContact,
                    listOf(
                        OutgoingPart.reply(42),
                        OutgoingPart.text("仅文本降级"),
                        OutgoingPart.Image(ImageSource.LocalFile("temp/demo.png")),
                    ),
                ),
            )

            val sendRequest = transport.requests.last { it.url.endsWith("/v2/groups/group_openid_demo/messages") }
            assertEquals(0, sendRequest.body!!.jsonObject["msg_type"]!!.jsonPrimitive.content.toInt())
            assertEquals("仅文本降级", sendRequest.body!!.jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("42", sendRequest.body!!.jsonObject["msg_id"]!!.jsonPrimitive.content)
            assertFalse(sendRequest.body!!.jsonObject.containsKey("media"))
        } finally {
            adapter.stop()
        }
    }

    @Test
    fun `pure local image should fail explicitly when no text fallback exists`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertFalse(
                adapter.sendMessage(
                    groupContact,
                    listOf(OutgoingPart.Image(ImageSource.LocalFile("temp/demo.png"))),
                ),
            )
            assertFalse(transport.requests.any { it.url.endsWith("/v2/groups/group_openid_demo/messages") })
        } finally {
            adapter.stop()
        }
    }

    @Test
    fun `guard capability should expose explicit unsupported reason for local image and atall`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            val imageGuard = adapter.guardCapability(
                CapabilityRequest(
                    capability = PlatformCapability.SEND_IMAGES,
                    contact = groupContact,
                    images = listOf(ImageSource.LocalFile("temp/demo.png")),
                ),
            )
            val atAllGuard = adapter.guardCapability(
                CapabilityRequest(
                    capability = PlatformCapability.AT_ALL,
                    contact = groupContact,
                ),
            )

            val degradedImage = assertIs<CapabilityGuardResult.Degraded>(imageGuard)
            assertTrue(degradedImage.reason.contains("public remote image"), "qq official image reason should be explicit")
            val unsupportedAtAll = assertIs<CapabilityGuardResult.Unsupported>(atAllGuard)
            assertTrue(unsupportedAtAll.reason.contains("@全体"), "qq official at-all reason should be explicit")
        } finally {
            adapter.stop()
        }
    }

    @Test
    fun `group manage events should toggle send capability`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            assertFalse(adapter.canSendMessage(groupContact))

            transport.emitGatewayText(
                manageGroupFrame(
                    eventType = "GROUP_ADD_ROBOT",
                    groupOpenId = "group_openid_demo",
                ),
            )
            waitForReachable(adapter, groupContact)
            assertTrue(adapter.canSendMessage(groupContact))

            transport.emitGatewayText(
                manageGroupFrame(
                    eventType = "GROUP_DEL_ROBOT",
                    groupOpenId = "group_openid_demo",
                ),
            )

            withTimeout(1_000) {
                while (adapter.canSendMessage(groupContact)) {
                    delay(10)
                }
            }
            assertFalse(adapter.canSendMessage(groupContact))
        } finally {
            adapter.stop()
        }
    }

    // 启动测试适配器时，预置 Hello/Ready 帧，确保启动路径能完成首轮网关握手。
    private fun createStartedAdapter(transport: FakeTransport): QQOfficialAdapter {
        val adapter = QQOfficialAdapter(
            config = QQOfficialConfig(
                appId = "demo-app",
                appSecret = "demo-secret",
            ),
            transport = transport,
        )
        adapter.start()
        return adapter
    }

    // 等待联系人被适配器标记为当前运行时可达，避免测试与异步事件处理竞争。
    private suspend fun waitForReachable(adapter: QQOfficialAdapter, contact: PlatformContact) {
        withTimeout(1_000) {
            while (!adapter.isContactReachable(contact)) {
                delay(10)
            }
        }
    }

    // 构造群聊消息事件，覆盖 group_openid/member_openid 的运行时归一化路径。
    private fun groupMessageFrame(): String {
        return buildJsonObject {
            put("op", 0)
            put("s", 2)
            put("t", "GROUP_AT_MESSAGE_CREATE")
            put("id", "evt-group-1")
            put("d", buildJsonObject {
                put("id", "msg-group-1")
                put("content", "/bili list")
                put("group_openid", "group_openid_demo")
                put("author", buildJsonObject {
                    put("member_openid", "member_openid_demo")
                })
            })
        }.toString()
    }

    // 构造 C2C 消息事件，覆盖 user_openid 的运行时归一化路径。
    private fun c2cMessageFrame(): String {
        return buildJsonObject {
            put("op", 0)
            put("s", 3)
            put("t", "C2C_MESSAGE_CREATE")
            put("id", "evt-c2c-1")
            put("d", buildJsonObject {
                put("id", "msg-c2c-1")
                put("content", "/login")
                put("author", buildJsonObject {
                    put("user_openid", "user_openid_demo")
                })
            })
        }.toString()
    }

    // 构造群管理事件，覆盖机器人被加入/移出群时的可达性切换路径。
    private fun manageGroupFrame(eventType: String, groupOpenId: String): String {
        return buildJsonObject {
            put("op", 0)
            put("s", 4)
            put("t", eventType)
            put("id", "evt-manage-$eventType")
            put("d", buildJsonObject {
                put("group_openid", groupOpenId)
            })
        }.toString()
    }

    private inner class FakeTransport : QQOfficialTransport {
        val requests = mutableListOf<RecordedRequest>()
        val lastGatewaySession = FakeGatewaySession(
            bootstrapFrames = listOf(
                buildJsonObject {
                    put("op", 10)
                    put("d", buildJsonObject {
                        put("heartbeat_interval", 30_000)
                    })
                }.toString(),
                buildJsonObject {
                    put("op", 0)
                    put("s", 1)
                    put("t", "READY")
                    put("id", "evt-ready-1")
                    put("d", buildJsonObject {
                        put("version", 1)
                        put("session_id", "session-demo")
                        put("user", buildJsonObject {
                            put("id", "bot_openid_demo")
                            put("username", "dynamic-bot")
                            put("bot", true)
                        })
                        put("shard", buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive(0))
                            add(kotlinx.serialization.json.JsonPrimitive(1))
                        })
                    })
                }.toString(),
            ),
        )

        override suspend fun getJson(url: String, headers: Map<String, String>): JsonObject {
            requests += RecordedRequest("GET", url, null, headers)
            return buildJsonObject {
                put("url", "wss://gateway.example.qq.com")
                put("shards", 1)
                put("session_start_limit", buildJsonObject {
                    put("total", 1000)
                    put("remaining", 999)
                    put("reset_after", 1)
                    put("max_concurrency", 1)
                })
            }
        }

        override suspend fun postJson(url: String, body: JsonElement, headers: Map<String, String>): JsonObject {
            requests += RecordedRequest("POST", url, body, headers)
            return when {
                url.endsWith("/app/getAppAccessToken") -> buildJsonObject {
                    put("access_token", "access-token-demo")
                    put("expires_in", "7200")
                }
                url.endsWith("/files") -> buildJsonObject {
                    put("file_uuid", "file-uuid-demo")
                    put("file_info", "file-info-demo")
                    put("ttl", 60)
                }
                else -> buildJsonObject {
                    put("id", "message-demo")
                }
            }
        }

        override suspend fun openGateway(url: String, headers: Map<String, String>): QQOfficialGatewaySession {
            requests += RecordedRequest("WS", url, null, headers)
            return lastGatewaySession
        }

        override fun close() = Unit

        // 允许测试在启动后继续推送额外的网关事件。
        suspend fun emitGatewayText(text: String) {
            lastGatewaySession.emit(text)
        }
    }

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val body: JsonElement?,
        val headers: Map<String, String>,
    )

    private inner class FakeGatewaySession(
        bootstrapFrames: List<String>,
    ) : QQOfficialGatewaySession {
        private val incomingFlow = MutableSharedFlow<String>(replay = 16, extraBufferCapacity = 16)
        private val closed = CompletableDeferred<Throwable?>()
        val sentTexts = mutableListOf<JsonElement>()

        init {
            bootstrapFrames.forEach { frame ->
                incomingFlow.tryEmit(frame)
            }
        }

        override val incoming: Flow<String> = incomingFlow
        override val closeSignal = closed

        override suspend fun sendText(text: String) {
            sentTexts += json.parseToJsonElement(text)
        }

        override suspend fun close(reason: String) {
            if (!closed.isCompleted) {
                closed.complete(null)
            }
        }

        // 允许测试在运行中向适配器注入新的网关消息。
        suspend fun emit(text: String) {
            incomingFlow.emit(text)
        }
    }
}
