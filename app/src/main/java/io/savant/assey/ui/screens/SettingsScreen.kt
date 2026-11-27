package io.savant.assey.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.savant.assey.data.store.SettingsStore
import io.savant.assey.domain.DownloadMode
import io.savant.assey.domain.MineruModel
import io.savant.assey.domain.ParserKind
import io.savant.assey.domain.Settings
import io.savant.assey.ui.AppController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    controller: AppController,
    snackbarHost: SnackbarHostState
) {
    var token by rememberSaveable { mutableStateOf(settings.mineruToken) }
    var parserKind by rememberSaveable { mutableStateOf(settings.parserKind) }
    var downloadMode by rememberSaveable { mutableStateOf(settings.downloadMode) }
    var defaultModel by rememberSaveable { mutableStateOf(settings.defaultModel) }
    var language by rememberSaveable { mutableStateOf(settings.defaultLanguage) }
    var ocr by rememberSaveable { mutableStateOf(settings.isOcr) }
    var formula by rememberSaveable { mutableStateOf(settings.enableFormula) }
    var table by rememberSaveable { mutableStateOf(settings.enableTable) }
    var autoTranslate by rememberSaveable { mutableStateOf(settings.autoTranslate) }
    var baseUrl by rememberSaveable { mutableStateOf(settings.translatorBaseUrl) }
    var translatorModel by rememberSaveable { mutableStateOf(settings.translatorModel) }
    var apiKey by rememberSaveable { mutableStateOf(settings.translatorApiKey) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }

    val busy by controller.busy.collectAsState()
    val testResult by controller.testResult.collectAsState()
    val scope = rememberCoroutineScope()

    fun save() {
        val cleanToken = SettingsStore.sanitizeToken(token)
        token = cleanToken
        controller.updateSettings {
            it.copy(
                mineruToken = cleanToken,
                parserKind = parserKind,
                downloadMode = downloadMode,
                defaultModel = defaultModel,
                defaultLanguage = language.trim().ifBlank { "ch" },
                isOcr = ocr,
                enableFormula = formula,
                enableTable = table,
                autoTranslate = autoTranslate,
                translatorBaseUrl = baseUrl.trim().ifBlank { Settings.DEFAULT_TRANSLATOR_BASE },
                translatorModel = translatorModel.trim().ifBlank { Settings.DEFAULT_TRANSLATOR_MODEL },
                translatorApiKey = apiKey.trim()
            )
        }
        scope.launch { snackbarHost.showSnackbar("设置已保存，立即生效") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = controller::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // MinerU Token
            SettingsCard("MinerU Token", "精准解析需要 Token；轻量解析可免 Token") {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    visualTransformation = if (tokenVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (tokenVisible) "隐藏" else "显示"
                            )
                        }
                    },
                    supportingText = {
                        Text(
                            if (settings.mineruToken.isBlank()) "未保存 Token"
                            else "已保存：${mask(settings.mineruToken)}（自动清理 Bearer 前缀与引号）"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { controller.testToken(token) },
                        enabled = !busy
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("测试 Token 是否有效")
                    }
                }
                Text(
                    "在 mineru.net/apiManage 创建 Token。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 解析后端
            SettingsCard("解析后端", "切换后端不需要更新界面") {
                ParserKind.entries.forEach { kind ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = parserKind == kind,
                            onClick = { parserKind = kind }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(kind.label, fontSize = 14.sp)
                            Text(
                                when (kind) {
                                    ParserKind.AUTO -> "有 Token 走精准（≤20MB），否则轻量（≤10MB，无图片）"
                                    ParserKind.PRECISION -> "MinerU 精准解析，返回 Markdown + 图片"
                                    ParserKind.LITE -> "免 Token，仅 Markdown，单文件 ≤10MB / ≤20 页"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 结果下载方式
            SettingsCard("结果下载方式", "解析完成后，结果包由谁来下载") {
                DownloadMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = downloadMode == mode,
                            onClick = { downloadMode = mode }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(mode.label, fontSize = 14.sp)
                            Text(
                                when (mode) {
                                    DownloadMode.BUILT_IN ->
                                        "App 内流式下载，进度在任务页与通知栏；退出 App 会中断"

                                    DownloadMode.EXTERNAL ->
                                        "交给系统下载管理器，通知栏可见，可切后台甚至退出 App"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    "系统下载先落到应用专属外部目录，完成后自动复制回任务目录并清理。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 解析默认参数
            SettingsCard("解析默认参数", "新建任务时的默认值，可在首页临时调整") {
                Text("默认模型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MineruModel.entries.forEach { item ->
                        FilterChip(
                            selected = defaultModel == item,
                            onClick = { defaultModel = item },
                            label = { Text(item.label, fontSize = 12.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = language,
                    onValueChange = { language = it },
                    label = { Text("默认语言") },
                    placeholder = { Text("ch / en") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SwitchLine("OCR", "扫描件才需要开启", ocr) { ocr = it }
                SwitchLine("公式识别", "保留 LaTeX 公式", formula) { formula = it }
                SwitchLine("表格识别", "保留 Markdown 表格", table) { table = it }
                HorizontalDivider()
                SwitchLine("解析完成后自动翻译", "关闭后仍可在结果页手动翻译", autoTranslate) {
                    autoTranslate = it
                }
            }

            // 翻译服务
            SettingsCard("翻译服务", "默认 Index-Translate；也可填任意 OpenAI 兼容端点") {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text(Settings.DEFAULT_TRANSLATOR_BASE) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("模型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Settings.TRANSLATOR_MODELS.forEach { item ->
                        FilterChip(
                            selected = translatorModel == item,
                            onClick = { translatorModel = item },
                            label = { Text(item.removePrefix("Index-Translate-"), fontSize = 12.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = translatorModel,
                    onValueChange = { translatorModel = it },
                    label = { Text("模型名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key（可留空）") },
                    singleLine = true,
                    visualTransformation = if (keyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (keyVisible) "隐藏" else "显示"
                            )
                        }
                    },
                    supportingText = { Text("留空时使用 EMPTY（Index-Translate 免 Token）") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { controller.testTranslator(baseUrl, translatorModel, apiKey) },
                    enabled = !busy
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("测试翻译服务")
                }
            }

            if (testResult != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            testResult.orEmpty(),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                        TextButton(onClick = controller::clearTestResult) {
                            Text("关闭")
                        }
                    }
                }
            }

            Button(
                onClick = { save() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("保存设置", fontWeight = FontWeight.Medium)
            }

            Text(
                "Token 与 API Key 使用 Android Keystore 加密后保存在本机，不会写入安装包。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun SwitchLine(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(
                description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun mask(value: String): String {
    if (value.isBlank()) return "未保存"
    if (value.length <= 8) return "••••"
    return value.take(4) + "••••" + value.takeLast(4)
}
