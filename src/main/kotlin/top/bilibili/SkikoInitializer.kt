package top.bilibili

/**
 * Skiko 图形库初始化配置
 * 必须在任何 Skiko 类加载之前调用
 */
object SkikoInitializer {

    private var initialized = false

    /**
     * 初始化 Skiko 配置
     * 强制使用软件渲染，避免硬件加速导致的 native 崩溃
     */
    @JvmStatic
    fun initialize() {
        // 只允许初始化一次，是为了避免运行中反复修改图形后端属性带来不可预期行为。
        if (initialized) return
        initialized = true

        // 强制使用软件渲染 API (关键配置)
        System.setProperty("skiko.renderApi", "SOFTWARE")

        // 禁用硬件加速
        System.setProperty("skiko.hardwareAcceleration", "false")
        System.setProperty("skiko.vsync.enabled", "false")

        // 禁用 GPU 相关功能
        System.setProperty("skiko.gpu.enabled", "false")

        // 禁用 OpenGL 和 XRender (Java2D)
        System.setProperty("sun.java2d.opengl", "false")
        System.setProperty("sun.java2d.xrender", "false")

        // 禁用 Direct3D (Windows)
        System.setProperty("sun.java2d.d3d", "false")
        System.setProperty("sun.java2d.noddraw", "true")

        // 统一保持 AWT headless=true，避免容器内尝试连接 X11 display 产生额外初始化成本。
        System.setProperty("java.awt.headless", "true")
    }
}
