package top.bilibili.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.utils.CacheType

class DrawCacheMaintenanceFeatureTest {
    private val tempRoot: Path = Files.createTempDirectory("draw-cache-maintenance")

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `clearSubjectScopedDrawCaches should remove only current subject cache files`() {
        val subject = "group:10001"
        val currentDynamic = createCacheFile(
            CacheType.DRAW_DYNAMIC,
            DrawCacheKeyService.dynamicPath(uid = 123L, dynamicId = "300", subject = subject, color = "#111111")
        )
        val currentLive = createCacheFile(
            CacheType.DRAW_LIVE,
            DrawCacheKeyService.livePath(uid = 123L, liveTime = 1710000000L, subject = subject, color = "#111111")
        )
        val currentSearch = createCacheFile(
            CacheType.DRAW_SEARCH,
            DrawCacheKeyService.searchPath(
                uid = 123L,
                entityType = "dynamic",
                entityId = "300",
                subject = subject,
                color = "#111111"
            )
        )
        val otherSubjectDynamic = createCacheFile(
            CacheType.DRAW_DYNAMIC,
            DrawCacheKeyService.dynamicPath(uid = 123L, dynamicId = "300", subject = "group:10002", color = "#111111")
        )
        val otherUidLive = createCacheFile(
            CacheType.DRAW_LIVE,
            DrawCacheKeyService.livePath(uid = 456L, liveTime = 1710000000L, subject = subject, color = "#111111")
        )

        DrawCacheMaintenanceService.clearSubjectScopedDrawCaches(tempRoot, uid = 123L, subject = subject)

        assertFalse(currentDynamic.exists(), "dynamic cache for current subject should be removed")
        assertFalse(currentLive.exists(), "live cache for current subject should be removed")
        assertFalse(currentSearch.exists(), "link resolve cache for current subject should be removed")
        assertTrue(otherSubjectDynamic.exists(), "different subject cache should remain")
        assertTrue(otherUidLive.exists(), "different uid cache should remain")
    }

    private fun createCacheFile(cacheType: CacheType, relativePath: String): Path {
        val path = tempRoot.resolve(cacheType.path).resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, "cached")
        return path
    }
}