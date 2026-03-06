package top.bilibili.core.resource

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
