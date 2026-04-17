plugins {
    val kotlinVersion = "2.0.0"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    // 独立应用程序插件
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "top.bilibili"
val releaseVersion = (findProperty("releaseVersion") as String?) ?: "1.8-SNAPSHOT"
version = releaseVersion

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// 配置主类
application {
    mainClass.set("top.bilibili.MainKt")
    applicationDefaultJvmArgs = listOf("-Dapp.version=$releaseVersion")
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

val skiaNativeMemoryEvidenceTest by tasks.registering(org.gradle.api.tasks.testing.Test::class) {
    description = "Runs the Skia native-memory evidence test with JVM native memory tracking enabled"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("top.bilibili.core.resource.SkiaNativeMemoryEvidenceTest")
    }
    jvmArgs("-XX:NativeMemoryTracking=summary")
    systemProperty("skia.native.memory.evidence", "true")
    shouldRunAfter(tasks.test)
}
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    manifest {
        attributes("Implementation-Version" to project.version.toString())
    }
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

            rem Windows 不使用 Linux LD_PRELOAD allocator 注入；当前发行包未携带 Windows jemalloc/tcmalloc runtime。
            rem Windows 裸机继续依赖 JVM 与 Skiko 参数约束 native memory 行为。
            set JAVA_OPTS=-Xms64m -Xmx160m
            rem 长时间静默场景显式启用 G1 周期回收与更积极的 heap 收缩，避免偶发绘图后长期保留高位 committed heap。
            set JAVA_OPTS=%JAVA_OPTS% -XX:MinHeapFreeRatio=10
            set JAVA_OPTS=%JAVA_OPTS% -XX:MaxHeapFreeRatio=20
            set JAVA_OPTS=%JAVA_OPTS% -XX:G1PeriodicGCInterval=60000
            set JAVA_OPTS=%JAVA_OPTS% -XX:G1PeriodicGCSystemLoadThreshold=0
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

            # Linux 裸机必须在 JVM 启动前注入 jemalloc，否则 Anonymous/RSS 漂移会回到 glibc malloc 行为。
            if [ "${'$'}(uname -s)" = "Linux" ]; then
                EXISTING_LD_PRELOAD="${'$'}{LD_PRELOAD:-}"
                JEMALLOC_LIB=""

                case "${'$'}EXISTING_LD_PRELOAD" in
                    *libjemalloc.so.2*)
                        ;;
                    *)
                        # 优先探测主流发行版安装路径，再回退到动态链接器缓存，避免要求用户手动填写绝对路径。
                        for candidate in \
                            /usr/lib/x86_64-linux-gnu/libjemalloc.so.2 \
                            /usr/lib/aarch64-linux-gnu/libjemalloc.so.2 \
                            /usr/lib64/libjemalloc.so.2 \
                            /usr/lib/libjemalloc.so.2
                        do
                            if [ -r "${'$'}candidate" ]; then
                                JEMALLOC_LIB="${'$'}candidate"
                                break
                            fi
                        done

                        if [ -z "${'$'}JEMALLOC_LIB" ] && command -v ldconfig >/dev/null 2>&1; then
                            JEMALLOC_LIB="${'$'}(ldconfig -p | awk '/libjemalloc\.so\.2/ { print ${'$'}NF; exit }')"
                        fi

                        if [ -z "${'$'}JEMALLOC_LIB" ] || [ ! -r "${'$'}JEMALLOC_LIB" ]; then
                            echo "ERROR: libjemalloc.so.2 not found. Install jemalloc before starting dynamic-bot on Linux bare metal." >&2
                            exit 1
                        fi

                        export LD_PRELOAD="${'$'}JEMALLOC_LIB"
                        if [ -n "${'$'}EXISTING_LD_PRELOAD" ]; then
                            export LD_PRELOAD="${'$'}JEMALLOC_LIB:${'$'}EXISTING_LD_PRELOAD"
                        fi
                        ;;
                esac

                # 与 Dockerfile 保持一致的 jemalloc decay 策略，允许部署侧通过 MALLOC_CONF 显式覆盖。
                MALLOC_CONF="${'$'}{MALLOC_CONF:-background_thread:true,dirty_decay_ms:2000,muzzy_decay_ms:2000,narenas:1,tcache:false}"
                export MALLOC_CONF
            fi

            JAVA_OPTS="-Xms64m -Xmx160m"
            # 长时间静默场景显式启用 G1 周期回收与更积极的 heap 收缩，避免偶发绘图后长期保留高位 committed heap。
            JAVA_OPTS="${'$'}JAVA_OPTS -XX:MinHeapFreeRatio=10"
            JAVA_OPTS="${'$'}JAVA_OPTS -XX:MaxHeapFreeRatio=20"
            JAVA_OPTS="${'$'}JAVA_OPTS -XX:G1PeriodicGCInterval=60000"
            JAVA_OPTS="${'$'}JAVA_OPTS -XX:G1PeriodicGCSystemLoadThreshold=0"
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

val sharedReleaseContentsDir = layout.buildDirectory.dir("release-platform/shared")

// 平台发行包共享同一份 fat jar、资源与 README，避免 Windows/Linux 打包逻辑重复维护公共 payload。
val stageSharedReleaseContents = tasks.register<Sync>("stageSharedReleaseContents") {
    dependsOn(tasks.shadowJar)
    into(sharedReleaseContentsDir)

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
}

// Windows 发布资产只暴露 Windows 启动入口，避免用户在发行包中误用 Linux 或 Gradle 默认脚本。
val windowsReleaseDistZip = tasks.register<Zip>("windowsReleaseDistZip") {
    group = "distribution"
    description = "Builds the Windows x64 release archive with the packaged start.bat entrypoint."
    dependsOn(stageSharedReleaseContents, createDistributionStartScripts)

    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("dynamic-bot-windows-x64-v${project.version}.zip")

    from(sharedReleaseContentsDir)
    from(createDistributionStartScripts) {
        include("start.bat")
        into("bin")
    }
}

// Linux 发布资产只暴露 Linux 启动入口，并生成 tar.gz 以保留 start.sh 的可执行权限。
val linuxReleaseDistTar = tasks.register<Tar>("linuxReleaseDistTar") {
    group = "distribution"
    description = "Builds the Linux x64 release archive with the packaged start.sh entrypoint."
    dependsOn(stageSharedReleaseContents, createDistributionStartScripts)

    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("dynamic-bot-linux-x64-v${project.version}.tar.gz")
    compression = Compression.GZIP

    from(sharedReleaseContentsDir)
    from(createDistributionStartScripts) {
        include("start.sh")
        into("bin")
        fileMode = 0b111101101 // 755
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
