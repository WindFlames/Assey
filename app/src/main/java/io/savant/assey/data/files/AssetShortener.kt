package io.savant.assey.data.files

import java.io.File
import java.net.URLDecoder
import java.util.Locale

/**
 * 把解析产物里的图片从上游的 hash 长名（如 `images/02e94f...ddd3.jpg`）改成短序号
 * （`images/0.jpg`、`images/1.jpg`），并同步改写 Markdown 里的引用。
 *
 * 编号规则：
 *  - 先按 Markdown 中出现的先后顺序编号，读者看到的顺序就是 0,1,2…
 *  - 再给没被引用到的图片续号，避免包里残留 hash 长名
 *  - 序号按目录独立计数，子目录互不影响
 */
object AssetShortener {

    private val IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "tif", "tiff", "svg"
    )

    /** Markdown 图片：`![alt](path "title")`，path 允许被 `<>` 包住。 */
    private val MD_IMAGE = Regex("""!\[[^\]]*]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)""")

    /** 内联 HTML 图片：`<img src="path">`，常见于表格里的图。 */
    private val HTML_IMAGE = Regex("""<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /**
     * @param markdown 解析得到的原文
     * @param assetsDir 图片相对路径的根目录（即 full.md 所在目录），为 null 时原样返回
     * @return 引用已改写成短名的新 Markdown
     */
    fun shorten(markdown: String, assetsDir: File?): String {
        if (assetsDir == null || !assetsDir.isDirectory) return markdown

        val images = imageFiles(assetsDir)
        val refs = collectRefs(markdown)
        if (refs.isEmpty() && images.isEmpty()) return markdown

        // 先建立「文件 → 新相对路径」的计划，全部算好再动文件，避免中途失败留下半套名字
        val counters = HashMap<String, Int>()
        val planned = LinkedHashMap<String, Plan>() // 绝对路径 → 计划
        val resolved = ArrayList<Pair<Ref, Plan?>>(refs.size)

        for (ref in refs) {
            val file = resolve(ref.raw, assetsDir)
            if (file == null) {
                resolved.add(ref to null)
                continue
            }
            val plan = planned[canonical(file)] ?: run {
                val rel = relativePath(file, assetsDir) ?: return@run null
                val fresh = Plan(file, rel, nextName(rel, counters))
                planned[canonical(file)] = fresh
                fresh
            }
            resolved.add(ref to plan)
        }

        // 没被引用的图片也缩短，否则导出包里仍会残留 hash 名
        images.forEach { file ->
            if (planned.containsKey(canonical(file))) return@forEach
            val rel = relativePath(file, assetsDir) ?: return@forEach
            planned[canonical(file)] = Plan(file, rel, nextName(rel, counters))
        }

        val applied = LinkedHashMap<String, String>() // 绝对路径 → 实际生效的新相对路径
        applyRenames(planned.values) { plan -> applied[canonical(plan.file)] = plan.newRelative }

        if (applied.isEmpty()) return markdown

        val out = StringBuilder(markdown)
        resolved.asReversed().forEach { (ref, plan) ->
            if (plan == null) return@forEach
            val newPath = applied[canonical(plan.file)] ?: return@forEach
            if (newPath != plan.oldRelative) {
                out.replace(ref.range.first, ref.range.last + 1, newPath)
            }
        }
        return out.toString()
    }

    // ---------------- 解析与命名 ----------------

    private data class Ref(val range: IntRange, val raw: String)

    private data class Plan(val file: File, val oldRelative: String, val newRelative: String)

    private fun collectRefs(markdown: String): List<Ref> =
        (MD_IMAGE.findAll(markdown) + HTML_IMAGE.findAll(markdown))
            .mapNotNull { match ->
                val group = match.groups[1] ?: return@mapNotNull null
                Ref(group.range, group.value)
            }
            .sortedBy { it.range.first }
            .toList()

    private fun imageFiles(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS }
            .sortedBy { it.absolutePath }
            .toList()

    private fun nextName(relative: String, counters: MutableMap<String, Int>): String {
        val dir = relative.substringBeforeLast('/', "")
        val ext = relative.substringAfterLast('.', "")
        val index = counters[dir] ?: 0
        counters[dir] = index + 1
        val name = index.toString() + if (ext.isEmpty()) "" else ".$ext"
        return if (dir.isEmpty()) name else "$dir/$name"
    }

    /** 与预览侧一致：去空格、去尖括号、URL 解码、去掉 `./`，且必须落在 assetsDir 内。 */
    private fun resolve(raw: String, assetsDir: File): File? {
        var candidate = raw.trim().substringBefore(' ').trim('<', '>')
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) return null
        if (candidate.startsWith("data:")) return null
        candidate = runCatching { URLDecoder.decode(candidate, "UTF-8") }.getOrDefault(candidate)
        candidate = candidate.removePrefix("./")
        if (candidate.isEmpty()) return null
        val file = File(assetsDir, candidate)
        if (!file.isFile) return null
        return file.takeIf { relativePath(it, assetsDir) != null }
    }

    /** 相对路径；不在 assetsDir 内返回 null。 */
    private fun relativePath(file: File, root: File): String? {
        val base = canonical(root)
        val path = canonical(file)
        if (!path.startsWith(base + File.separator, ignoreCase = true)) return null
        return path.substring(base.length + 1).replace('\\', '/')
    }

    private fun canonical(file: File): String = file.absoluteFile.normalize().path

    /**
     * 两段式改名：先把所有待改文件挪到临时名，再统一落到目标名。
     * 否则 `a.jpg → 0.jpg` 与 `0.jpg → 1.jpg` 会互相覆盖（Android 上 rename 是静默覆盖的）。
     * 失败的文件回滚原名，只通过回调报告真正成功的。
     */
    private fun applyRenames(plans: Collection<Plan>, onRenamed: (Plan) -> Unit) {
        val pending = plans.filter { it.oldRelative != it.newRelative }
        if (pending.isEmpty()) return

        val staged = ArrayList<Triple<Plan, File, File>>()
        pending.forEachIndexed { index, plan ->
            val target = File(plan.file.parentFile, plan.newRelative.substringAfterLast('/'))
            val tmp = File(plan.file.parentFile, ".assey-rename-$index.tmp")
            if (plan.file.renameTo(tmp)) staged.add(Triple(plan, tmp, target))
        }
        staged.forEach { (plan, tmp, target) ->
            // 上一次解析留下的同名文件在这里清掉，避免临时名落不下去
            if (target.exists()) target.delete()
            if (tmp.renameTo(target)) onRenamed(plan) else runCatching { tmp.renameTo(plan.file) }
        }
    }
}
