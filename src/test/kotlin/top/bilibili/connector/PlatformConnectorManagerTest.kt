package top.bilibili.connector

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import top.bilibili.config.BotConfig

class PlatformConnectorManagerTest {
    @Test
    fun `start should only create one adapter generation while manager is running`() {
        val createdAdapters = mutableListOf<RecordingAdapter>()
        val manager = PlatformConnectorManager(
            config = BotConfig(),
            adapterFactory = {
                RecordingAdapter().also(createdAdapters::add)
            },
        )

        manager.start()
        manager.start()

        assertEquals(1, createdAdapters.size)
        assertEquals(1, createdAdapters.single().startCount)
    }

    @Test
    fun `stop followed by start should replace stopped adapter generation`() = runBlocking {
        val createdAdapters = mutableListOf<RecordingAdapter>()
        val manager = PlatformConnectorManager(
            config = BotConfig(),
            adapterFactory = {
                RecordingAdapter().also(createdAdapters::add)
            },
        )

        manager.start()
        val firstGeneration = createdAdapters.single()
        manager.stop()
        manager.start()
        val secondGeneration = createdAdapters.last()

        assertEquals(2, createdAdapters.size)
        assertNotSame(firstGeneration, secondGeneration)
        assertEquals(1, firstGeneration.stopCount)
        assertEquals(1, secondGeneration.startCount)
        assertEquals(0, secondGeneration.stopCount)
    }

    @Test
    fun `start failure should discard half initialized adapter generation`() {
        var creationCount = 0
        val createdAdapters = mutableListOf<RecordingAdapter>()
        val manager = PlatformConnectorManager(
            config = BotConfig(),
            adapterFactory = {
                creationCount++
                RecordingAdapter(failOnStart = creationCount == 1).also(createdAdapters::add)
            },
        )

        assertFailsWith<IllegalStateException> {
            manager.start()
        }

        assertFalse(manager.isInitialized())
        manager.start()

        assertEquals(2, createdAdapters.size)
        assertEquals(1, createdAdapters[0].startCount)
        assertEquals(1, createdAdapters[1].startCount)
    }

    /**
     * 记录 manager 对 adapter 生命周期调用次数，确保测试只验证代际切换语义。
     */
    private class RecordingAdapter(
        private val failOnStart: Boolean = false,
    ) : PlatformAdapter {
        var startCount: Int = 0
            private set
        var stopCount: Int = 0
            private set

        override val eventFlow = emptyFlow<PlatformInboundMessage>()

        override fun start() {
            startCount++
            if (failOnStart) {
                throw IllegalStateException("start failure")
            }
        }

        override suspend fun stop() {
            stopCount++
        }

        override fun declaredCapabilities(): Set<PlatformCapability> = emptySet()

        override suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean = false

        override fun runtimeStatus(): PlatformRuntimeStatus = PlatformRuntimeStatus(
            connected = false,
            reconnectAttempts = 0,
        )

        override suspend fun isContactReachable(contact: PlatformContact): Boolean = false

        override suspend fun canAtAll(contact: PlatformContact): Boolean = false
    }
}
