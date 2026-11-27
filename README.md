# Assey

个人自用的 Android 小工具：把论文 PDF 解析成 Markdown，并一键翻译成中文。

- 输入：系统文件选择器（PDF / 图片）、其他 App 分享 PDF、粘贴远程 PDF 链接
- 解析：MinerU 精准 API（Markdown + 图片）或免 Token 的轻量 API
- 翻译：默认 Index-Translate，也兼容任意 OpenAI Chat Completions 端点
- 输出：App 内预览、导出 Markdown / ZIP 到系统「下载/Assey」、系统分享
- 全程前台服务，锁屏或切后台不中断；进程被杀后重开 App 可续轮询

> 只面向个人侧载使用：没有账号、云同步、批量队列和多语言 UI。

## 构建与安装

环境要求：

- Android Studio（支持 AGP 9.5.3 / Gradle 9.6）
- JDK 17+（Gradle 自带 toolchain 配置）
- Android SDK：`compileSdk 37`、`minSdk 33`、Build Tools 36.1.0
- 首次构建需要联网下载依赖

Windows：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

macOS / Linux：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 打开工程，选择 `app` 运行。

`local.properties` 中需要指向本机 SDK，例如：

```properties
sdk.dir=D\:\\Softwares\\AndroidSDK
```

## 首次使用

1. 打开 App → 右上角「设置」。
2. 填入 MinerU Token（可选，但只有精准解析才会返回图片），点「测试 Token 是否有效」。
3. 翻译服务默认已填好：
   - Base URL：`https://index-translate.bilibili.com/v1`
   - 模型：`Index-Translate-35B-A3B`
   - API Key：留空即使用 `EMPTY`
4. 回到首页，选择 PDF、分享 PDF 进来，或粘贴 PDF 链接。
5. 等待解析完成；默认会自动翻译，可在设置里关闭。
6. 在结果页切换「原文 / 中文译文」，导出或分享。

### 解析后端规则

| 设置 | 有 Token | 无 Token |
| --- | --- | --- |
| 自动 | 精准解析（≤20MB，带图片） | 轻量解析（≤10MB，无图片） |
| 精准 | 精准解析（≤20MB） | 直接报错，提示先配置 Token |
| 轻量 | 轻量解析（≤10MB，无图片） | 轻量解析（≤10MB，无图片） |

本地文件超过 20MB 时，在导入阶段就会拒绝，不会复制或上传；远程链接无法在本地预知大小，由后端返回 413 时统一提示文件过大。

## 项目结构

```
app/src/main/java/io/savant/assey/
├── MainActivity.kt              Compose 入口 + 分享/打开 Intent
├── AsseyApp.kt                  Application，持有 AppContainer
├── AppContainer.kt              手动依赖注入 + 中断任务恢复
├── core/                        错误模型、HTTP、中文提示
├── domain/                      领域模型与后端接口（无 Android/后端专有概念）
├── data/
│   ├── TaskRunner.kt            复制 → 解析 → 落盘 → 翻译 流水线
│   ├── files/FileIntake.kt      content:// 流式复制、大小检查、文件名清洗
│   ├── parser/                  MinerU 精准 / 轻量实现与工厂
│   ├── translate/               Index-Translate / OpenAI 兼容实现与工厂
│   ├── store/                   Settings / Task 持久化、Keystore 加密
│   ├── zip/                     解压（防 Zip Slip）与打包
│   └── export/                  MediaStore 导出与 FileProvider 分享
├── md/                          占位符保护、分块、翻译引擎、预览解析
├── service/                     前台服务与通知
└── ui/                          Compose 页面、控制器、Markdown 渲染
```

## 测试

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

当前覆盖 24 个 JVM 单元测试：

- `MarkdownProtectorTest`：代码块、行内代码、公式、URL、图片路径、HTML、表格分隔行的保护与还原；占位符缺失/重复/未知检测
- `MarkdownChunkerTest`：分块拼接恒等、长段落切分、占位符不被切断、空行原样保留
- `MarkdownTranslationEngineTest`：恒等翻译、占位符还原、代码围栏清理、失败重试、最终失败报错、进度回调
- `MarkdownBlocksTest`：预览用的标题/段落/代码/表格/列表/图片/公式解析

## 更多文档

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)：架构、关键流程、验收清单、简化说明、扩展后端方法

## 已知限制

- 一次只跑一个任务；导入新任务会取消正在跑的任务。
- 轻量解析不支持图片，单文件上限 10MB / 20 页。
- 公式以等宽文本展示，不做 KaTeX 排版。
- 上传/复制阶段被系统杀死后无法安全续传，会标记失败并提示重新提交；已进入轮询阶段的任务可以续跑。
- 前台服务受系统省电策略影响，极端情况下仍可能被厂商 ROM 杀死。
