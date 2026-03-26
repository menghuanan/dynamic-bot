package top.bilibili.connector

enum class PlatformCapability {
    SEND_MESSAGE,
    SEND_IMAGES,
    REPLY,
    AT_ALL,
    LINK_RESOLVE,
}

data class CapabilityRequest(
    val capability: PlatformCapability,
    val contact: PlatformContact? = null,
    val images: List<ImageSource> = emptyList(),
)
