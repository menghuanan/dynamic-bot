package top.bilibili.service

import top.bilibili.BiliData

data class ColorBindingMigrationSummary(
    val scannedCount: Int,
    val changedCount: Int,
    val unchangedCount: Int,
)

fun migrateSubjectScopedColorBindings(): ColorBindingMigrationSummary {
    var scannedCount = 0
    var changedCount = 0
    var unchangedCount = 0

    BiliData.dynamicColorByUid.forEach { (_, subjectBindings) ->
        subjectBindings.entries.forEach { entry ->
            scannedCount++
            val original = entry.value
            val syntacticColor = normalizeGradientColorInput(original)
            val normalized = syntacticColor?.let {
                normalizeSubjectScopedGradientColor(it, NormalizationContext.STARTUP_MIGRATION)
            }

            if (normalized == null) {
                unchangedCount++
                return@forEach
            }

            if (normalized.normalizedColor != original) {
                entry.setValue(normalized.normalizedColor)
                changedCount++
            } else {
                unchangedCount++
            }
        }
    }

    return ColorBindingMigrationSummary(
        scannedCount = scannedCount,
        changedCount = changedCount,
        unchangedCount = unchangedCount,
    )
}
