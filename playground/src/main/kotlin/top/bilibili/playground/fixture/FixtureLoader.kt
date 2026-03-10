package top.bilibili.playground.fixture

import kotlinx.serialization.json.Json

object FixtureLoader {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun loadResource(path: String): PlaygroundFixture {
        val stream = FixtureLoader::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalArgumentException("Fixture not found: $path")

        return stream.use {
            json.decodeFromString(PlaygroundFixture.serializer(), it.reader(Charsets.UTF_8).readText())
        }
    }

    fun loadDefaults(): List<PlaygroundFixture> {
        return listOf(
            loadResource("fixtures/dynamic-sample.json"),
            loadResource("fixtures/live-sample.json"),
        )
    }
}