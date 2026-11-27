# Assey 架构说明

## 1. 目标与边界

Assey 是一个个人自用的单文档工具：**PDF → Markdown → 中文**。

明确不做：

- 账号、云同步、跨设备同步
- 批量队列、并发多任务
- 多语言 UI、主题市场
- 在线协作、版本管理

明确要做：

- 系统文件选择器、分享菜单、远程链接三种入口
- MinerU 精准解析（带图片）与免 Token 轻量解析
- 前台服务保障锁屏/切后台不中断，进程被杀后可续轮询
- 原文与译文都保留，可预览、导出、分享
- 后端可替换，新增后端不改 UI / ViewModel / 流水线

## 2. 分层

```
┌─────────────────────────────────────────────┐
│ UI（Compose）                                │
│ MainActivity / AsseyRoot / Home / Task /     │
│ Settings / MarkdownView                     │
└───────────────────┬─────────────────────────┘
                    │ 只读 StateFlow + 调 Controller
┌───────────────────▼─────────────────────────┐
│ AppController                                │
│ 导航、导入、导出、测试 Token/翻译、提示         │
└───────────────────┬─────────────────────────┘
                    │
┌───────────────────▼─────────────────────────┐
│ AppContainer（手动 DI 单例）                  │
│ SettingsStore / TaskStore / Exporter /       │
│ ParserFactory / TranslatorFactory / TaskRunner│
└───────────────────┬─────────────────────────┘
                    │
┌───────────────────▼─────────────────────────┐
│ TaskService（前台服务，dataSync）             │
│ 通知进度、取消、进程重启恢复                   │
└───────────────────┬─────────────────────────┘
                    │
┌───────────────────▼─────────────────────────┐
│ TaskRunner（领域流水线）                      │
│ 复制 → 选后端 → 解析 → 落盘 → 翻译             │
└──────────┬───────────────────────┬──────────┘
           │                       │
┌──────────▼─────────┐   ┌─────────▼──────────┐
│ DocumentParser      │   │ Translator         │
│ MinerU 精准 / 轻量   │   │ Index / OpenAI 兼容 │
└────────────────────┘   └────────────────────┘
```

### 为什么这样分

| 决策 | 理由 |
| --- | --- |
| 单 Activity + 手写路由 | 只有 3 个页面，省掉 Navigation 依赖与参数序列化 |
| 手动 DI（AppContainer） | 依赖少，避免 Hilt/KSP 带来的构建复杂度 |
| StateFlow + JSON 文件持久化 | 任务数量少，不需要 Room；400ms 节流原子写足够 |
| 前台服务承载流水线 | Activity 销毁不影响任务，通知栏可取消 |
| 后端接口 + 工厂 | 后端专有参数（`batch_id`、`chat_template_kwargs`）不进入领域模型和 UI |
| 翻译引擎与 HTTP 解耦 | 保护/分块/校验可用纯 JVM 单测覆盖，不依赖 Android |
| 手写 Markdown 渲染 | 论文常见结构有限，避免引入 Markwon 及其依赖 |

## 3. 关键数据模型

### TaskRecord

一条任务同时保存「输入、进度、结果、错误」：

- 输入：`sourceKind`、`sourceUri` / `remoteUrl`、`sizeBytes`、`options`
- 进度：`status`、`stageLabel`、`pagesDone/pagesTotal`、`bytesDone/bytesTotal`
- 续跑：`backendId`、`resumeToken`
- 结果：`markdownPath`、`assetsPath`、`translatedPath`、`translateStatus`
- 错误：`errorKind`、`errorMessage`（中文）

### 状态机

```
DRAFT
  └─ COPYING ── UPLOADING ── PARSING ── DOWNLOADING ── EXTRACTING ── DONE
                                                        │
                                                        └─ TRANSLATING ── DONE
任意运行态 ── CANCELED
任意运行态 ── FAILED（可重试）
```

