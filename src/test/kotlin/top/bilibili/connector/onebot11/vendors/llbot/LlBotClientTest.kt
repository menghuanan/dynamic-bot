package top.bilibili.connector.onebot11.vendors.llbot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import top.bilibili.config.NapCatConfig
import top.bilibili.connector.CapabilityGuardResult
import top.bilibili.connector.CapabilityRequest
import top.bilibili.connector.ImageSource
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformObservabilitySnapshot
import top.bilibili.connector.PlatformType
import top.bilibili.connector.onebot11.core.OneBot11MessageSegment

class LlBotClientTest {
    @Test
    fun `llbot client should send standard onebot11 group action and await ok response`() = runBlocking {
        val transport = FakeTransport()
        transport.lastGatewaySession.onSend = { request ->
            if (request["action"]?.jsonPrimitive?.content == "send_group_msg") {
                transport.lastGatewaySession.emit(okResponse(request["echo"]!!.jsonPrimitive.content))
            }
        }
        val client = createStartedClient(transport)

        try {
            assertTrue(
                client.sendMessage(
                    PlatformChatType.GROUP,
                    100L,
                    listOf(OneBot11MessageSegment("text", mapOf("text" to "hello llbot"))),
                ),
            )

            val request = transport.lastGatewaySession.sentTexts.last()
            assertEquals("send_group_msg", request["action"]!!.jsonPrimitive.content)
            assertEquals("100", request["params"]!!.jsonObject["group_id"]!!.jsonPrimitive.content)
            assertEquals(
                "hello llbot",
                request["params"]!!.jsonObject["message"]!!.jsonArray[0].jsonObject["data"]!!.jsonObject["text"]!!.jsonPrimitive.content,
            )
        } finally {
            client.stop()
        }
    }

