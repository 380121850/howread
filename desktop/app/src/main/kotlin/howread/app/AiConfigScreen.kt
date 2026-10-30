package howread.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import howread.core.TestLog
import howread.core.ai.AiClient
import howread.core.ai.AiConfig
import howread.core.ai.AiConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** AI 大模型配置窗口：openai 兼容端点（默认 bigmodel 智谱）+ 测试连接 + 拉取模型。 */
@Composable
fun AiConfigScreen(onClose: () -> Unit) {
    val saved = remember { AiConfigStore.load() }
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var maxTokens by remember { mutableStateOf(saved.maxTokens.toString()) }
    var thinking by remember { mutableStateOf(saved.thinking) }
    var testing by remember { mutableStateOf(false) }
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

    Box(Modifier.fillMaxSize().background(UiTheme.p.bg).padding(20.dp)) {
        Column(Modifier.fillMaxSize().padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 大模型（openai 兼容协议）", color = UiTheme.p.textPrimary, fontSize = 16.sp)
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("服务地址 Base URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(model, { model = it }, label = { Text("模型名") },
                    singleLine = true, modifier = Modifier.weight(1f))
                TextButton(enabled = !loadingModels, onClick = {
                    loadingModels = true
                    statusMsg = ""
                    val cfgNow = formCfg()
                    scope.launch {
                        val list = withContext(Dispatchers.IO) { AiClient.listModels(cfgNow) }
                        loadingModels = false
                        if (list == null) {
                            statusOk = false
                            statusMsg = "模型列表拉取失败（检查地址/Key）"
                        } else {
                            models = list
                            statusOk = null
                            statusMsg = "共 ${list.size} 个模型，点击选用"
                        }
                    }
                }) { Text(if (loadingModels) "拉取中…" else "拉取模型", color = UiTheme.p.accent, fontSize = 13.sp) }
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
            OutlinedTextField(maxTokens, { maxTokens = it }, label = { Text("最大 tokens") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = thinking, onCheckedChange = { thinking = it })
                Text("思考模式（更慢，翻译不需要）", color = UiTheme.p.textTertiary, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = !testing, onClick = {
                    testing = true
                    statusOk = null
                    statusMsg = "测试中…"
                    val cfgNow = formCfg()
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { AiClient.testConnection(cfgNow) }
                        testing = false
                        statusOk = r.ok
                        statusMsg = if (r.ok) "连接成功：" + r.reply.take(40)
                        else "失败(${r.error})：" + r.detail.take(120)
                        TestLog.evt("ai_cfg_test", if (r.ok) "ok model=" + cfgNow.model else "err=" + r.error)
                    }
                }) { Text(if (testing) "测试中…" else "测试连接") }
                OutlinedButton(onClick = {
                    AiConfigStore.save(formCfg())
                    TestLog.evt("ai_cfg_save", "model=" + model.trim())
                    onClose()
                }) { Text("保存并关闭") }
                TextButton(onClick = onClose) { Text("取消") }
            }
            statusMsg.takeIf { it.isNotEmpty() }?.let {
                Text(it, color = if (statusOk == true) UiTheme.p.green else UiTheme.p.warn, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("Key 仅存本机 %LOCALAPPDATA%/HowRead/ai.json；默认地址即智谱 bigmodel openai 兼容端点。",
                color = UiTheme.p.textFaintest, fontSize = 11.sp)
        }
    }
}
