plugins {
    val kotlinVersion = "2.0.0"
    kotlin("jvm") version kotlinVersion
    id("org.jetbrains.kotlin.plugin.compose") version kotlinVersion
    id("org.jetbrains.compose") version "1.6.10"
}

group = "top.bilibili"
version = rootProject.version

repositories {
    mavenLocal()
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":"))
    implementation(compose.material)
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "top.bilibili.playground.MainKt"
    }
}