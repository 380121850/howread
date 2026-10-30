plugins {
    kotlin("jvm") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
}

allprojects {
    repositories {
        mavenCentral()
        google() // androidx.lifecycle 等 compose 桌面传递依赖只在 Google Maven
    }
}
