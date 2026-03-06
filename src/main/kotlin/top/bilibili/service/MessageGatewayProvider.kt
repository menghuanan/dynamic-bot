package top.bilibili.service

object MessageGatewayProvider {
    @Volatile
    private var gateway: MessageGateway? = null

    fun register(messageGateway: MessageGateway) {
        gateway = messageGateway
    }

    fun unregister() {
        gateway = null
    }

    fun isRegistered(): Boolean = gateway != null

    fun require(): MessageGateway {
        return gateway ?: error("MessageGateway 未初始化，请先完成 Bot 启动。")
    }
}
