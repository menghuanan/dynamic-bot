package top.bilibili.service

/**
 * 维护全局消息网关注册点，确保业务层始终通过同一发送入口取用网关。
 */
object MessageGatewayProvider {
    @Volatile
    private var gateway: MessageGateway? = null

    /**
     * 在启动阶段注册当前可用的消息网关实现，供业务层统一发送消息。
     */
    fun register(messageGateway: MessageGateway) {
        gateway = messageGateway
    }

    /**
     * 在停机或重载时移除当前网关引用，避免继续向旧适配器发送消息。
     */
    fun unregister() {
        gateway = null
    }

    /**
     * 供启动流程判断网关是否已就绪，避免业务层提前发消息。
     */
    fun isRegistered(): Boolean = gateway != null

    /**
     * 获取已注册的消息网关，并在启动未完成时立即暴露明确错误。
     */
    fun require(): MessageGateway {
        return gateway ?: error("MessageGateway 未初始化，请先完成 Bot 启动。")
    }
}
