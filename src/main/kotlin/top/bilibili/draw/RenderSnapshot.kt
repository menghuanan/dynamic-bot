package top.bilibili.draw

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.ImageConfig
import top.bilibili.data.BiliImageQuality
import top.bilibili.data.BiliImageTheme
import top.bilibili.data.Quality
import top.bilibili.data.Theme

/**
 * Immutable rendering snapshot for draw-layer preview and production rendering.
 */
data class RenderSnapshot(
    val qualityKey: String,
    val quality: Quality,
    val themeKey: String,
    val theme: Theme,
    val fontFamily: String,
    val cardOrnament: String,
    val colorGenerator: ImageConfig.ColorGenerator,
    val badges: BadgeSnapshot,
    val footerAlign: String,
    val dynamicFooterTemplate: String,
    val liveFooterTemplate: String,
) {
    data class BadgeSnapshot(
        val leftEnabled: Boolean,
        val rightEnabled: Boolean,
    ) {
        val enabled: Boolean get() = leftEnabled || rightEnabled
    }
}

data class RenderSnapshotOverrides(
    val quality: Quality? = null,
    val theme: Theme? = null,
    val fontFamily: String? = null,
    val cardOrnament: String? = null,
    val colorGenerator: ImageConfig.ColorGenerator? = null,
    val badgeLeftEnabled: Boolean? = null,
    val badgeRightEnabled: Boolean? = null,
    val footerAlign: String? = null,
    val dynamicFooterTemplate: String? = null,
    val liveFooterTemplate: String? = null,
)

object RenderSnapshotFactory {
    fun fromConfig(
        config: BiliConfig,
        overrides: RenderSnapshotOverrides = RenderSnapshotOverrides(),
    ): RenderSnapshot {
        val imageConfig = config.imageConfig
        val footerConfig = config.templateConfig.footer

        val leftEnabled = overrides.badgeLeftEnabled ?: imageConfig.badgeEnable.left
        val rightEnabled = overrides.badgeRightEnabled ?: imageConfig.badgeEnable.right
        val qualityTemplate = overrides.quality ?: resolveQuality(imageConfig.quality)
        val theme = overrides.theme ?: resolveTheme(imageConfig.theme)
        val fontFamily = overrides.fontFamily ?: imageConfig.font.split(';').first().substringBefore('.')

        return RenderSnapshot(
            qualityKey = imageConfig.quality,
            quality = qualityTemplate.copy(
                badgeHeight = if (leftEnabled || rightEnabled) qualityTemplate.badgeHeight else 0,
            ),
            themeKey = imageConfig.theme,
            theme = theme,
            fontFamily = fontFamily,
            cardOrnament = overrides.cardOrnament ?: imageConfig.cardOrnament,
            colorGenerator = overrides.colorGenerator ?: imageConfig.colorGenerator,
            badges = RenderSnapshot.BadgeSnapshot(
                leftEnabled = leftEnabled,
                rightEnabled = rightEnabled,
            ),
            footerAlign = (overrides.footerAlign ?: footerConfig.footerAlign).uppercase(),
            dynamicFooterTemplate = overrides.dynamicFooterTemplate ?: footerConfig.dynamicFooter,
            liveFooterTemplate = overrides.liveFooterTemplate ?: footerConfig.liveFooter,
        )
    }

    private fun resolveQuality(qualityKey: String): Quality {
        return when {
            BiliImageQuality.customOverload -> BiliImageQuality.customQuality
            else -> BiliImageQuality.quality[qualityKey] ?: BiliImageQuality.quality.values.first()
        }
    }

    private fun resolveTheme(themeKey: String): Theme {
        return when {
            BiliImageTheme.customOverload -> BiliImageTheme.customTheme
            else -> BiliImageTheme.theme[themeKey] ?: BiliImageTheme.theme.values.first()
        }
    }
}

object RenderSnapshotContext {
    private val snapshotOverride = ThreadLocal<RenderSnapshot?>()

    fun current(): RenderSnapshot {
        return snapshotOverride.get() ?: RenderSnapshotFactory.fromConfig(BiliConfigManager.config)
    }

    fun <T> withSnapshot(snapshot: RenderSnapshot, block: () -> T): T {
        val previous = snapshotOverride.get()
        snapshotOverride.set(snapshot)
        return try {
            block()
        } finally {
            snapshotOverride.set(previous)
        }
    }
}

fun currentRenderSnapshot(): RenderSnapshot = RenderSnapshotContext.current()

fun <T> withRenderSnapshot(snapshot: RenderSnapshot, block: () -> T): T {
    return RenderSnapshotContext.withSnapshot(snapshot, block)
}