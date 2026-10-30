package howread.core

import java.io.File

/** HRTEST 打桩（与鸿蒙 CI 同协议）：evt=<name> ts=<毫秒> [detail]。日志随数据目录（v0.28.0）。 */
object TestLog {
    /** 调试日志开关（偏好→常规设置→调试日志；v0.30.0，默认开，可用 howread.hrtest=0 系统属性禁用）。 */
    @Volatile var enabled: Boolean = System.getProperty("howread.hrtest", "1") != "0"

    private val dir: File = AppPaths.logsDir.apply { mkdirs() }

    fun evt(name: String, detail: String = "") {
        if (!enabled) return
        try {
            File(dir, "hrtest.log").appendText(
                "evt=$name ts=${System.currentTimeMillis()}${if (detail.isEmpty()) "" else " $detail"}\n"
            )
        } catch (_: Exception) {
        }
    }
}
