package io.savant.assey.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.savant.assey.data.files.FileIntake
import io.savant.assey.domain.TaskRecord
import io.savant.assey.domain.TaskStatus
import io.savant.assey.domain.TranslateStatus
import io.savant.assey.ui.AppController
import io.savant.assey.ui.components.MarkdownView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(
    task: TaskRecord?,
    controller: AppController,
    snackbarHost: SnackbarHostState
) {
    var tab by rememberSaveable(task?.id) { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        task?.title ?: "任务",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 16.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = controller::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (task != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "删除任务")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        if (task == null) {
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("任务不存在或已删除")
            }
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (task.status) {
                TaskStatus.DONE -> DoneContent(task, controller, tab) { tab = it }
                TaskStatus.FAILED -> FailedContent(task, controller)
                TaskStatus.CANCELED -> CanceledContent(task, controller)
                else -> RunningContent(task, controller)
            }
        }
    }

    if (confirmDelete && task != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除任务？") },
            text = { Text("将同时删除已解析的 Markdown 与图片，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        controller.delete(task.id)
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun RunningContent(task: TaskRecord, controller: AppController) {
    val fraction = task.progressFraction
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.displayStatus,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (fraction != null) {
                        Text(
                            "${(fraction * 100).toInt()}%",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    task.stageLabel.ifBlank { "处理中…" },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (task.pagesTotal > 0) {
                    Text(
                        "已处理 ${task.pagesDone}/${task.pagesTotal} 页",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (task.bytesTotal > 0 &&
                    (task.status == TaskStatus.UPLOADING ||
                        task.status == TaskStatus.COPYING ||
                        task.status == TaskStatus.DOWNLOADING)
                ) {
                    Text(
                        "${FileIntake.formatSize(task.bytesDone)} / ${FileIntake.formatSize(task.bytesTotal)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                task.warning?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                task.backendName?.let {
                    Text(
                        "解析后端：$it",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { controller.cancel(task.id) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("取消任务")
                }
            }
        }
        Text(
            "可以切到后台或锁屏，任务会在前台服务中继续；通知栏可查看进度并取消。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FailedContent(task: TaskRecord, controller: AppController) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "任务失败",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    task.errorMessage ?: "未知错误",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { controller.retry(task.id) }) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("重新解析")
            }
            TextButton(onClick = { controller.delete(task.id) }) { Text("删除任务") }
        }
        if (task.errorKind == "AUTH") {
            TextButton(onClick = controller::openSettings) { Text("去设置页检查 Token") }
        }
    }
}

@Composable
private fun CanceledContent(task: TaskRecord, controller: AppController) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("任务已取消，临时文件已清理。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { controller.retry(task.id) }) { Text("重新解析") }
            TextButton(onClick = { controller.delete(task.id) }) { Text("删除任务") }
        }
    }
}

@Composable
private fun ColumnScope.DoneContent(
    task: TaskRecord,
    controller: AppController,
    tab: Int,
    onTabChange: (Int) -> Unit
) {
    val hasTranslation = task.translatedPath != null

    LaunchedEffect(hasTranslation) {
        if (!hasTranslation && tab != 0) onTabChange(0)
    }

    if (task.translateStatus == TranslateStatus.FAILED) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "翻译失败（原文已保留）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    task.errorMessage ?: "请重试翻译",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                TextButton(onClick = { controller.retranslate(task.id) }) {
                    Text("重试翻译")
                }
            }
        }
    }

    if (hasTranslation) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { onTabChange(0) },
                text = { Text("原文") }
            )
            Tab(
                selected = tab == 1,
                onClick = { onTabChange(1) },
                text = { Text("中文译文") }
            )
        }
    }

    ActionRow(task, controller, translated = tab == 1 && hasTranslation)

    val path = if (tab == 1 && hasTranslation) task.translatedPath else task.markdownPath
    val markdown by produceState<String?>(initialValue = null, path) {
        value = path?.let { p ->
            withContext(Dispatchers.IO) { runCatching { File(p).readText() }.getOrNull() }
        }
    }

    when {
        path == null -> Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) { Text("内容缺失，请重新解析") }

        markdown == null -> Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }

        else -> MarkdownView(
            markdown = markdown.orEmpty(),
            assetsDir = task.assetsPath?.let(::File),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionRow(task: TaskRecord, controller: AppController, translated: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { controller.exportMarkdown(task.id, translated) }) {
            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("导出 MD", fontSize = 12.sp)
        }
        TextButton(onClick = { controller.exportZip(task.id) }) {
            Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("导出 ZIP", fontSize = 12.sp)
        }
        TextButton(onClick = { controller.shareMarkdown(task.id, translated) }) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("分享", fontSize = 12.sp)
        }
        TextButton(onClick = { controller.retranslate(task.id) }) {
            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("重新翻译", fontSize = 12.sp)
        }
    }
}
