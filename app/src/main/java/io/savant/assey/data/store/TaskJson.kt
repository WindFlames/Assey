package io.savant.assey.data.store

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import io.savant.assey.domain.MineruModel
import io.savant.assey.domain.SourceKind
import io.savant.assey.domain.TaskRecord
import io.savant.assey.domain.TaskStatus
import io.savant.assey.domain.TranslateStatus
import java.lang.reflect.Type

/**
 * tasks.json 的序列化入口，用 Gson 直接读写 [TaskRecord]，不再手写字段映射。
 *
 * 兼容性约定：
 *  - 字段名与 [TaskRecord] 属性名一致，沿用旧文件的键名，无需迁移
 *  - 枚举按名字持久化；MineruModel 例外，按 [MineruModel.value]（vlm / pipeline / MinerU-HTML）存
 *  - 未知/缺失的枚举值回落到安全默认值，避免一个坏字段让整条任务都读不出来
 *  - 缺字段时先用一条合法空记录兜底，Gson 只覆盖文件里实际存在的字段
 */
internal object TaskJson {

    private val listType: Type = object : TypeToken<List<TaskRecord>>() {}.type

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(TaskRecord::class.java, object : InstanceCreator<TaskRecord> {
            override fun createInstance(type: Type): TaskRecord =
                TaskRecord(id = "", title = "", sourceKind = SourceKind.LOCAL_FILE)
        })
        .registerTypeAdapter(MineruModel::class.java, MineruModelAdapter)
        .registerTypeAdapter(
            TaskStatus::class.java,
            EnumFallbackAdapter(TaskStatus::class.java, TaskStatus.FAILED)
        )
        .registerTypeAdapter(
            TranslateStatus::class.java,
            EnumFallbackAdapter(TranslateStatus::class.java, TranslateStatus.NONE)
        )
        .registerTypeAdapter(
            SourceKind::class.java,
            EnumFallbackAdapter(SourceKind::class.java, SourceKind.LOCAL_FILE)
        )
        .create()

    fun encode(tasks: List<TaskRecord>): String = gson.toJson(tasks, listType)

    fun decode(text: String): List<TaskRecord> =
        gson.fromJson<List<TaskRecord>>(text, listType)
            .orEmpty()
            .filter { it.id.isNotBlank() }
            .sortedByDescending { it.createdAt }
}

/** `model_version` 用官方取值（vlm / pipeline / MinerU-HTML），而不是枚举名。 */
private object MineruModelAdapter : TypeAdapter<MineruModel>() {

    override fun write(out: JsonWriter, value: MineruModel?) {
        if (value == null) out.nullValue() else out.value(value.value)
    }

    override fun read(input: JsonReader): MineruModel {
        if (input.peek() == JsonToken.NULL) {
            input.nextNull()
            return MineruModel.DEFAULT
        }
        return MineruModel.fromValue(input.nextString())
    }
}

/** 枚举按 name 存；读到未知值（改名、降级、手改文件）时回落到 [fallback]。 */
private class EnumFallbackAdapter<T : Enum<T>>(
    private val type: Class<T>,
    private val fallback: T
) : TypeAdapter<T>() {

    override fun write(out: JsonWriter, value: T?) {
        if (value == null) out.nullValue() else out.value(value.name)
    }

    override fun read(input: JsonReader): T {
        if (input.peek() == JsonToken.NULL) {
            input.nextNull()
            return fallback
        }
        val raw = input.nextString()
        return type.enumConstants?.firstOrNull { it.name == raw } ?: fallback
    }
}