    @Test
    fun `llbot client should resolve group reachability from standard group list action`() = runBlocking {
        val transport = FakeTransport()
        transport.lastGatewaySession.onSend = { request ->
            if (request["action"]?.jsonPrimitive?.content == "get_group_list") {
                transport.lastGatewaySession.emit(
                    okResponse(
                        request["echo"]!!.jsonPrimitive.content,
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("group_id", JsonPrimitive(100))
                                },
                            ),
                        ),
                    ),
                )
            }
        }
        val client = createStartedClient(transport)

        try {
            assertTrue(client.isBotInGroup(100L))
        } finally {
            client.stop()
        }
    }

    @Test
    fun `llbot client should resolve atall from login info and group member role`() = runBlocking {
        val transport = FakeTransport()
        transport.lastGatewaySession.onSend = { request ->
            when (request["action"]?.jsonPrimitive?.content) {
                "get_login_info" -> {
                    transport.lastGatewaySession.emit(
                        okResponse(
                            request["echo"]!!.jsonPrimitive.content,
                            buildJsonObject {
                                put("user_id", JsonPrimitive(300))
                            },
                        ),
                    )
                }
                "get_group_member_info" -> {
                    transport.lastGatewaySession.emit(
                        okResponse(
                            request["echo"]!!.jsonPrimitive.content,
                            buildJsonObject {
                                put("role", JsonPrimitive("admin"))
                            },
                        ),
                    )
                }
            }
        }
        val client = createStartedClient(transport)

        try {
            assertTrue(client.canAtAllInGroup(100L))
        } finally {
            client.stop()
        }
    }

    @Test
    fun `llbot adapter should expose supported local image and atall when probes succeed`() = runBlocking {
        val transport = FakeTransport()
        transport.lastGatewaySession.onSend = { request ->
            when (request["action"]?.jsonPrimitive?.content) {
                "get_login_info" -> {
                    transport.lastGatewaySession.emit(
                        okResponse(
                            request["echo"]!!.jsonPrimitive.content,
                            buildJsonObject {
                                put("user_id", JsonPrimitive(300))
                            },
                        ),
                    )
                }
                "get_group_member_info" -> {
                    transport.lastGatewaySession.emit(
                        okResponse(
                            request["echo"]!!.jsonPrimitive.content,
                            buildJsonObject {
                                put("role", JsonPrimitive("owner"))
                            },
                        ),
                    )
                }
            }
        }
        val client = createStartedClient(transport)
        val adapter = LlBotAdapter(client)
        val contact = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "100")

        try {
            val imageGuard = adapter.guardCapability(
                CapabilityRequest(
                    capability = PlatformCapability.SEND_IMAGES,
                    contact = contact,
                    images = listOf(ImageSource.LocalFile("temp/demo.png")),
                ),
            )
            val atAllGuard = adapter.guardCapability(
                CapabilityRequest(
                    capability = PlatformCapability.AT_ALL,
                    contact = contact,
                ),
            )

            assertIs<CapabilityGuardResult.Supported>(imageGuard)
            assertIs<CapabilityGuardResult.Supported>(atAllGuard)
        } finally {
            adapter.stop()
        }
    }

    @Test
    fun `llbot adapter should convert local image into base64 payload when send mode is base64`() = runBlocking {
        val transport = FakeTransport()
        transport.lastGatewaySession.onSend = { request ->
            if (request["action"]?.jsonPrimitive?.content == "send_group_msg") {
                transport.lastGatewaySession.emit(okResponse(request["echo"]!!.jsonPrimitive.content))
            }
        }
        val client = createStartedClient(
            transport = transport,
            config = NapCatConfig(
                host = "127.0.0.1",
                port = 3001,
                token = "demo-token",
                sendMode = "base64",
            ),
        )
        val adapter = LlBotAdapter(client)
        val contact = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "100")
        val imageFile = Files.createTempFile("llbot-image-base64", ".png").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            deleteOnExit()
        }

        try {
            assertTrue(adapter.sendMessage(contact, listOf(OutgoingPart.image(ImageSource.LocalFile(imageFile.absolutePath)))))

            val payload = transport.lastGatewaySession.sentTexts.last()
            val imageValue = payload["params"]!!.jsonObject["message"]!!.jsonArray[0]
                .jsonObject["data"]!!.jsonObject["file"]!!.jsonPrimitive.content
            assertTrue(imageValue.startsWith("base64://"), "llbot base64 mode should encode local files before send")
        } finally {
            adapter.stop()
        }
    }

    @Test
    fun `llbot adapter should keep file uri payload when send mode is file`() = runBlocking {
        val transport = FakeTransport()
        transport.lastGatewaySession.onSend = { request ->
            if (request["action"]?.jsonPrimitive?.content == "send_group_msg") {
                transport.lastGatewaySession.emit(okResponse(request["echo"]!!.jsonPrimitive.content))
            }
        }
        val client = createStartedClient(
            transport = transport,
            config = NapCatConfig(
                host = "127.0.0.1",
                port = 3001,
                token = "demo-token",
                sendMode = "file",
            ),
        )
        val adapter = LlBotAdapter(client)
        val contact = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "100")
        val imageFile = Files.createTempFile("llbot-image-file", ".png").toFile().apply {
            writeBytes(byteArrayOf(5, 6, 7, 8))
            deleteOnExit()
        }

        try {
            assertTrue(adapter.sendMessage(contact, listOf(OutgoingPart.image(ImageSource.LocalFile(imageFile.absolutePath)))))

            val payload = transport.lastGatewaySession.sentTexts.last()
            val imageValue = payload["params"]!!.jsonObject["message"]!!.jsonArray[0]
                .jsonObject["data"]!!.jsonObject["file"]!!.jsonPrimitive.content
            assertTrue(imageValue.startsWith("file:///"), "llbot file mode should keep file uri payload")
        } finally {
            adapter.stop()
        }
    }

    @Test
    fun `llbot client should normalize inbound message events into onebot11 events`() = runBlocking {
        val transport = FakeTransport()
        val client = createStartedClient(transport)

        try {
            val eventDeferred = CompletableDeferred<top.bilibili.connector.onebot11.core.OneBot11MessageEvent>()
            // 订阅协程使用 UNDISPATCHED 立即进入 first()，避免 emit 与订阅调度竞争导致事件丢失。
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                eventDeferred.complete(client.eventFlow.first())
            }

            try {
                transport.lastGatewaySession.emit(
                    buildJsonObject {
                        put("post_type", JsonPrimitive("message"))
                        put("message_type", JsonPrimitive("group"))
                        put("message_id", JsonPrimitive(1))
                        put("user_id", JsonPrimitive(200))
                        put("message", JsonArray(listOf(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("data", buildJsonObject {
                                put("text", JsonPrimitive("/bili check"))
                            })
                        })))
                        put("raw_message", JsonPrimitive("/bili check"))
                        put("group_id", JsonPrimitive(100))
                        put("self_id", JsonPrimitive(300))
                    }.toString(),
                )

                val event = withTimeout(1_000) {
                    eventDeferred.await()
                }

                assertEquals("group", event.messageType)
                assertEquals(100L, event.groupId)
                assertEquals("/bili check", event.rawMessage)
            } finally {
                collectJob.cancel()
            }
        } finally {
            client.stop()
        }
    }

    /**
     * 启动 llbot client 后等待连接状态可见，避免测试与异步连接协程竞争。
     */
    private suspend fun createStartedClient(
        transport: FakeTransport,
        config: NapCatConfig = NapCatConfig(
            host = "127.0.0.1",
            port = 3001,
            token = "demo-token",
        ),
    ): LlBotClient {
        val client = LlBotClient(
            config = config,
            transport = transport,
        )
        client.start()
        withTimeout(1_000) {
            while (!client.runtimeStatus().connected) {
                delay(10)
            }
        }
        return client
    }

    /**
     * 构造标准 OneBot11 成功响应，供 fake session 回放 action 结果。
     */
    private fun okResponse(echo: String, data: kotlinx.serialization.json.JsonElement? = null): String {
        return buildJsonObject {
            put("status", JsonPrimitive("ok"))
            put("retcode", JsonPrimitive(0))
            if (data != null) {
                put("data", data)
            }
            put("echo", JsonPrimitive(echo))
        }.toString()
    }

    private inner class FakeTransport : LlBotTransport {
        val lastGatewaySession = FakeGatewaySession()
        var lastUrl: String = ""
        var lastHeaders: Map<String, String> = emptyMap()

        override suspend fun openGateway(url: String, headers: Map<String, String>): LlBotGatewaySession {
            lastUrl = url
            lastHeaders = headers
            return lastGatewaySession
        }

        /**
         * llbot fake transport 不关心底层 OkHttp 资源，只返回空快照保持合同完整。
         */
        override fun runtimeObservability(): PlatformObservabilitySnapshot {
            return PlatformObservabilitySnapshot.empty("fake transport")
        }

        override fun close() = Unit
    }

    private inner class FakeGatewaySession : LlBotGatewaySession {
        private val incomingFlow = MutableSharedFlow<String>(replay = 16, extraBufferCapacity = 16)
        private val closed = CompletableDeferred<Throwable?>()
        val sentTexts = mutableListOf<JsonObject>()
        var onSend: suspend (JsonObject) -> Unit = {}

        override val incoming: Flow<String> = incomingFlow
        override val closeSignal: CompletableDeferred<Throwable?> = closed

        /**
         * 记录客户端发出的 action，并允许测试按 action 内容回放响应。
         */
        override suspend fun sendText(text: String) {
            val parsed = llBotJson.parseToJsonElement(text).jsonObject
            sentTexts += parsed
            onSend(parsed)
        }

        /**
         * 主动关闭 fake session，模拟停机或断线完成信号。
         */
        override suspend fun close(reason: String) {
            if (!closed.isCompleted) {
                closed.complete(null)
            }
        }

        /**
         * 允许测试在运行中向 llbot client 注入入站文本帧。
         */
        suspend fun emit(text: String) {
            incomingFlow.emit(text)
        }
    }
}
