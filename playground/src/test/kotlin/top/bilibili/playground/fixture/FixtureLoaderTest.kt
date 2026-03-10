package top.bilibili.playground.fixture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        assertEquals("1000w", state.selectedQualityKey)
        assertEquals("v3", state.selectedThemeKey)
        assertEquals("FanCard", state.cardOrnament)
        assertNull(state.lastResult)
        assertNull(state.lastError)
    }
}