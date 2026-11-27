package io.savant.assey.data.zip

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `json and pdf are skipped while other files are kept`() {
        val src = tmp.newFolder("assets")
        val md = write(src, "full.md", "# hi")
        val png = write(src, "images/a.png", "png")
        write(src, "document.pdf", "%PDF-1.7")
        write(src, "layout.json", "{}")
        write(src, "blocks/block-1.json", "[]")
        write(src, "UPPER.PDF", "%PDF-1.7")

        val out = File(tmp.root, "out.zip")
        ZipWriter().write(
            listOf(
                md to "full.md",
                png to "images/a.png",
                File(src, "document.pdf") to "document.pdf",
                File(src, "layout.json") to "layout.json",
                File(src, "blocks/block-1.json") to "blocks/block-1.json",
                File(src, "UPPER.PDF") to "sub/UPPER.PDF"
            ),
            out
        )

        val names = ZipFile(out).use { zip -> zip.entries().toList().map { it.name }.sorted() }
        assertEquals(listOf("full.md", "images/a.png"), names)
    }

    @Test
    fun `empty exclude set packs everything`() {
        val src = tmp.newFolder("raw")
        val pdf = write(src, "a.pdf", "%PDF-1.7")
        val json = write(src, "a.json", "{}")
        val out = File(tmp.root, "raw.zip")

        ZipWriter(excludedExtensions = emptySet()).write(
            listOf(pdf to "a.pdf", json to "a.json"),
            out
        )

        ZipFile(out).use { zip ->
            assertTrue(zip.getEntry("a.pdf") != null)
            assertTrue(zip.getEntry("a.json") != null)
        }
    }

    @Test
    fun `dotted directories do not confuse extension detection`() {
        val src = tmp.newFolder("dots")
        val file = write(src, "data-v1.2/readme", "text")
        val out = File(tmp.root, "dots.zip")

        ZipWriter().write(listOf(file to "data-v1.2/readme"), out)

        val names = ZipFile(out).use { zip -> zip.entries().toList().map { it.name } }
        assertEquals(listOf("data-v1.2/readme"), names)
    }

    private fun write(dir: File, relative: String, content: String): File {
        val file = File(dir, relative)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }
}
