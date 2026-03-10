package top.bilibili.playground.fixture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.bilibili.core.BiliBiliBot
import top.bilibili.playground.state.PlaygroundState

class FixtureLoaderTest {
    @Test
    fun `loader should deserialize bundled dynamic fixture`() {
        val fixture = FixtureLoader.loadResource("fixtures/dynamic-sample.json")

        assertEquals(PlaygroundFixtureKind.DYNAMIC, fixture.kind)
        assertEquals("dynamic-sample", fixture.id)
        assertNotNull(fixture.dynamic)
        assertNull(fixture.live)
    }

    @Test
    fun `default fixtures should hydrate local bundled preview assets`() {
        val fixtures = FixtureLoader.loadDefaults()
        val dynamic = fixtures.first { it.id == "dynamic-word" }.dynamic!!
        val live = fixtures.first { it.id == "live-room" }.live!!

        assertEquals("cache/user/playground-avatar.png", dynamic.modules.moduleAuthor.face)
        assertEquals("cache/user/playground-avatar.png", live.face)
        assertEquals("cache/images/playground-cover.png", live.cover)
        assertTrue(Files.exists(BiliBiliBot.dataFolderPath.resolve("cache/user/playground-avatar.png")))
        assertTrue(Files.exists(BiliBiliBot.dataFolderPath.resolve("cache/images/playground-cover.png")))
    }

    @Test
    fun `default fixtures should expose diverse dynamic subtype catalog`() {
        val ids = FixtureLoader.loadDefaults().map { it.id }

        assertTrue(ids.contains("dynamic-word"))
        assertTrue(ids.contains("dynamic-forward"))
        assertTrue(ids.contains("dynamic-archive"))
        assertTrue(ids.contains("dynamic-draw"))
        assertTrue(ids.contains("dynamic-article"))
        assertTrue(ids.contains("dynamic-music"))
        assertTrue(ids.contains("dynamic-live-card"))
        assertTrue(ids.contains("dynamic-live-rcmd"))
        assertTrue(ids.contains("dynamic-common"))
        assertTrue(ids.contains("dynamic-opus"))
        assertTrue(ids.contains("dynamic-pgc"))
        assertTrue(ids.contains("dynamic-ugc-season"))
        assertTrue(ids.contains("dynamic-additional-common"))
        assertTrue(ids.contains("dynamic-additional-reserve"))
        assertTrue(ids.contains("dynamic-additional-vote"))
        assertTrue(ids.contains("dynamic-additional-ugc"))
        assertTrue(ids.contains("dynamic-additional-goods"))
        assertTrue(ids.contains("dynamic-additional-lottery"))
        assertTrue(ids.contains("live-room"))
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test
    fun `fixtures should round trip through editable json`() {
        val fixture = FixtureLoader.loadDefaults().first { it.id == "dynamic-word" }
        val json = FixtureLoader.encode(fixture)
        val edited = FixtureLoader.decode(
            json.replace("sample-up", "custom-up").replace("hello playground", "custom body")
        )

        assertEquals("custom-up", edited.dynamic!!.modules.moduleAuthor.name)
        assertEquals("custom body", edited.dynamic.modules.moduleDynamic.desc!!.text)
    }

    @Test
    fun `fixtures should round trip through local file storage`() {
        val fixture = FixtureLoader.loadDefaults().first { it.id == "dynamic-archive" }
        val path = Files.createTempFile("playground-fixture", ".json")

        try {
            FixtureLoader.savePath(path, fixture)
            val loaded = FixtureLoader.loadPath(path)

            assertEquals(fixture.id, loaded.id)
            assertEquals(fixture.label, loaded.label)
            assertEquals(fixture.dynamic!!.typeStr, loaded.dynamic!!.typeStr)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `loader should fail with readable error for missing fixture`() {
        val error = assertFailsWith<IllegalArgumentException> {
            FixtureLoader.loadResource("fixtures/missing.json")
        }

        assertEquals(true, error.message?.contains("fixtures/missing.json"))
    }

    @Test
    fun `playground state defaults should be stable`() {
        val state = PlaygroundState()

        assertEquals(emptyList(), state.fixtures)
        assertEquals("", state.selectedFixtureId)
        assertEquals("", state.fixtureJson)
        assertEquals("", state.fixtureFilePath)
        assertEquals("1000w", state.selectedQualityKey)
        assertEquals("v3", state.selectedThemeKey)
        assertEquals("FanCard", state.cardOrnament)
        assertNull(state.lastResult)
        assertNull(state.lastError)
    }
}
