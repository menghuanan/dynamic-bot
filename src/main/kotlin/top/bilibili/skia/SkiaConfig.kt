package top.bilibili.skia

/**
 * Skia 资源管理配置
 */
object SkiaConfig {
    // 队列配置
    var maxQueueSize: Int = 20
    var maxConcurrent: Int = 2

    // 超时配置
    var idleTimeoutMs: Long = 60_000L
    var cleanupIntervalMs: Long = 300_000L
    var drawingTimeoutMs: Long = 60_000L

    // 内存阈值
    var memoryWarningThreshold: Double = 0.70
    var memoryCriticalThreshold: Double = 0.85

    // 子进程配置
    var workerRestartIntervalMs: Long = 24 * 3600 * 1000L
    var workerMaxMemoryMb: Int = 512
    var workerIdleTimeoutMs: Long = 120_000L

    // 模式配置
    var enableWorkerProcess: Boolean = true
    var autoSwitchMode: Boolean = true
}
