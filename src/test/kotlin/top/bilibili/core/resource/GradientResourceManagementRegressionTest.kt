package top.bilibili.core.resource

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class GradientResourceManagementRegressionTest {
    private fun read(path: String): String {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8)
    }

    @Test
    fun `drawing session should expose managed factories for native skia helpers`() {
        val session = read("src/main/kotlin/top/bilibili/skia/DrawingSession.kt")

        assertTrue(
            session.contains("fun createPaint("),
            "DrawingSession should provide managed Paint creation",
        )
        assertTrue(
            session.contains("fun createLinearGradient("),
            "DrawingSession should provide managed linear gradient shader creation",
        )
        assertTrue(
            session.contains("fun createSweepGradient("),
            "DrawingSession should provide managed sweep gradient shader creation",
        )
        assertTrue(
            session.contains("fun createBlendColorFilter("),
            "DrawingSession should provide managed ColorFilter creation",
        )
        assertTrue(
            session.contains("fun createSvg("),
            "DrawingSession should provide managed SVGDOM loading",
        )
    }

    @Test
    fun `gradient draw hot paths should route through drawing session factories`() {
        val dynamicDraw = read("src/main/kotlin/top/bilibili/draw/DynamicDraw.kt")
        val dynamicMajor = read("src/main/kotlin/top/bilibili/draw/DynamicMajorDraw.kt")

        assertTrue(
            dynamicDraw.contains("session.createLinearGradient("),
            "DynamicDraw should create background gradients through DrawingSession",
        )
        assertTrue(
            dynamicDraw.contains("session.createSweepGradient("),
            "DynamicDraw should create outline gradients through DrawingSession",
        )
        assertTrue(
            dynamicDraw.contains("session.createPaint"),
            "DynamicDraw should create temporary Paint instances through DrawingSession",
        )
        assertTrue(
            dynamicMajor.contains("session.createPaint"),
            "DynamicMajorDraw should create temporary Paint instances through DrawingSession",
        )
        assertTrue(
            dynamicMajor.contains("session.createLinearGradient("),
            "DynamicMajorDraw should create temporary gradient shaders through DrawingSession",
        )
    }

    @Test
    fun `draw helper hot paths should stop relying on unmanaged global or inline skia helpers`() {
        val dynamicDraw = read("src/main/kotlin/top/bilibili/draw/DynamicDraw.kt")
        val dynamicModule = read("src/main/kotlin/top/bilibili/draw/DynamicModuleDraw.kt")
        // 登录二维码渲染热路径已迁移到专用渲染器，旧分支仍保留 QrCodeDraw 作为兼容回退入口。
        val loginQrRenderer = if (Files.exists(Path.of("src/main/kotlin/top/bilibili/draw/LoginQrCodeRenderer.kt"))) {
            read("src/main/kotlin/top/bilibili/draw/LoginQrCodeRenderer.kt")
        } else {
            read("src/main/kotlin/top/bilibili/draw/QrCodeDraw.kt")
        }

        assertTrue(
            dynamicModule.contains("session.createSvg("),
            "DynamicModuleDraw should load SVGDOM through DrawingSession",
        )
        assertTrue(
            loginQrRenderer.contains("createBlendColorFilter("),
            "login qr renderer should create ColorFilter through DrawingSession",
        )
        assertTrue(
            loginQrRenderer.contains("createPaint"),
            "login qr renderer should create temporary Paint instances through DrawingSession",
        )
        assertTrue(
            !dynamicDraw.contains("val linkPaint = Paint().apply"),
            "DynamicDraw should not keep unmanaged global Paint instances for text rendering",
        )
        assertTrue(
            !dynamicDraw.contains("val generalPaint = Paint().apply"),
            "DynamicDraw should not keep unmanaged global Paint instances for text rendering",
        )
    }
}
