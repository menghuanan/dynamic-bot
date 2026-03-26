package top.bilibili.connector

import top.bilibili.core.BiliBiliBot

object PlatformCapabilityService {
    suspend fun isGroupReachable(groupId: Long): Boolean {
        if (!BiliBiliBot.isPlatformAdapterInitialized()) {
            return false
        }
        return BiliBiliBot.platformAdapter.isGroupReachable(groupId)
    }

    suspend fun canAtAllInGroup(groupId: Long): Boolean {
        if (!BiliBiliBot.isPlatformAdapterInitialized()) {
            return false
        }
        return BiliBiliBot.platformAdapter.canAtAll(groupId)
    }
}
