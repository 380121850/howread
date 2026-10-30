plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.github.albfernandez:juniversalchardet:2.4.0")
    // 远程三协议（全纯 JVM，与安卓同栈：sardine(桌面版)/jcifs-ng/sshj）
    implementation("com.github.lookfirst:sardine:5.12") // 桌面版 sardine（安卓用 thegrizzlylabs/sardine-android 同源）
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10") // 与安卓同版本
    implementation("com.hierynomus:sshj:0.38.0")
    // AI 大模型（M5）：okhttp(与安卓同栈) + org.json(Android org.librera 同构 API，零插件)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    // 凭据本机加密（v0.50.0 对齐安卓 KeyStore）：Windows DPAPI 走 JNA Crypt32
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("org.zwobble.mammoth:mammoth:1.5.0") // docx→html（安卓 DocxContext 同款库）
    implementation("com.github.joniles:rtfparserkit:1.16.0") // rtf 解析（安卓 RtfExtract 同款库）
    implementation("org.zwobble.mammoth:mammoth:1.5.0") // docx→html（安卓 DocxContext 同款库）
    implementation("com.github.joniles:rtfparserkit:1.16.0") // rtf 解析（安卓 RtfExtract 同款库）
}
