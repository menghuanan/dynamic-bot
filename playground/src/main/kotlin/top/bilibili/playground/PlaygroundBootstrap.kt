package top.bilibili.playground

import java.util.concurrent.atomic.AtomicBoolean
import top.bilibili.BiliConfigManager
import top.bilibili.data.BiliImageQuality
import top.bilibili.data.BiliImageTheme
import top.bilibili.loadFonts

object PlaygroundBootstrap {
    private val initialized = AtomicBoolean(false)

    suspend fun ensureInitialized() {
        if (!initialized.compareAndSet(false, true)) {
            return
        }

        try {
            BiliConfigManager.init()
            BiliImageTheme.reload()
            BiliImageQuality.reload()
            loadFonts()
        } catch (error: Throwable) {
            initialized.set(false)
            throw error
        }
    }
}
