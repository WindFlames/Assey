package io.savant.assey.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.savant.assey.domain.ChatMessage
import io.savant.assey.domain.ChatSession
import io.savant.assey.domain.Settings
import io.savant.assey.ui.ChatController
import io.savant.assey.ui.components.MarkdownBlockColumn
import kotlinx.coroutines.launch

/**
 * 对话页：单会话视图 + 底部输入栏，历史会话放在底部抽屉里。
 *
 * 渲染上有个取舍：assistant 的消息走 [MarkdownBlockColumn]（代码块/列表/表格都能显示），
 * user 的消息直接按纯文本显示——用户输入的 Markdown 记号和气泡背景混在一起反而难看。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: ChatController,
    settings: Settings,
    snackbarHost: SnackbarHostState,
    onBack: () -> Unit
) {
    val sessions by chat.sessions.collectAsState()
    val activeId by chat.activeId.collectAsState()
    val streaming by chat.streaming.collectAsState()
    val toast by chat.toast.collectAsState()

    val session = sessions.firstOrNull { it.id == activeId }
    var input by rememberSaveable { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(toast) {
        val message = toast ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        chat.consumeToast()
    }

    // 离开页面时把还在去抖窗口里的增量立刻写盘，别等进程被杀
    DisposableEffect(Unit) {
        onDispose { chat.flush() }
    }

    // 新消息或流式增量都会改变长度，滚到底让用户看到最新内容
    val lastLength = session?.messages?.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(session?.messages?.size, lastLength) {
        val count = session?.messages?.size ?: 0
        if (count > 0) listState.scrollToItem(count - 1)
    }

    fun copy(text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("chat", text))
        scope.launch { snackbarHost.showSnackbar("已复制") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            session?.title?.takeIf { it.isNotBlank() } ?: "对话",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            settings.chatModel.ifBlank { Settings.DEFAULT_CHAT_MODEL },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Default.History, contentDescription = "历史对话")
                    }
                    IconButton(onClick = chat::newSession) {
                        Icon(Icons.Default.Add, contentDescription = "新对话")
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
                .imePadding()
        ) {
            if (session == null || session.messages.isEmpty()) {
                EmptyChat(
                    modifier = Modifier.weight(1f),
                    onPick = { input = it }
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(session.messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            onCopy = { copy(message.content) },
                            onRetry = { chat.regenerate() }
                        )
                    }
                    if (streaming && session.messages.lastOrNull()?.content.isNullOrBlank()) {
                        item(key = "typing") {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "正在生成…",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            InputBar(
                value = input,
                onValueChange = { input = it },
                streaming = streaming,
                onSend = {
                    val text = input
                    input = ""
                    chat.send(text)
                },
                onStop = chat::stop
            )
        }
    }

    if (showHistory) {
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            HistorySheet(
                sessions = sessions,
                activeId = activeId,
                onOpen = { id ->
                    chat.open(id)
                    showHistory = false
                },
                onDelete = chat::delete
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onCopy: () -> Unit,
    onRetry: () -> Unit
) {
    if (message.isUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
            ) {
                Text(
                    message.content,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
        }
        return
    }

    val container = if (message.error) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = container,
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            modifier = Modifier.widthIn(max = 640.dp)
        ) {
            SelectionContainer {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    if (message.content.isBlank()) {
                        Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        MarkdownBlockColumn(message.content)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (message.content.isNotBlank()) {
                TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("复制", fontSize = 11.sp)
                }
            }
            if (message.error) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("重试", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("发消息…", fontSize = 14.sp) },
                maxLines = 6,
                shape = RoundedCornerShape(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { if (streaming) onStop() else onSend() },
                enabled = streaming || value.isNotBlank(),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Icon(
                    if (streaming) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (streaming) "停止生成" else "发送"
                )
            }
        }
    }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val suggestions = listOf(
        "把这段报错解释一下：",
        "帮我写个匹配 Markdown 图片的正则",
        "把下面这段文字改得更简洁："
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("有什么可以帮你？", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "消息只保存在本机，走设置里配置的对话服务",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        suggestions.forEach { text ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onPick(text) }
            ) {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun HistorySheet(
    sessions: List<ChatSession>,
    activeId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Text("历史对话", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (sessions.isEmpty()) {
            Text(
                "还没有对话记录",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(sessions, key = { it.id }) { session ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(
                            if (session.id == activeId) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            RoundedCornerShape(12.dp)
                        )
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(vertical = 10.dp)
                    ) {
                        Text(session.title, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(
                            "${formatTime(session.updatedAt)} · ${session.messages.size} 条",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onOpen(session.id) }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "打开")
                    }
                    IconButton(onClick = { onDelete(session.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            }
        }
    }
}
