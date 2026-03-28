package top.bilibili.service

import java.nio.file.Path
import top.bilibili.utils.CacheType
import top.bilibili.utils.cachePath

/**
 * 集中处理按会话主题色隔离的绘图缓存清理，避免颜色切换后复用旧图。
 */
object DrawCacheMaintenanceService {
    /**
     * 以默认缓存根目录清理指定 UID 在目标会话下的绘图缓存。
     */
    fun clearSubjectScopedDrawCaches(uid: Long, subject: String) {
        clearSubjectScopedDrawCaches(cachePath, uid, subject)
    }

    /**
     * 允许调用方指定缓存根目录，便于测试和启动期复用同一清理逻辑。
     */
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
