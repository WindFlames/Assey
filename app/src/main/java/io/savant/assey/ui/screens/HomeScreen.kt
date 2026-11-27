package io.savant.assey.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.savant.assey.domain.MineruModel
import io.savant.assey.domain.ParseOptions
import io.savant.assey.domain.ParserKind
import io.savant.assey.domain.Settings
import io.savant.assey.domain.TaskRecord
import io.savant.assey.domain.TaskStatus
import io.savant.assey.ui.AppController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    tasks: List<TaskRecord>,
    settings: Settings,
    controller: AppController,
    snackbarHost: SnackbarHostState
) {
    var url by rememberSaveable { mutableStateOf("") }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var model by rememberSaveable { mutableStateOf(settings.defaultModel) }
    var pageRanges by rememberSaveable { mutableStateOf("") }
    var ocr by rememberSaveable { mutableStateOf(settings.isOcr) }
    var formula by rememberSaveable { mutableStateOf(settings.enableFormula) }
    var table by rememberSaveable { mutableStateOf(settings.enableTable) }

    fun options() = ParseOptions(
        model = model,
        pageRanges = pageRanges,
        isOcr = ocr,
        enableFormula = formula,
        enableTable = table,
        language = settings.defaultLanguage
    )

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { controller.importUri(it, options()) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { controller.importUri(it, options()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Assey", fontWeight = FontWeight.Bold)
                        Text(
                            "论文 PDF → Markdown → 中文",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = controller::openSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { IntroCard(settings) }

            item {
                ImportCard(
                    url = url,
                    onUrlChange = { url = it },
                    onPickPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                    onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                    onSubmitUrl = {
                        controller.importUrl(url, options())
                        url = ""
                    }
                )
            }

            item {
                OptionsCard(
                    expanded = showOptions,
                    onToggle = { showOptions = !showOptions },
                    model = model,
                    onModelChange = { model = it },
                    pageRanges = pageRanges,
                    onPageRangesChange = { pageRanges = it },
                    ocr = ocr,
                    onOcrChange = { ocr = it },
                    formula = formula,
                    onFormulaChange = { formula = it },
                    table = table,
                    onTableChange = { table = it }
                )
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("任务记录", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (tasks.isNotEmpty()) {
                        Text(
                            "${tasks.size} 个",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (tasks.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("还没有任务", fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "选择 PDF、从其他 App 分享进来，或粘贴链接开始解析",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(task) { controller.openTask(task.id) }
                }
            }
        }
    }
}

@Composable
private fun IntroCard(settings: Settings) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "解析、翻译、导出，一步到位",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "MinerU 解析公式/表格/插图，Index-Translate 翻译中文；" +
                    "代码、公式、图片路径与 URL 会被完整保留。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hasToken = settings.mineruToken.isNotBlank()
                Spacer(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (hasToken) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        hasToken -> "精准解析已就绪（带图片）"
                        settings.parserKind == ParserKind.LITE -> "轻量解析（无图片）"
                        else -> "未配置 Token，自动使用轻量解析（≤10MB，无图片）"
                    },
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ImportCard(
    url: String,
    onUrlChange: (String) -> Unit,
    onPickPdf: () -> Unit,
    onPickImage: () -> Unit,
    onSubmitUrl: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("导入文档", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPickPdf,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("选择 PDF")
                }
                OutlinedButton(
                    onClick = onPickImage,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("选择图片")
                }
            }

            HorizontalDivider()

            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                label = { Text("或粘贴远程 PDF 链接") },
                placeholder = { Text("https://example.com/paper.pdf") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSubmitUrl, enabled = url.isNotBlank()) {
                    Text("解析链接")
                }
            }
        }
    }
}

@Composable
private fun OptionsCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    model: MineruModel,
    onModelChange: (MineruModel) -> Unit,
    pageRanges: String,
    onPageRangesChange: (String) -> Unit,
    ocr: Boolean,
    onOcrChange: (Boolean) -> Unit,
    formula: Boolean,
    onFormulaChange: (Boolean) -> Unit,
    table: Boolean,
    onTableChange: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("解析选项", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "默认：${model.label} · 公式${if (formula) "开" else "关"} · 表格${if (table) "开" else "关"}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "收起" else "展开")
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spacer(Modifier.height(4.dp))
                    Text("模型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MineruModel.entries.forEach { item ->
                            FilterChip(
                                selected = model == item,
                                onClick = { onModelChange(item) },
                                label = { Text(item.label, fontSize = 12.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = pageRanges,
                        onValueChange = onPageRangesChange,
                        label = { Text("页码范围（可选）") },
                        placeholder = { Text("1-10 或 2,4-6") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SwitchRow("OCR 识别", "电子版论文通常不需要开启", ocr, onOcrChange)
                    SwitchRow("公式识别", "保留 LaTeX 公式", formula, onFormulaChange)
                    SwitchRow("表格识别", "保留 Markdown 表格", table, onTableChange)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
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

@Composable
private fun TaskCard(task: TaskRecord, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(task)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                task.displayStatus,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val fraction = task.progressFraction
            if (task.isRunning) {
                Spacer(Modifier.height(8.dp))
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatTime(task.createdAt)} · ${task.backendName ?: "等待选择后端"}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatusBadge(task: TaskRecord) {
    val color = statusColor(task.status)
    Text(
        text = task.status.label,
        fontSize = 10.sp,
        color = color,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
fun statusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.DONE -> MaterialTheme.colorScheme.primary
    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
    TaskStatus.CANCELED -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.tertiary
}

fun formatTime(time: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