- `DONE` 表示解析完成；翻译状态单独用 `TranslateStatus` 表示，翻译失败不会把原文结果弄丢。
- `CANCELED` 会删除任务目录；**翻译阶段取消是例外**：保留已解析的 Markdown 与图片，只回退翻译状态。

## 4. 导入流程

### 本地文件 / 分享

1. `MainActivity` 处理 `ACTION_SEND` / `ACTION_VIEW`，取出 `Uri`。
2. `AppController.importUri`：
   - 尝试 `takePersistableUriPermission`，保证后台服务仍能读。
   - 读取文件名、MIME、大小；**超过 20MB 直接拒绝，不复制、不上传**。
   - 创建 `TaskRecord(DRAFT)`，写入 `TaskStore`。
   - `TaskService.startParse` 启动前台服务。
3. `TaskRunner.parse`：
   - `FileIntake.copyTo` 以流式方式复制 `content://` 到任务目录，边复制边回调字节进度，并在复制过程中再次强制 20MB 上限。
   - `ParserFactory.select` 根据 Token、设置、文件大小选择后端。
   - 调用 `DocumentParser.parse`，通过 `ParseProgress` 更新状态与 `resumeToken`。
   - 结果统一落成 `full.md` + 可选资源目录。

### 远程链接

- 不做预下载，直接把 URL 交给解析后端（MinerU 支持 URL 输入）。
- 仍然创建任务、走同一个前台服务与状态机。

## 5. 解析后端

### 精准解析（`MineruPrecisionParser`）

1. `POST /api/v4/file-urls/batch` 获取预签名上传地址。
2. 对预签名 URL 做流式 `PUT`：**不加 `Authorization`，不设 `Content-Type`**。
3. `POST /api/v4/extract/task/batch` 提交解析任务。
4. 轮询 `GET /api/v4/extract-results/batch/{batch_id}`，把 `batch_id` 写入 `resumeToken`。
5. 下载结果 zip，流式写入任务目录。
6. `ZipExtractor` 解压，带 Zip Slip 防护；优先取 `full.md`，资源目录保留 `images/` 与相对路径。

### 轻量解析（`MineruLiteParser`）

1. `POST /api/v1/agent/parse/file` 或 `POST /api/v1/agent/parse/url`。
2. 轮询 `GET /api/v1/agent/parse/{task_id}`。
3. 取 `data.markdown_url` 下载 Markdown。
4. 限制：单文件 ≤10MB、≤20 页、无图片。

### 错误归一化

所有后端必须把 HTTP / 业务错误转换成 `core/Errors.kt` 中的类型：

- `AuthError`：401/403、Token 无效
- `FileTooLarge`：超过 20MB / 10MB
- `PageLimitExceeded`：页数超限
- `RateLimited`：429
- `NetworkError`：超时、断网、DNS
- `BackendError`：其余后端错误

UI 只展示 `userMessage()`，不解析后端原始 JSON。

## 6. 续跑与进程恢复

`TaskStore` 会把任务元数据持久化到 JSON（节流 + 临时文件原子替换）。

App 启动时 `AppContainer.recoverInterruptedTasks()`：

| 任务状态 | 条件 | 动作 |
| --- | --- | --- |
| `PARSING` / `DOWNLOADING` | 有 `resumeToken` + `backendId` | `startResume`，继续轮询 |
| `TRANSLATING` | `markdownPath` 文件仍存在 | `startTranslate`，重新翻译 |
| 其他运行态 | — | 标记失败，提示重新提交 |

上传/复制阶段无法安全续传，因此不会假装恢复。

## 7. 翻译流程

### 7.1 保护不可翻译片段

`MarkdownProtector` 在翻译前把以下内容替换成 `[[n]]`：

- 围栏代码块（含未闭合围栏，保护到文末）
- 行内代码
- 块级公式 `$$...$$`、行内公式 `$...$`（带金额误判保护）
- Markdown 图片 / 链接整体（图片路径与 URL 逐字不变）
- 裸 URL、HTML 标签 / 注释
- 引用式链接定义行
- 表格分隔行 `|---|---|`

