plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":engine"))
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended) // 侧栏/标签栏/首页 Material 图标
    // 交叉运行：fat dist 里带 Windows skiko 原生（服务器构建、Windows 宿主运行）
    implementation(compose.desktop.windows_x64)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")   // i18n tables.json 解析（与 core 同版本）
}

compose.desktop {
    application {
        mainClass = "howread.app.MainKt"
        nativeDistributions {
            packageName = "HowReadPro"
            // M1 不启用 jpackage（不能交叉出 Windows 安装包）；用下方 dist 任务 + 脚本
        }
    }
}

// M1 发行目录：app/*.jar + engine 原生库 + 启动脚本 → desktop/build/dist/
val distTask = tasks.register<Sync>("dist") {
    dependsOn(tasks.jar)
    val rootP = rootProject.projectDir
    into(File(rootP, "build/dist"))
    into("app") {
        from(tasks.jar)
        from(configurations.runtimeClasspath)
    }
    into("engine") {
        from(File(rootP, "../prebuilt/linux/x86_64/libmupdf_java64.so"))
        from(File(rootP, "../prebuilt/windows/x86_64/mupdf_java64.dll"))
    }
    from(File(rootP, "scripts/run.bat"))
    from(File(rootP, "scripts/run.sh"))
}
