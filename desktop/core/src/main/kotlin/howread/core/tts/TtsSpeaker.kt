package howread.core.tts

import howread.core.AppPaths
import howread.core.TestLog
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Windows 朗读（System.Speech 经 PowerShell 子进程，逐段顺序播放）。
 * v0.20.0：语速/音量/指定语音/段间停顿（TtsPrefs，偏好页可调）；
 * onDone(finished)：finished=true 表示本批自然播完（连读翻页判定），
 * 被 cancel() 或被新一次 speak() 顶替时 finished=false；世代号隔离旧线程回调。
 */
object TtsSpeaker {

    @Volatile var speaking = false
        private set

    private val gen = AtomicLong(0)
    @Volatile private var cur: Process? = null
    @Volatile private var voicesCache: List<String>? = null

    fun cancel() {
        gen.incrementAndGet()
        cur?.destroyForcibly()
        cur = null
        speaking = false
    }

    /** 逐段朗读；onProgress(当前段,总段数) 每段开始时回调（TTS 线程）。 */
    fun speak(
        paragraphs: List<String>,
        onDone: (finished: Boolean) -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        cancel()
        val myGen = gen.incrementAndGet()
        val prefs = TtsPrefsStore.load()
        // 检视修复：超长段按 8000 字符切分（Windows 环境块上限约 32K，超限整段静默丢失）
        // v0.52.0 生词替换（安卓 isEnableTextReplacement 等价：仅朗读前套用，规则见偏好页）
        val rules = if (prefs.replaceEnabled) prefs.replacements else ""
        val texts = paragraphs.map { TtsReplace.apply(it.trim(), rules) }.filter { it.length > 1 }
            .flatMap { if (it.length > 8000) it.chunked(8000) else listOf(it) }
        if (texts.isEmpty()) {
            onDone(false)
            return
        }
        speaking = true
        Thread {
            var finished = false
            try {
                for ((i, t) in texts.withIndex()) {
                    if (gen.get() != myGen) break
                    onProgress(i + 1, texts.size)
                    speakOne(t, prefs, myGen)
                    if (i < texts.size - 1 && prefs.pauseMs > 0 && gen.get() == myGen)
                        runCatching { Thread.sleep(prefs.pauseMs.toLong()) }
                }
                if (gen.get() == myGen) {
                    finished = true
                    TestLog.evt("tts_page_done", "paras=" + texts.size)
                }
            } finally {
                // 检视修复：世代校验——旧线程不再覆盖新会话的 speaking 状态
                //（onDone(finished=false) 保持无条件回调：cancel→onDone(false) 是既定契约）
                if (gen.get() == myGen) speaking = false
                onDone(finished)
            }
        }.apply { isDaemon = true }.start()
    }