翻译后 `restore` 会校验：

- 占位符缺失 → 报错
- 占位符重复 → 报错
- 出现未知占位符 → 报错

校验失败不会写出损坏的译文。

### 7.2 分块

`MarkdownChunker` 按空行切块，并保证：

```kotlin
chunks.joinToString("") { it.text + it.joiner } == source
```

即块与块之间的空行、缩进不会被吞掉或重复。长块会优先在换行、句末标点、空格处切分，并且不会把 `[[n]]` 切断。

### 7.3 调用与校验

`MarkdownTranslationEngine`：

1. 保护 → 分块。
2. 逐块调用 `ChatClient`。
3. 清理模型可能包上的 ```markdown 围栏。
4. 校验占位符、标题层级、反引号数量、表格分隔行。
5. 失败重试（最多 3 次，退避 500ms/1000ms）。
6. 仍失败则把块对半拆开递归翻译（最多两层）。
7. 最终失败抛 `TranslationError`，不写文件。
8. 全部成功后拼接并 `restore`，写入 `translated.md`。

### 7.4 后端选择

`TranslatorFactory`：

- Base URL 含 `index-translate` → `IndexTranslateTranslator`
  - 必须自定义 `User-Agent`（否则 412）
  - `Authorization: Bearer EMPTY`（未填 Key 时）
  - `chat_template_kwargs.enable_thinking = false`
  - `max_tokens = 4096`
- 其他 → `OpenAiCompatibleTranslator`（标准 `/chat/completions`）

## 8. 导出与分享

- 导出统一走 `MediaStore.Downloads`，目录 `下载/Assey`；`minSdk 33` 无需存储权限。
- 单 Markdown：`<标题>.md` / `<标题>.zh.md`。
- ZIP：包含 `full.md`、可选的 `translated.md`、`images/` 等资源，保持相对路径。
- 分享：复制到 `cache/share`，通过 `FileProvider` + `ACTION_SEND` 发出。
- 写入使用 `IS_PENDING`，失败时删除占位记录，避免下载目录出现半截文件。

## 9. 安全与工程细节

- Token / API Key 使用 Android Keystore AES-256-GCM 加密后落盘，界面脱敏。
- Token 输入会清理 `Bearer ` 前缀、引号与空白。
- 预签名上传不带鉴权头与 `Content-Type`，避免签名不匹配。
- 上传、下载、`content://` 复制全部流式处理，不整文件进内存。
- 解压前规范化路径，拒绝 `../` 越界（Zip Slip）。
- Android 14+ 声明 `dataSync` 前台服务与 `FOREGROUND_SERVICE_DATA_SYNC` 权限。
- Android 13+ 启动时请求 `POST_NOTIFICATIONS`。
- 任务取消会清理临时文件；翻译阶段取消保留解析结果。
- 所有用户可见错误为中文。

## 10. 验收清单

### 导入

- [ ] 文件选择器选 PDF 能创建任务并开始解析
- [ ] 从其他 App 分享 PDF 能进入并自动开始
- [ ] 粘贴 `https://...pdf` 能创建远程任务
- [ ] 超过 20MB 的文件被拒绝，且没有上传请求
- [ ] 分享进来的 `content://` 在切后台后仍可读

### 解析

- [ ] 有 Token 时使用精准解析，结果含图片
- [ ] 无 Token 时自动使用轻量解析，并提示无图片
- [ ] 精准解析支持 pipeline / vlm / MinerU-HTML
- [ ] 页码范围、OCR、公式、表格开关生效
- [ ] 进度显示已处理页/总页与阶段
- [ ] 锁屏/切后台任务继续
- [ ] 通知栏可取消，取消后临时文件清理
- [ ] 进程被杀后重开 App，轮询中的任务继续
- [ ] 401 显示「Token 无效」类中文提示
- [ ] 429 显示限流提示

### 结果

