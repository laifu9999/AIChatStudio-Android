package com.lele.mobipaint

import android.content.Context

data class UpdBlock(val cat: String, val title: String, val content: String)

/** AI 回复里的协议块解析：设定沉淀 / 题材回写（与 PC 端口径一致）。 */
object Protocols {
    private val SET_RE = Regex("【设定更新】([^【\\n]+?)\\|([^【\\n\\|]+?)\\|([\\s\\S]*?)【/设定更新】")
    private val GENRE_RE = Regex("【题材更新】([\\s\\S]*?)【/题材更新】")
    private val ALLOWED_CATS = setOf("人物设定", "世界观", "大纲", "随记")
    private val JUNK_MARK = Regex("（在此写下|（请填写|待补充|TODO|xxx|XXX")

    fun parseSettingBlocks(text: String): List<UpdBlock> {
        val out = ArrayList<UpdBlock>()
        for (m in SET_RE.findAll(text)) {
            val cat = m.groupValues[1].trim()
            val title = m.groupValues[2].trim()
            val content = m.groupValues[3].trim()
            if (cat !in ALLOWED_CATS) continue
            if (title.isEmpty() || content.isEmpty()) continue
            if (content.length < 4) continue
            if (JUNK_MARK.containsMatchIn(content)) continue
            out.add(UpdBlock(cat, title, content))
        }
        return out
    }

    fun parseGenre(text: String): String? {
        val m = GENRE_RE.find(text) ?: return null
        val g = m.groupValues[1].trim()
        if (g.isEmpty() || g.length > 30) return null
        if (JUNK_MARK.containsMatchIn(g)) return null
        return g
    }

    /** 剥掉协议块后的可见正文。 */
    fun visibleOf(text: String): String {
        var t = SET_RE.replace(text, "")
        t = GENRE_RE.replace(t, "")
        return t.trim()
    }

    /** 应用设定沉淀与题材更新到数据库；返回给用户看的应用摘要（可能为空）。 */
    fun apply(ctx: Context?, pid: Long, reply: String): List<String> {
        val applied = ArrayList<String>()
        for (b in parseSettingBlocks(reply)) {
            Db.upsertSetting(pid, b.cat, b.title, b.content)
            applied.add("已记下「${b.title}」（${b.cat}）")
        }
        val g = parseGenre(reply)
        if (g != null) {
            val cur = Db.project(pid)?.genre ?: ""
            if (g != cur) {
                Db.updateProject(pid, genre = g)
                applied.add("题材已更新为：$g")
            }
        }
        return applied
    }

    /** 拆「第N章 标题 + 正文」。 */
    fun splitTitle(no: Int, text: String): Pair<String, String> {
        val t = text.trim()
        val re = Regex("^#{0,3}\\s*第\\s*\\d+\\s*章\\s*(.*)$")
        val firstLineEnd = t.indexOf('\n')
        val firstLine = if (firstLineEnd >= 0) t.substring(0, firstLineEnd).trim() else t
        val m = re.find(firstLine)
        if (m != null) {
            val title = m.groupValues[1].trim().ifEmpty { "第${no}章" }
            val body = if (firstLineEnd >= 0) t.substring(firstLineEnd + 1).trim() else ""
            return Pair(title, if (body.isEmpty()) t else body)
        }
        return Pair("第${no}章", t)
    }
}
