package top.bilibili.core.resource

/**
 * 定义资源停止和业务执行时的超时与告警策略。
 */
enum class ResourceStrictness {
    STRICT {
        override val stopTimeoutMs: Long = 10_000L
        override val businessWarnThresholdMs: Long = 10_000L
        override val businessHardTimeoutMs: Long? = 120_000L
    },
    RELAXED_LONG_RUNNING {
        override val stopTimeoutMs: Long = 60_000L
        override val businessWarnThresholdMs: Long = 180_000L
        override val businessHardTimeoutMs: Long? = null
    },
    ;

    abstract val stopTimeoutMs: Long
    abstract val businessWarnThresholdMs: Long
    abstract val businessHardTimeoutMs: Long?
}