- [ ] 标题、列表、表格、图片预览正确
- [ ] 图片相对路径能加载
- [ ] 导出单 Markdown 到「下载/Assey」
- [ ] 导出 ZIP 含 Markdown 与 `images/`
- [ ] 分享 Markdown / ZIP 能打开系统分享面板

### 翻译

- [ ] 默认解析完成后自动翻译
- [ ] 设置里可关闭自动翻译
- [ ] 结果页可手动重译
- [ ] 原文与译文都保留，可切换
- [ ] 代码块、行内代码、公式、URL、图片路径逐字不变
- [ ] 长文分块后不丢不重
- [ ] 模型丢占位符时明确报错，不写损坏文件
- [ ] 翻译失败后原文仍可查看、导出

### 设置

- [ ] Token 加密保存，重装前不丢失
- [ ] Token 输入框脱敏，可切换显示
- [ ] 「测试 Token 是否有效」给出明确结果
- [ ] 翻译 Base URL / 模型 / Key 可修改
- [ ] 「测试翻译服务」能返回示例译文

## 11. 简化说明

| 简化 | 影响 | 何时需要升级 |
| --- | --- | --- |
| 单任务 | 导入新任务会取消旧任务 | 需要批量时改成任务队列 + 多通知 |
| JSON 持久化 | 任务多时全量读写 | 任务数上百时换 Room |
| 手写路由 | 无深层链接栈 | 页面变多时换 Navigation |
| 手写 Markdown 渲染 | 不支持全部 CommonMark | 需要脚注/任务列表/复杂嵌套时换 Markwon |
| 公式等宽展示 | 不做 KaTeX 排版 | 需要出版级公式时接 WebView/KaTeX |
| 翻译按空行分块 | 极端长表格可能被拆 | 可改成按 AST 分块 |
| 无多语言 | 只有中文 UI | 需要英文 UI 时抽 strings |
| 无账号 | Token 只在本机 | 需要多设备时加同步服务 |

## 12. 新增解析后端

1. 在 `data/parser/` 新建实现：

```kotlin
class MyParser(private val http: Http) : DocumentParser {
    override val id = ID
    override val supportsImages = true

    companion object {
        const val ID = "my-parser"
    }

    override suspend fun parse(
        request: ParseRequest,
        onProgress: (ParseProgress) -> Unit
    ): ParseResult {
        // 1. 上传 / 提交
        // 2. onProgress(ParseProgress(ParseStage.PARSING, resumeToken = taskId))
        // 3. 轮询
        // 4. 下载并解压，返回 markdown + assetsDir
        // 所有错误转换成 AppError.*
    }

    override suspend fun resume(
        resumeToken: String,
        dataId: String,
        options: ParseOptions,
        onProgress: (ParseProgress) -> Unit
    ): ParseResult {
        // 用 resumeToken 继续轮询
    }
}
```

2. 在 `ParserFactory` 中注册：

```kotlin
private val mine: DocumentParser by lazy { MyParser(http) }

fun byId(id: String?) = when (id) {
    MyParser.ID -> mine
    // ...
}
```

3. 如果需要在设置页暴露新选项，只加 `ParserKind` 枚举值与文案；`TaskRunner`、`TaskRecord`、UI 结果页都不用改。

## 13. 新增翻译后端

1. 实现 `Translator`：

```kotlin
class MyTranslator(
    private val http: Http,
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String
) : Translator {
    override val id = "my-translator"

    override suspend fun translate(
        markdown: String,
        target: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): String {
        // 复用 MarkdownTranslationEngine，只提供 ChatClient
        val engine = MarkdownTranslationEngine(
            chat = MarkdownTranslationEngine.ChatClient { prompt ->
                // 调用你的 HTTP 接口，返回纯文本
            }
        )
        return engine.translate(markdown, target, onProgress)
    }
}
```

2. 在 `TranslatorFactory.create` 中按 Base URL / 设置选择实现。

3. UI 与 `TaskRunner` 不需要任何改动。
