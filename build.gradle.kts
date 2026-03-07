plugins {
    val kotlinVersion = "2.0.0"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    // 独立应用程序插件
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "top.bilibili"
val releaseVersion = (findProperty("releaseVersion") as String?) ?: "1.7.1"
version = releaseVersion

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// 配置主类
application {
    mainClass.set("top.bilibili.MainKt")
}

// Java 版本
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Kotlin 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // HTTP 客户端
    implementation("io.ktor:ktor-client-okhttp:3.0.3") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-client-encoding:3.0.3") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3") {
        exclude(group = "org.slf4j")
    }

    // WebSocket 客户端（用于 NapCat）
    implementation("io.ktor:ktor-client-websockets:3.0.3") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3") {
        exclude(group = "org.slf4j")
    }

    // JSON 序列化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // 二维码生成
    implementation("com.google.zxing:javase:3.5.0")

    // Skiko 图片绘制 (Linux x64, Windows x64)
    // 使用 0.8.15 版本
    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.15")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.8.15")

    // 日志系统
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("org.slf4j:slf4j-api:2.0.9")

    // YAML 配置解析
    implementation("com.charleskorn.kaml:kaml:0.61.0")

    // 测试
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Shadow JAR 配置 - 打包所有依赖
tasks.shadowJar {
    archiveBaseName.set("dynamic-bot")
    archiveClassifier.set("")
    archiveVersion.set(version.toString())

    manifest {
        attributes(
            "Main-Class" to "top.bilibili.MainKt",
            "Multi-Release" to "true"
        )
    }

    // 合并服务文件
    mergeServiceFiles()

    // 排除签名文件
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

val generatedDistributionScriptsDir = layout.buildDirectory.dir("generated/distribution-scripts")

val createDistributionStartScripts = tasks.register("createDistributionStartScripts") {
    val outputDir = generatedDistributionScriptsDir.get().asFile
    outputs.dir(outputDir)

    doLast {
        outputDir.mkdirs()

        outputDir.resolve("start.bat").writeText(
            """
            @echo off
            chcp 65001 >nul
            cd /d "%~dp0.."

            set JAVA_OPTS=-Xms512m -Xmx2g
            set JAVA_OPTS=%JAVA_OPTS% -Dfile.encoding=UTF-8
            set JAVA_OPTS=%JAVA_OPTS% -Duser.timezone=Asia/Shanghai
            set JAVA_OPTS=%JAVA_OPTS% -Dskiko.renderApi=SOFTWARE
            set JAVA_OPTS=%JAVA_OPTS% -Dskiko.hardwareAcceleration=false

            java %JAVA_OPTS% -jar lib\dynamic-bot-${version}.jar
            pause
            """.trimIndent()
        )

        outputDir.resolve("start.sh").writeText(
            """
            #!/bin/bash
            cd "${'$'}(dirname "${'$'}0")/.."

            JAVA_OPTS="-Xms512m -Xmx2g"
            JAVA_OPTS="${'$'}JAVA_OPTS -Dfile.encoding=UTF-8"
            JAVA_OPTS="${'$'}JAVA_OPTS -Duser.timezone=Asia/Shanghai"
            JAVA_OPTS="${'$'}JAVA_OPTS -Dskiko.renderApi=SOFTWARE"
            JAVA_OPTS="${'$'}JAVA_OPTS -Dskiko.hardwareAcceleration=false"

            java ${'$'}JAVA_OPTS -jar lib/dynamic-bot-${version}.jar
            """.trimIndent()
        )

        outputDir.resolve("start.sh").setExecutable(true)
    }
}
// Distribution 配置
distributions {
    main {
        contents {
            from(tasks.shadowJar) {
                into("lib")
            }
            from("README.md") {
                into("")
            }
            from("src/main/resources") {
                into("resources")
                exclude("logback")
            }
            from(createDistributionStartScripts) {
                into("bin")
                fileMode = 0b111101101 // 755
            }
        }
    }
}

// 设置 distribution 任务的重复文件处理策略
tasks.withType<Tar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Zip> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// 修复 startScripts 和 shadowJar 的依赖关系
tasks.named("startScripts") {
    dependsOn("shadowJar")
}

// 修复 startShadowScripts 和 jar 的依赖关系
tasks.named("startShadowScripts") {
    dependsOn("jar", "shadowJar")
}

tasks.named("distTar") {
    dependsOn(createDistributionStartScripts)
}

tasks.named("distZip") {
    dependsOn(createDistributionStartScripts)
}
