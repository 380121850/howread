plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

// fitz Java 绑定 jar（engine 构建产物；linux/windows 两份内容一致）
val fitzJar = rootProject.projectDir.resolve("../prebuilt/linux/x86_64/libmupdf.jar")

dependencies {
    api(files(fitzJar))
}
