@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package howread.app

import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import howread.core.TestLog
import howread.core.ai.AiClient
import howread.core.ai.AiConfig
import howread.core.ai.AiConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** AI 大模型配置窗口（对齐安卓 dialog：协议/厂商展示行、Key 显示切换、获取模型、测试对话、关闭/保存）。 */
@Composable
fun AiConfigScreen(onClose: () -> Unit) {
    val saved = remember { AiConfigStore.load() }
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(saved.model) }
    var maxTokens by remember { mutableStateOf(saved.maxTokens.toString()) }
    var thinking by remember { mutableStateOf(saved.thinking) }
    var testInput by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var statusOk by remember { mutableStateOf<Boolean?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()

    fun formCfg() = AiConfig(
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        maxTokens = maxTokens.trim().toIntOrNull() ?: 4096,
        thinking = thinking,
    )

    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
    Column(Modifier.fillMaxSize().background(UiTheme.p.bg).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L10n.s("AI 大模型接入", "AI model access"), color = UiTheme.p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        // 行1：协议 / 模型厂商（展示值行；桌面仅实现 openai 兼容协议）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(L10n.s("协议", "Protocol"), color = UiTheme.p.textPrimary, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Text(L10n.s("OpenAI 兼容", "OpenAI compatible"), color = UiTheme.p.accent, fontSize = 13.sp, textDecoration = TextDecoration.Underline)
            Spacer(Modifier.weight(1f))
            Text(L10n.s("模型厂商", "Model vendor"), color = UiTheme.p.textPrimary, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Text(L10n.s("未添加", "Not added"), color = UiTheme.p.accent, fontSize = 13.sp, textDecoration = TextDecoration.Underline)
        }
        CompactField(baseUrl, { baseUrl = it },
            hint = L10n.s("服务地址 Base URL（如 https://api.openai.com/v1）", "Base URL"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(apiKey, { apiKey = it }, hint = L10n.s("API Key", "API Key"),
                modifier = Modifier.weight(1f),
                visual = if (showKey) VisualTransformation.None else PasswordVisualTransformation())
            TextButton(onClick = { showKey = !showKey }) {
                Text(if (showKey) L10n.s("隐藏", "Hide") else L10n.s("显示", "Show"), color = UiTheme.p.accent, fontSize = 13.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(model, { model = it },
                hint = L10n.s("模型名称（如 gpt-4o-mini）", "Model name (e.g. gpt-4o-mini)"), modifier = Modifier.weight(1f))
            TextButton(enabled = !loadingModels, onClick = {
                loadingModels = true
                statusMsg = ""
                val cfgNow = formCfg()
                scope.launch {
                    val list = withContext(Dispatchers.IO) { AiClient.listModels(cfgNow) }
                    loadingModels = false
                    if (list == null) {
                        statusOk = false
                        statusMsg = L10n.s("模型列表拉取失败（检查地址/Key）", "Failed to list models (check URL/Key)")
                    } else {
                        models = list
                        statusOk = null
                        statusMsg = L10n.s("共 ", "") + list.size + L10n.s(" 个模型，点击选用", " models; tap to pick")
                    }
                }
            }) { Text(if (loadingModels) L10n.s("拉取中…", "Loading…") else L10n.s("获取模型", "Get models"), color = UiTheme.p.accent, fontSize = 13.sp) }
        }
        models?.let { list ->
            LazyColumn(Modifier.height(110.dp).background(UiTheme.p.panel)) {
                items(list.size) { i ->
                    TextButton(onClick = { model = list[i]; models = null }) {
                        Text(list[i], color = UiTheme.p.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(L10n.s("输出上限", "Max output tokens"), color = UiTheme.p.textPrimary, fontSize = 13.sp)
            Spacer(Modifier.width(16.dp))
            CompactField(maxTokens, { maxTokens = it }, hint = "4096", modifier = Modifier.width(120.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = thinking, onCheckedChange = { thinking = it })
            Text(L10n.s("思考模式（输出推理过程，默认关闭）", "Thinking mode (reasoning output, off by default)"),
                color = UiTheme.p.textSecondary, fontSize = 13.sp)
        }
        TextButton(enabled = !testing, onClick = {
            testing = true
            statusOk = null
            statusMsg = L10n.s("测试中…", "Testing…")
            val cfgNow = formCfg()
            scope.launch {
                val r = withContext(Dispatchers.IO) { AiClient.testConnection(cfgNow) }
                testing = false
                statusOk = r.ok
                statusMsg = if (r.ok) L10n.s("连接成功：", "Connected: ") + r.reply.take(40)
                else L10n.s("失败", "Failed") + "(" + r.error + ")：" + r.detail.take(120)
                TestLog.evt("ai_cfg_test", if (r.ok) "ok model=" + cfgNow.model else "err=" + r.error)
            }
        }) { Text(if (testing) L10n.s("测试中…", "Testing…") else L10n.s("测试连接", "Test connection"), color = UiTheme.p.accent, fontSize = 13.sp) }
        statusMsg.takeIf { it.isNotEmpty() }?.let {
            Text(it, color = if (statusOk == true) UiTheme.p.green else UiTheme.p.warn, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CompactField(testInput, { testInput = it },
                hint = L10n.s("输入文字，测试模型是否有响应", "Type text to test the model"), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(enabled = !sending && testInput.isNotBlank(), onClick = {
                sending = true
                statusOk = null
                statusMsg = L10n.s("请求中…", "Asking…")
                val cfgNow = formCfg()
                val q = testInput
                scope.launch {
                    val r = withContext(Dispatchers.IO) { AiClient.chat(cfgNow, q, cfgNow.maxTokens) }
                    sending = false
                    statusOk = r.ok
                    statusMsg = if (r.ok) L10n.s("回复：", "Reply: ") + r.reply.take(80)
                    else L10n.s("失败", "Failed") + "(" + r.error + ")：" + r.detail.take(120)
                    TestLog.evt("ai_cfg_send", if (r.ok) "ok" else "err=" + r.error)
                }
            }) { Text(if (sending) "…" else L10n.s("发送", "Send")) }
        }
        // 底部：关闭 / 保存（右对齐；保存=落盘并关闭）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text(L10n.s("关闭", "Close"), fontSize = 13.sp, color = UiTheme.p.textTertiary) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                AiConfigStore.save(formCfg())
                TestLog.evt("ai_cfg_save", "model=" + model.trim())
                onClose()
            }) { Text(L10n.s("保存", "Save")) }
        }
    }
    }
}
