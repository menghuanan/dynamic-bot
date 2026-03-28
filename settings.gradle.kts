// 本文件主要工作流程：
// 1. 在工程初始化最早阶段声明插件解析仓库，保证后续构建脚本能稳定解析插件依赖。
// 2. 固定根工程名称，保证构建产物、IDE 导入结果和发布识别名称保持一致。
pluginManagement {
    repositories {
        // 步骤 1：先检查本地仓库。
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        // 步骤 2：补充 Kotlin EAP 仓库。
        maven("https://dl.bintray.com/kotlin/kotlin-eap")
    }
}
rootProject.name = "dynamic-bot"
