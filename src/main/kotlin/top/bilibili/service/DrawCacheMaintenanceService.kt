package top.bilibili.service

import java.nio.file.Path
import top.bilibili.utils.CacheType
import top.bilibili.utils.cachePath

object DrawCacheMaintenanceService {
    fun clearSubjectScopedDrawCaches(uid: Long, subject: String) {
        clearSubjectScopedDrawCaches(cachePath, uid, subject)
    }

    fun clearSubjectScopedDrawCaches(root: Path, uid: Long, subject: String) {
        val scopeDirectory = DrawCacheKeyService.subjectScopeDirectory(subject)
        listOf(CacheType.DRAW_DYNAMIC, CacheType.DRAW_LIVE, CacheType.DRAW_SEARCH)
            .map { root.resolve(it.path).resolve(uid.toString()).resolve(scopeDirectory) }
            .forEach { scopedDirectory ->
                val directory = scopedDirectory.toFile()
                if (directory.exists()) {
                    directory.deleteRecursively()
                }
            }
    }
}