package com.lele.lelenote

import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v1.2：把笔记导出为 Word（.docx，截图内嵌），方便直接阅读。
 * 零第三方依赖：.docx 本质是一个 ZIP 包，手工拼 OOXML 打包即可。
 * 结构：
 *   [Content_Types].xml
 *   _rels/.rels
 *   word/document.xml
 *   word/_rels/document.xml.rels
 *   word/media/imgN.jpeg|png
 */
object DocxExport {

    private const val EMU_PER_INCH = 914400L
    // A4 内容区宽约 6.27in，图片统一压到 5.8in 以内
    private const val MAX_IMG_WIDTH_EMU = (5.8 * EMU_PER_INCH).toLong()

    fun buildDocx(notes: List<Note>): ByteArray {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val media = mutableListOf<Pair<String, ByteArray>>() // (media文件名, 字节)
        val rels = mutableListOf<String>()                   // document.xml.rels 条目
        val body = StringBuilder()

        // 文档标题
        body.append(paragraph("乐乐速记 导出", bold = true, sizeHalfPt = 32, center = true))
        body.append(paragraph("共 ${notes.size} 条笔记 · ${df.format(Date())}", sizeHalfPt = 18, center = true))

        notes.forEachIndexed { idx, n ->
            val head = "第 ${idx + 1} 条 · ${df.format(Date(if (n.updatedAt > 0) n.updatedAt else n.createdAt))}"
            body.append(paragraph(head, bold = true, sizeHalfPt = 22))

            // 正文：按行拆段落，空行保留
            val text = n.text.ifBlank { if (n.images.isEmpty()) "（空笔记）" else "（无文字）" }
            text.split('\n').forEach { line ->
                body.append(paragraph(escape(line), sizeHalfPt = 22))
            }

            // 图片
            n.images.forEachIndexed { imgIdx, path ->
                val bytes = try { java.io.File(path).takeIf { it.exists() }?.readBytes() } catch (_: Exception) { null }
                if (bytes != null) {
                    val mime = sniffMime(bytes, path)
                    val ext = if (mime == "image/png") "png" else "jpeg"
                    val mediaName = "img${media.size + 1}.$ext"
                    media.add(mediaName to bytes)
                    val relId = "rIdImg${media.size}"
                    rels.add(
                        "<Relationship Id=\"$relId\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$mediaName\"/>"
                    )
                    body.append(imageParagraph(relId, media.size + 1, bytes))
                }
            }

            if (idx != notes.size - 1) {
                body.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"1\" w:color=\"D9D7E3\"/></w:pBdr></w:pPr></w:p>")
            }
        }

        val documentXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"")
            append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"")
            append(" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">")
            append("<w:body>")
            append(body)
            append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
            append("<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr>")
            append("</w:body></w:document>")
        }

        val contentTypes = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
            append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
            append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
            append("</Types>")
        }

        val rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                "</Relationships>"

        val docRels = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            rels.forEach { append(it) }
            append("</Relationships>")
        }

        return ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zip ->
                put(zip, "[Content_Types].xml", contentTypes.toByteArray(Charsets.UTF_8))
                put(zip, "_rels/.rels", rootRels.toByteArray(Charsets.UTF_8))
                put(zip, "word/document.xml", documentXml.toByteArray(Charsets.UTF_8))
                put(zip, "word/_rels/document.xml.rels", docRels.toByteArray(Charsets.UTF_8))
                media.forEach { (name, bytes) -> put(zip, "word/media/$name", bytes) }
            }
            bos.toByteArray()
        }
    }

    private fun put(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun paragraph(
        text: String,
        bold: Boolean = false,
        sizeHalfPt: Int = 22,
        center: Boolean = false
    ): String {
        val rPr = buildString {
            append("<w:rPr>")
            if (bold) append("<w:b/>")
            append("<w:color w:val=\"20223A\"/>")
            append("<w:sz w:val=\"$sizeHalfPt\"/><w:szCs w:val=\"$sizeHalfPt\"/>")
            append("</w:rPr>")
        }
        val jc = if (center) "<w:jc w:val=\"center\"/>" else ""
        return "<w:p><w:pPr>$jc</w:pPr><w:r>$rPr<w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"
    }

    private fun imageParagraph(relId: String, docPrId: Int, imgBytes: ByteArray): String {
        // 取原始尺寸算等比缩放（只读 bounds，不解码像素，省内存）
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size, opt)
        var w = opt.outWidth
        var h = opt.outHeight
        if (w <= 0 || h <= 0) { w = 1080; h = 1920 }
        var cx = MAX_IMG_WIDTH_EMU
        var cy = cx * h / w
        if (cy > (7.5 * EMU_PER_INCH).toLong()) { // 高也别超一页
            cy = (7.5 * EMU_PER_INCH).toLong()
            cx = cy * w / h
        }
        return "<w:p><w:r><w:drawing>" +
                "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
                "<wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
                "<wp:docPr id=\"$docPrId\" name=\"Picture $docPrId\"/>" +
                "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
                "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                "<pic:nvPicPr><pic:cNvPr id=\"$docPrId\" name=\"img$docPrId\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                "<pic:blipFill><a:blip r:embed=\"$relId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
                "</pic:pic></a:graphicData></a:graphic></wp:inline>" +
                "</w:drawing></w:r></w:p>"
    }

    private fun sniffMime(bytes: ByteArray, path: String): String {
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return "image/png"
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
        return if (path.lowercase(Locale.US).endsWith(".png")) "image/png" else "image/jpeg"
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
