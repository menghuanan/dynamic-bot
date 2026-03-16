package top.bilibili.service

import top.bilibili.BiliConfig
import top.bilibili.EnableConfig
import top.bilibili.LinkResolveConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureSwitchServiceTest {
    @Test
    fun `push draw requires both global and push switches`() {
        val config = BiliConfig(
            enableConfig = EnableConfig(drawEnable = true, pushDrawEnable = true),
            linkResolveConfig = LinkResolveConfig(drawEnable = true),
        )

        assertTrue(FeatureSwitchService.canRenderPushDraw(config))
        assertFalse(
            FeatureSwitchService.canRenderPushDraw(
                config.copy(enableConfig = config.enableConfig.copy(pushDrawEnable = false))
            )
        )
        assertFalse(
            FeatureSwitchService.canRenderPushDraw(
                config.copy(enableConfig = config.enableConfig.copy(drawEnable = false))
            )
        )
    }

    @Test
    fun `link resolve draw requires both global and resolve switches`() {
        val config = BiliConfig(
            enableConfig = EnableConfig(drawEnable = true, pushDrawEnable = true),
            linkResolveConfig = LinkResolveConfig(drawEnable = true),
        )

        assertTrue(FeatureSwitchService.canRenderLinkResolveDraw(config))
        assertFalse(
            FeatureSwitchService.canRenderLinkResolveDraw(
                config.copy(linkResolveConfig = config.linkResolveConfig.copy(drawEnable = false))
            )
        )
        assertFalse(
            FeatureSwitchService.canRenderLinkResolveDraw(
                config.copy(enableConfig = config.enableConfig.copy(drawEnable = false))
            )
        )
    }

    @Test
    fun `login qr draw stays available regardless of draw switches`() {
        val config = BiliConfig(
            enableConfig = EnableConfig(drawEnable = false, pushDrawEnable = false),
            linkResolveConfig = LinkResolveConfig(drawEnable = false),
        )

        assertTrue(FeatureSwitchService.canRenderLoginQr(config))
    }

    @Test
    fun `managed admin notice follows notify switch and skips self-notify for admin subject`() {
        val config = BiliConfig(
            admin = 42L,
            enableConfig = EnableConfig(notifyEnable = true),
        )

        assertTrue(FeatureSwitchService.canSendManagedAdminNotice(config, subject = 7L))
        assertFalse(FeatureSwitchService.canSendManagedAdminNotice(config, subject = 42L))
        assertFalse(
            FeatureSwitchService.canSendManagedAdminNotice(
                config.copy(enableConfig = config.enableConfig.copy(notifyEnable = false)),
                subject = 7L
            )
        )
    }

    @Test
    fun `direct managed admin notice only depends on notify switch`() {
        val config = BiliConfig(enableConfig = EnableConfig(notifyEnable = true))

        assertTrue(FeatureSwitchService.canSendManagedAdminNotice(config))
        assertFalse(
            FeatureSwitchService.canSendManagedAdminNotice(
                config.copy(enableConfig = config.enableConfig.copy(notifyEnable = false))
            )
        )
    }
}
