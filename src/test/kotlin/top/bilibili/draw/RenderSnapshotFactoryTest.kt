package top.bilibili.draw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import top.bilibili.BiliConfig
import top.bilibili.FooterConfig
import top.bilibili.ImageConfig
import top.bilibili.TemplateConfig
import top.bilibili.data.BiliImageQuality
import top.bilibili.data.BiliImageTheme

class RenderSnapshotFactoryTest {
    @Test
    fun `from config should resolve named quality theme and footer values`() {
        val config = BiliConfig(
            imageConfig = ImageConfig(
                quality = "800w",
                theme = "v2",
                font = "Source Han Sans SC",
                cardOrnament = "QrCode",
                badgeEnable = ImageConfig.BadgeEnable(left = false, right = true),
            ),
            templateConfig = TemplateConfig(
                footer = FooterConfig(
                    dynamicFooter = "dynamic-{name}",
                    liveFooter = "live-{name}",
                    footerAlign = "RIGHT",
                ),
            ),
        )

        val snapshot = RenderSnapshotFactory.fromConfig(config)

        assertEquals("800w", snapshot.qualityKey)
        assertEquals("v2", snapshot.themeKey)
        assertEquals("Source Han Sans SC", snapshot.fontFamily)
        assertEquals("QrCode", snapshot.cardOrnament)
        assertFalse(snapshot.badges.leftEnabled)
        assertEquals("RIGHT", snapshot.footerAlign)
        assertEquals("dynamic-{name}", snapshot.dynamicFooterTemplate)
        assertEquals("live-{name}", snapshot.liveFooterTemplate)
        assertEquals(BiliImageTheme.theme.getValue("v2"), snapshot.theme)
    }

    @Test
    fun `factory should copy quality before badge mutation`() {
        val config = BiliConfig(
            imageConfig = ImageConfig(
                quality = "800w",
                badgeEnable = ImageConfig.BadgeEnable(left = false, right = false),
            ),
        )
        val original = BiliImageQuality.quality.getValue("800w")

        val snapshot = RenderSnapshotFactory.fromConfig(config)

        assertNotSame(original, snapshot.quality)
        assertEquals(0, snapshot.quality.badgeHeight)
        assertEquals(36, original.badgeHeight)
    }

    @Test
    fun `factory should allow in-memory overrides without mutating config`() {
        val config = BiliConfig()
        val overrides = RenderSnapshotOverrides(
            fontFamily = "Debug Font",
            footerAlign = "CENTER",
            dynamicFooterTemplate = "override-dynamic",
            liveFooterTemplate = "override-live",
            badgeLeftEnabled = false,
            badgeRightEnabled = true,
            cardOrnament = "QrCode",
            quality = BiliImageQuality.quality.getValue("1200w"),
            theme = BiliImageTheme.theme.getValue("v3RainbowOutline"),
        )

        val snapshot = RenderSnapshotFactory.fromConfig(config, overrides)

        assertEquals("Debug Font", snapshot.fontFamily)
        assertEquals("CENTER", snapshot.footerAlign)
        assertEquals("override-dynamic", snapshot.dynamicFooterTemplate)
        assertEquals("override-live", snapshot.liveFooterTemplate)
        assertFalse(snapshot.badges.leftEnabled)
        assertEquals(true, snapshot.badges.rightEnabled)
        assertEquals("QrCode", snapshot.cardOrnament)
        assertEquals(1200, snapshot.quality.imageWidth)
        assertEquals("", config.imageConfig.font)
        assertEquals("LEFT", config.templateConfig.footer.footerAlign)
    }
}