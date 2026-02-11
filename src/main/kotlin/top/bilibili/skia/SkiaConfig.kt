package top.bilibili.skia

/**
 * Skia 资源管理配置
 */
object SkiaConfig {
    // 队列配置

    /** 最大队列长度，超过此值的绘图请求将被拒绝 (must be > 0) */
    @Volatile
    var maxQueueSize: Int = 20

    /** 最大并发绘图数量 (must be > 0) */
    @Volatile
    var maxConcurrent: Int = 2

    // 超时配置

    /** 资源空闲超时时间（毫秒），超过此时间未使用的资源将被清理 (must be > 0) */
    @Volatile
    var idleTimeoutMs: Long = 60_000L

    /** 资源清理检查间隔（毫秒） (must be > 0) */
    @Volatile
    var cleanupIntervalMs: Long = 300_000L

    /** 单次绘图操作超时时间（毫秒） (must be > 0) */
    @Volatile
    var drawingTimeoutMs: Long = 60_000L

    // 内存阈值

    /** 内存警告阈值，超过此比例将触发警告 (must be in 0.0..1.0) */
    @Volatile
    var memoryWarningThreshold: Double = 0.70

    /** 内存临界阈值，超过此比例将触发紧急清理 (must be in 0.0..1.0, must be > memoryWarningThreshold) */
    @Volatile
    var memoryCriticalThreshold: Double = 0.85

    // 子进程配置

    /** Worker 进程重启间隔（毫秒），定期重启以防止内存泄漏累积 (must be > 0) */
    @Volatile
    var workerRestartIntervalMs: Long = 24 * 3600 * 1000L

    /** Worker 进程最大内存限制（MB） (must be > 0) */
    @Volatile
    var workerMaxMemoryMb: Int = 512

    /** Worker 进程空闲超时时间（毫秒） (must be > 0) */
    @Volatile
    var workerIdleTimeoutMs: Long = 120_000L

    // 模式配置

    /** 是否启用子进程模式 (worker process mode) */
    @Volatile
    var enableWorkerProcess: Boolean = true

    /** 是否自动根据内存压力切换模式 */
    @Volatile
    var autoSwitchMode: Boolean = true

    /**
     * 验证配置参数的有效性
     * @throws IllegalArgumentException 如果配置无效
     */
    fun validate() {
        require(maxQueueSize > 0) { "maxQueueSize must be > 0, got $maxQueueSize" }
        require(maxConcurrent > 0) { "maxConcurrent must be > 0, got $maxConcurrent" }
        require(idleTimeoutMs > 0) { "idleTimeoutMs must be > 0, got $idleTimeoutMs" }
        require(cleanupIntervalMs > 0) { "cleanupIntervalMs must be > 0, got $cleanupIntervalMs" }
        require(drawingTimeoutMs > 0) { "drawingTimeoutMs must be > 0, got $drawingTimeoutMs" }
        require(memoryWarningThreshold in 0.0..1.0) { "memoryWarningThreshold must be in 0.0..1.0, got $memoryWarningThreshold" }
        require(memoryCriticalThreshold in 0.0..1.0) { "memoryCriticalThreshold must be in 0.0..1.0, got $memoryCriticalThreshold" }
        require(memoryWarningThreshold < memoryCriticalThreshold) {
            "memoryWarningThreshold ($memoryWarningThreshold) must be < memoryCriticalThreshold ($memoryCriticalThreshold)"
        }
        require(workerRestartIntervalMs > 0) { "workerRestartIntervalMs must be > 0, got $workerRestartIntervalMs" }
        require(workerMaxMemoryMb > 0) { "workerMaxMemoryMb must be > 0, got $workerMaxMemoryMb" }
        require(workerIdleTimeoutMs > 0) { "workerIdleTimeoutMs must be > 0, got $workerIdleTimeoutMs" }
    }
}
