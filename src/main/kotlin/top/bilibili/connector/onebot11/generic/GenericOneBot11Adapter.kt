package top.bilibili.connector.onebot11.generic

import top.bilibili.connector.CapabilityGuardResult
import top.bilibili.connector.CapabilityRequest
import top.bilibili.connector.ImageSource
import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.onebot11.OneBot11Adapter
import top.bilibili.connector.onebot11.core.OneBot11Transport

class GenericOneBot11Adapter(
    transport: OneBot11Transport,
) : OneBot11Adapter(transport) {
    /**
     * generic OneBot11 先按保守语义暴露能力：@全体直接声明不支持，本地/二进制图片只允许调用方走显式降级。
     */
    override suspend fun guardCapability(request: CapabilityRequest): CapabilityGuardResult {
        if (request.capability == PlatformCapability.AT_ALL) {
            return CapabilityGuardResult.Unsupported(reason = "generic OneBot11 does not support @全体")
        }
        if (
            request.capability == PlatformCapability.SEND_IMAGES &&
            request.images.any { image -> image is ImageSource.LocalFile || image is ImageSource.Binary }
        ) {
            return CapabilityGuardResult.Degraded(reason = "generic OneBot11 requires remote image URLs")
        }
        return super.guardCapability(request)
    }
}
