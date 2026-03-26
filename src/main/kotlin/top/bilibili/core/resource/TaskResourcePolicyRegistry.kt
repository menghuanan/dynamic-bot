package top.bilibili.core.resource

data class TaskResourcePolicy(
    val taskName: String,
    val strictness: ResourceStrictness,
    val reason: String,
)

object TaskResourcePolicyRegistry {
    private val policies = mapOf(
        "ListenerTasker" to TaskResourcePolicy("ListenerTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "持续监听平台事件流"),
        "DynamicCheckTasker" to TaskResourcePolicy("DynamicCheckTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "周期轮询任务"),
        "LiveCheckTasker" to TaskResourcePolicy("LiveCheckTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "周期轮询任务"),
        "LiveCloseCheckTasker" to TaskResourcePolicy("LiveCloseCheckTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "周期轮询任务"),
        "DynamicMessageTasker" to TaskResourcePolicy("DynamicMessageTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "持续消费消息通道"),
        "LiveMessageTasker" to TaskResourcePolicy("LiveMessageTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "持续消费消息通道"),
        "SendTasker" to TaskResourcePolicy("SendTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "持续消费发送队列"),
        "CacheClearTasker" to TaskResourcePolicy("CacheClearTasker", ResourceStrictness.STRICT, "周期缓存维护"),
        "LogClearTasker" to TaskResourcePolicy("LogClearTasker", ResourceStrictness.STRICT, "周期日志维护"),
        "SkiaCleanupTasker" to TaskResourcePolicy("SkiaCleanupTasker", ResourceStrictness.STRICT, "周期 Skia 维护"),
        "ProcessGuardian" to TaskResourcePolicy("ProcessGuardian", ResourceStrictness.STRICT, "系统守护与监控"),
    )

    fun policyOf(taskName: String): TaskResourcePolicy? = policies[taskName]

    fun validateCoverage(startupTaskNames: List<String>) {
        val missing = startupTaskNames.filter { !policies.containsKey(it) }
        require(missing.isEmpty()) {
            "任务资源策略未覆盖: ${missing.joinToString(", ")}"
        }
    }
}