    /** 已安装语音列表（System.Speech 枚举；非 Windows/失败返回空表）。 */
    fun listVoices(refresh: Boolean = false): List<String> {
        if (!isWin()) return emptyList()
        if (!refresh) voicesCache?.let { return it }
        val out = ArrayList<String>()
        runCatching {
            val p = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Speech; " +
                    "(New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices() | " +
                    "ForEach-Object { \$_.VoiceInfo.Name }",
            ).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().forEachLine { l ->
                val t = l.trim()
                if (t.isNotEmpty()) out.add(t)
            }
            p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)   // 检视修复：防 powershell 挂起阻塞调用线程
            p.destroyForcibly()
        }
        voicesCache = out
        return out
    }

    private fun isWin(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    private fun speakOne(text: String, prefs: TtsPrefs, myGen: Long) {
        if (!isWin()) return
        val cjk = text.count { it.code in 0x4E00..0x9FFF } * 100 / maxOf(1, text.length) >= 30
        val ps = buildString {
            append("Add-Type -AssemblyName System.Speech; ")
            append("\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; ")
            append("try { \$s.Rate = ").append(prefs.rate)
            append("; \$s.Volume = ").append(prefs.volume).append(" } catch {}; ")
            append("if (-not [string]::IsNullOrEmpty(\$env:HR_TTS_VOICE)) { try { \$s.SelectVoice(\$env:HR_TTS_VOICE) } catch {} }")
            if (cjk) {
                append(" else { try { \$s.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::NotSet, " +
                    "[System.Speech.Synthesis.VoiceAge]::Adult, 0, " +
                    "[System.Globalization.CultureInfo]::new('zh-CN')) } catch {} }")
            }
            append("; \$s.Speak(\$env:HR_TTS)")
        }
        try {
            val pb = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
                .redirectErrorStream(true)
            pb.environment()["HR_TTS"] = text
            if (prefs.voice.isNotBlank()) pb.environment()["HR_TTS_VOICE"] = prefs.voice
            val p = pb.start()
            if (gen.get() == myGen) cur = p   // 检视修复：旧线程不清新会话的进程引用
            p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)   // 防挂死
            p.destroyForcibly()
            if (gen.get() == myGen) cur = null
        } catch (_: Exception) {
            // 朗读失败静默跳过该段（不阻断后续段落）
        }
    }

    /** 导出音频为 WAV（v0.52.0 对齐安卓 TTS 导出）：逐段 SAPI 合成临时 WAV，
     *  再解析 RIFF 串接 data 段合并成整文件（同机同格式，fmt 取首段）。可被 cancel() 中断。 */
    fun export(paragraphs: List<String>, out: File,
               onDone: (ok: Boolean, msg: String) -> Unit,
               onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        cancel()
        val myGen = gen.incrementAndGet()
        val prefs = TtsPrefsStore.load()
        val rules = if (prefs.replaceEnabled) prefs.replacements else ""
        val texts = paragraphs.map { TtsReplace.apply(it.trim(), rules) }.filter { it.length > 1 }
        if (texts.isEmpty()) { onDone(false, "没有可朗读的文本"); return }
        if (!isWin()) { onDone(false, "导出仅支持 Windows"); return }
        speaking = true
        Thread {
            var ok = false
            var msg = ""
            val tmp = File(AppPaths.dataDir, "tmp/exp-" + System.currentTimeMillis())
            try {
                tmp.mkdirs()
                val parts = ArrayList<File>()
                for ((i, t) in texts.withIndex()) {
                    if (gen.get() != myGen) { msg = "已取消"; break }
                    onProgress(i + 1, texts.size)
                    val wf = File(tmp, "seg" + i.toString().padStart(4, '0') + ".wav")
                    if (!speakOneToFile(t, prefs, wf, myGen) || !wf.isFile || wf.length() < 100) {
                        msg = "第 " + (i + 1) + " 段合成失败（语音未安装？）"; break
                    }
                    parts.add(wf)
                }
                if (msg.isEmpty()) {
                    if (!mergeWavs(parts, out)) msg = "WAV 合并失败"
                    else { ok = true; msg = out.absolutePath }
                }
            } catch (e: Exception) {
                msg = e.message ?: e.toString()
            } finally {
                runCatching { tmp.listFiles()?.forEach { it.delete() }; tmp.delete() }
                if (gen.get() == myGen) speaking = false
                TestLog.evt(if (ok) "tts_export_done" else "tts_export_fail", "n=" + texts.size + " " + msg.take(120))
                onDone(ok, msg)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun speakOneToFile(text: String, prefs: TtsPrefs, out: File, myGen: Long): Boolean {
        val cjk = text.count { it.code in 0x4E00..0x9FFF } * 100 / maxOf(1, text.length) >= 30
        val ps = buildString {
            append("Add-Type -AssemblyName System.Speech; ")
            append("\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; ")
            append("try { \$s.Rate = ").append(prefs.rate)
            append("; \$s.Volume = ").append(prefs.volume).append(" } catch {}; ")
            append("try { \$s.SetOutputToWaveFile(\$env:HR_TTS_OUT) } catch { exit 3 }; ")
            append("if (-not [string]::IsNullOrEmpty(\$env:HR_TTS_VOICE)) { try { \$s.SelectVoice(\$env:HR_TTS_VOICE) } catch {} }")
            if (cjk) {
                append(" else { try { \$s.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::NotSet, " +
                    "[System.Speech.Synthesis.VoiceAge]::Adult, 0, " +
                    "[System.Globalization.CultureInfo]::new('zh-CN')) } catch {} }")
            }
            append("; \$s.Speak(\$env:HR_TTS)")
        }
        return try {
            val pb = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
                .redirectErrorStream(true)
            pb.environment()["HR_TTS"] = text
            pb.environment()["HR_TTS_OUT"] = out.absolutePath
            if (prefs.voice.isNotBlank()) pb.environment()["HR_TTS_VOICE"] = prefs.voice
            val p = pb.start()
            if (gen.get() == myGen) cur = p
            p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)
            p.destroyForcibly()
            if (gen.get() == myGen) cur = null
            gen.get() == myGen
        } catch (_: Exception) {
            false
        }
    }

    /** WAV 合并：解析各段 RIFF 取 fmt/data，拼成新文件（SAPI 同机输出格式一致）。 */
    private fun mergeWavs(parts: List<File>, dest: File): Boolean = runCatching {
        var fmt: ByteArray? = null
        val datas = ArrayList<ByteArray>()
        for (f in parts) {
            val b = f.readBytes()
            if (b.size < 12 || String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" ||
                String(b, 8, 4, Charsets.ISO_8859_1) != "WAVE") return@runCatching false
            var p = 12
            while (p + 8 <= b.size) {
                val id = String(b, p, 4, Charsets.ISO_8859_1)
                val sz = (b[p + 4].toInt() and 0xFF) or ((b[p + 5].toInt() and 0xFF) shl 8) or
                    ((b[p + 6].toInt() and 0xFF) shl 16) or ((b[p + 7].toInt() and 0xFF) shl 24)
                val start = p + 8
                if (sz < 0 || start + sz > b.size) break
                if (id == "fmt ") fmt = b.copyOfRange(start, start + sz)
                if (id == "data") datas.add(b.copyOfRange(start, start + sz))
                p = start + sz + (sz and 1)
            }
        }
        val fm = fmt ?: return@runCatching false
        if (datas.isEmpty()) return@runCatching false
        var total = 0
        for (d in datas) total += d.size
        val os = java.io.FileOutputStream(dest)
        try {
            fun le(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
            fun chunk(cc: String, body: ByteArray) {
                os.write(cc.toByteArray(Charsets.ISO_8859_1))
                os.write(le(body.size))
                os.write(body)
                if (body.size and 1 == 1) os.write(0)
            }
            os.write("RIFF".toByteArray(Charsets.ISO_8859_1))
            os.write(le(4 + (8 + fm.size + (fm.size and 1)) + (8 + total + (total and 1))))
            os.write("WAVE".toByteArray(Charsets.ISO_8859_1))
            chunk("fmt ", fm)
            var all = ByteArray(0)
            for (d in datas) all += d
            chunk("data", all)
        } finally {
            os.close()
        }
        dest.length() > 44L
    }.getOrDefault(false)
}
