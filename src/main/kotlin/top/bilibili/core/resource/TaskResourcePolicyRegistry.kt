package top.bilibili.core.resource

/**
 * 描述单个任务对应的资源约束策略。
 */
data class TaskResourcePolicy(
    val taskName: String,
    val strictness: ResourceStrictness,
    val reason: String,
)

/**
 * 维护任务名称到资源策略的映射表。
 */
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

    /**
     * 根据任务名查询对应的资源策略。
     */
    fun policyOf(taskName: String): TaskResourcePolicy? = policies[taskName]

    /**
     * 校验启动任务列表是否都配置了资源策略。
     */
    fun validateCoverage(startupTaskNames: List<String>) {
        val missing = startupTaskNames.filter { !policies.containsKey(it) }
        require(missing.isEmpty()) {
            "任务资源策略未覆盖: ${missing.joinToString(", ")}"
        }
    }
}


