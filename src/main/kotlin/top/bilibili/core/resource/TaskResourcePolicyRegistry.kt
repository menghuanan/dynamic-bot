package top.bilibili.core.resource

data class TaskResourcePolicy(
    val taskName: String,
    val strictness: ResourceStrictness,
    val reason: String,
)

object TaskResourcePolicyRegistry {
    private val policies = mapOf(
        "ListenerTasker" to TaskResourcePolicy("ListenerTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "持续监听 NapCat 事件流"),
        "DynamicCheckTasker" to TaskResourcePolicy("DynamicCheckTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "鍛ㄦ湡杞浠诲姟"),
        "LiveCheckTasker" to TaskResourcePolicy("LiveCheckTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "鍛ㄦ湡杞浠诲姟"),
        "LiveCloseCheckTasker" to TaskResourcePolicy("LiveCloseCheckTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "鍛ㄦ湡杞浠诲姟"),
        "DynamicMessageTasker" to TaskResourcePolicy("DynamicMessageTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "鎸佺画娑堣垂娑堟伅閫氶亾"),
        "LiveMessageTasker" to TaskResourcePolicy("LiveMessageTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "鎸佺画娑堣垂娑堟伅閫氶亾"),
        "SendTasker" to TaskResourcePolicy("SendTasker", ResourceStrictness.RELAXED_LONG_RUNNING, "持续消费发送队列"),
        "CacheClearTasker" to TaskResourcePolicy("CacheClearTasker", ResourceStrictness.STRICT, "鍛ㄦ湡缂撳瓨缁存姢"),
        "LogClearTasker" to TaskResourcePolicy("LogClearTasker", ResourceStrictness.STRICT, "鍛ㄦ湡鏃ュ織缁存姢"),
        "SkiaCleanupTasker" to TaskResourcePolicy("SkiaCleanupTasker", ResourceStrictness.STRICT, "鍛ㄦ湡 Skia 缁存姢"),
        "ProcessGuardian" to TaskResourcePolicy("ProcessGuardian", ResourceStrictness.STRICT, "系统守护与监控"),
    )

    fun policyOf(taskName: String): TaskResourcePolicy? = policies[taskName]

    fun validateCoverage(startupTaskNames: List<String>) {
        val missing = startupTaskNames.filter { !policies.containsKey(it) }
        require(missing.isEmpty()) {
            "浠诲姟璧勬簮绛栫暐鏈鐩? ${missing.joinToString(", ")}"
        }
    }
}


