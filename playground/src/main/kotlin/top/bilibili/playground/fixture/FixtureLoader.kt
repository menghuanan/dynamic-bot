package top.bilibili.playground.fixture

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.bilibili.core.BiliBiliBot
import top.bilibili.data.DynamicItem
import top.bilibili.data.LiveInfo
import top.bilibili.utils.loadResourceBytes

internal data class FixtureAssets(
    val avatarUrl: String,
    val coverUrl: String,
)

object FixtureLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun loadResource(path: String): PlaygroundFixture {
        val stream = FixtureLoader::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalArgumentException("Fixture not found: $path")

        return stream.use {
            decode(it.reader(Charsets.UTF_8).readText())
        }
    }

    fun loadPath(path: Path): PlaygroundFixture {
        return decode(Files.readString(path, StandardCharsets.UTF_8))
    }

    fun savePath(path: Path, fixture: PlaygroundFixture) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            path,
            encode(fixture),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    fun decode(text: String): PlaygroundFixture {
        return json.decodeFromString(PlaygroundFixture.serializer(), text)
    }

    fun encode(fixture: PlaygroundFixture): String {
        return json.encodeToString(PlaygroundFixture.serializer(), fixture)
    }

    fun loadDefaults(): List<PlaygroundFixture> {
        val assets = PreviewFixtureAssets.prepare()
        return FixtureCatalog.defaults(assets)
    }

    private fun PlaygroundFixture.withBundledAssets(assets: FixtureAssets): PlaygroundFixture {
        return when (kind) {
            PlaygroundFixtureKind.DYNAMIC -> copy(
                dynamic = dynamic?.withPreviewAvatar(assets.avatarUrl),
            )
            PlaygroundFixtureKind.LIVE -> copy(
                live = live?.withPreviewAssets(assets.avatarUrl, assets.coverUrl),
            )
        }
    }

    private fun DynamicItem.withPreviewAvatar(avatarUrl: String): DynamicItem {
        return copy(
            modules = modules.copy(
                moduleAuthor = modules.moduleAuthor.copy(face = avatarUrl),
            ),
        )
    }

    private fun LiveInfo.withPreviewAssets(avatarUrl: String, coverUrl: String): LiveInfo {
        return copy(
            face = avatarUrl,
            cover = coverUrl,
        )
    }
}

internal object PreviewFixtureAssets {
    private const val avatarRelativePath = "cache/user/playground-avatar.png"
    private const val coverRelativePath = "cache/images/playground-cover.png"

    fun prepare(): FixtureAssets {
        materialize(avatarRelativePath)
        materialize(coverRelativePath)
        return FixtureAssets(
            avatarUrl = avatarRelativePath,
            coverUrl = coverRelativePath,
        )
    }

    private fun materialize(relativePath: String) {
        val target = BiliBiliBot.dataFolderPath.resolve(relativePath)
        Files.createDirectories(target.parent)
        if (Files.notExists(target)) {
            Files.write(target, loadResourceBytes("image/IMAGE_MISS.png"))
        }
    }
}