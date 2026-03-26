package top.bilibili.connector.onebot11.core

data class OneBot11MessageEvent(
    val messageType: String,
    val messageId: Int,
    val userId: Long,
    val message: List<OneBot11MessageSegment>,
    val rawMessage: String,
    val groupId: Long? = null,
    val selfId: Long = 0L,
)

data class OneBot11MessageSegment(
    val type: String,
    val data: Map<String, String> = emptyMap(),
)
