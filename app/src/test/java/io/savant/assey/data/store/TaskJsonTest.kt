package io.savant.assey.data.store

import io.savant.assey.domain.MineruModel
import io.savant.assey.domain.ParseOptions
import io.savant.assey.domain.SourceKind
import io.savant.assey.domain.TaskRecord
import io.savant.assey.domain.TaskStatus
import io.savant.assey.domain.TranslateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskJsonTest {

    @Test
    fun `round trip keeps every field`() {
        val original = TaskRecord(
            id = "task-1",
            title = "论文.pdf",
            sourceKind = SourceKind.REMOTE_URL,
            remoteUrl = "https://example.com/a.pdf",
            backendId = "mineru-precision",
            backendName = "MinerU 精准解析",
            options = ParseOptions(model = MineruModel.PIPELINE, pageRanges = "1-5", language = "en"),
            status = TaskStatus.TRANSLATING,
            stageLabel = "翻译中 3/10",
            pagesDone = 3,
            pagesTotal = 10,
            bytesDone = 1024,
            bytesTotal = 2048,
            resumeToken = "batch-9",
            markdownPath = "/tmp/full.md",
            translateStatus = TranslateStatus.RUNNING,
            supportsImages = false,
            autoTranslate = true
        )

        val restored = TaskJson.decode(TaskJson.encode(listOf(original))).single()

        assertEquals(original, restored)
    }

    @Test
    fun `model is stored as official value and read back as enum`() {
        val task = TaskRecord(
            id = "t",
            title = "a",
            sourceKind = SourceKind.LOCAL_FILE,
            options = ParseOptions(model = MineruModel.HTML)
        )

        val json = TaskJson.encode(listOf(task))
        assertTrue("model 应存官方取值", json.contains("\"model\":\"MinerU-HTML\""))

        val restored = TaskJson.decode(json).single()
        assertEquals(MineruModel.HTML, restored.options.model)
    }

    @Test
    fun `unknown enum and missing fields fall back to safe defaults`() {
        // 模拟旧版本/手改文件：字段缺失、枚举值不认识
        val legacy = """
            [
              {
                "id": "old-1",
                "title": "旧任务",
                "sourceKind": "SOMETHING_NEW",
                "status": "PAUSED",
                "translateStatus": "WHATEVER",
                "options": { "model": "vlm", "language": "ch" }
              }
            ]
        """.trimIndent()

        val restored = TaskJson.decode(legacy).single()

        assertEquals("old-1", restored.id)
        assertEquals(SourceKind.LOCAL_FILE, restored.sourceKind)
        assertEquals(TaskStatus.FAILED, restored.status)
        assertEquals(TranslateStatus.NONE, restored.translateStatus)
        assertEquals(MineruModel.VLM, restored.options.model)
        // 缺失字段走 TaskRecord 自身的默认值
        assertEquals(0, restored.pagesTotal)
        assertEquals(false, restored.autoTranslate)
    }

    @Test
    fun `records without id are dropped`() {
        val json = """[{"title":"没有 id"},{"id":"ok","title":"正常","sourceKind":"LOCAL_FILE"}]"""
        val restored = TaskJson.decode(json)
        assertEquals(listOf("ok"), restored.map { it.id })
    }
}
