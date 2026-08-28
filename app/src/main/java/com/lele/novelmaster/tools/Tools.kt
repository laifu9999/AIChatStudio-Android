package com.lele.novelmaster.tools

import android.content.Context
import com.lele.novelmaster.data.AutoWriteManager
import com.lele.novelmaster.data.CardCategories
import com.lele.novelmaster.data.Chapter
import com.lele.novelmaster.data.Project
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.data.SettingCard
import com.lele.novelmaster.data.WriterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 单条工具执行结果（用于即时回放给用户） */
data class ToolResult(
    val ok: Boolean = true,
    val summary: String = "",
    val detail: String = "",
    val navigateTo: String? = null,
    val newProjectId: Long? = null
)

/**
 * 全部可被聊天 / IntentRouter 调用的本地工具。
 * 关键：所有操作都直接落数据库（设定卡、章节都写到 Room），
 * 因此"和 AI 聊天完成的修改"会立即在所有界面可见。
 */
object Tools {

    // ---------- 项目 / 设定卡 ----------

    suspend fun listProjects(): ToolResult {
        val snap: List<Project> = withContext(Dispatchers.IO) { Repo.dao.projectsFlow().first() }
        if (snap.isEmpty()) return ToolResult(false, "还没有任何小说项目。")
        val text = snap.withIndex().joinToString("\n") { (i, p) ->
            "${i + 1}. 《${p.title}》 (ID=${p.id}, ${if (p.genre.isBlank()) "未分类" else p.genre}, 目标${p.targetChapters}章)"
        }
        return ToolResult(true, "你共有 ${snap.size} 本小说：", text)
    }

    suspend fun createProject(title: String, genre: String, desc: String, totalCh: Int, chWords: Int): ToolResult {
        val pid = withContext(Dispatchers.IO) {
            val p = Project(
                title = title.ifBlank { "未命名小说" },
                genre = genre.ifBlank { "玄幻" },
                description = desc,
                targetChapters = totalCh.coerceAtLeast(1),
                chapterWordTarget = chWords.coerceAtLeast(500)
            )
            val id = Repo.dao.insertProject(p)
            // 创建分章骨架
            val list = (1..p.targetChapters).map { Chapter(projectId = id, chapterIndex = it) }
            Repo.dao.insertChapters(list)
            id
        }
        return ToolResult(true, "已创建项目《$title》", "项目 ID = $pid ，已生成 ${totalCh} 章骨架。", newProjectId = pid)
    }

    suspend fun switchProject(pid: Long): ToolResult {
        val p = withContext(Dispatchers.IO) { Repo.dao.project(pid) } ?: return ToolResult(false, "找不到该 ID 的项目")
        return ToolResult(true, "已切换到《${p.title}》", "目标 ${p.targetChapters} 章 / 每章 ${p.chapterWordTarget} 字")
    }

    suspend fun listCards(pid: Long, cat: String? = null, query: String? = null): ToolResult {
        val all = withContext(Dispatchers.IO) { Repo.dao.cards(pid) }
        val filtered = all.filter { c ->
            (cat == null || c.category == cat) &&
                (query.isNullOrBlank() || c.name.contains(query, true) || c.content.contains(query, true))
        }
        if (filtered.isEmpty()) return ToolResult(false, "没有匹配的设定卡", "可用分类：" + CardCategories.all.joinToString("、"))
        val byCat = filtered.groupBy { it.category }
        val detail = byCat.entries.joinToString("\n\n") { (k, v) ->
            "📂 $k（${v.size} 张）\n" + v.joinToString("\n") { c ->
                "  • ${c.name}" +
                    (if (c.category == "伏笔钩子") "（${c.status.ifBlank { "埋设中" }}）" else "") +
                    "：${c.content.take(120)}"
            }
        }
        return ToolResult(true, "设定卡共 ${filtered.size} 张：", detail)
    }

    suspend fun addCard(pid: Long, category: String, name: String, content: String, priority: Int? = null): ToolResult {
        if (category !in CardCategories.all) return ToolResult(false, "未知分类「$category」", "可选：" + CardCategories.all.joinToString("、"))
        if (name.isBlank() || content.isBlank()) return ToolResult(false, "名称和内容不能为空")
        val prio = priority ?: when (category) {
            "设定圣经", "世界观", "主线剧情", "核心冲突", "剧情进度" -> 2
            "伏笔钩子" -> 1
            else -> 1
        }
        val status = if (category == "伏笔钩子") "埋设中" else ""
        val id = withContext(Dispatchers.IO) {
            val exist = Repo.dao.findCard(pid, category, name)
            if (exist != null) {
                Repo.dao.updateCard(exist.copy(content = content, priority = prio, status = status))
                exist.id
            } else {
                Repo.dao.insertCard(
                    SettingCard(projectId = pid, category = category, name = name, content = content, priority = prio, status = status)
                )
            }
        }
        return ToolResult(true, "✅ 已存入设定卡", "「$category / $name」已保存（id=$id）")
    }

    suspend fun deleteCard(pid: Long, cardId: Long): ToolResult {
        val c = withContext(Dispatchers.IO) { Repo.dao.cards(pid).firstOrNull { it.id == cardId } }
            ?: return ToolResult(false, "找不到该设定卡")
        withContext(Dispatchers.IO) { Repo.dao.deleteCard(c) }
        return ToolResult(true, "已删除", "「${c.category} / ${c.name}」已移除")
    }

    // ---------- 章节 ----------

    suspend fun listChapters(pid: Long, onlyMissing: Boolean = false): ToolResult {
        val chs = withContext(Dispatchers.IO) { Repo.dao.chapters(pid) }
        val list = if (onlyMissing) chs.filter { it.content.isBlank() } else chs
        if (list.isEmpty()) return ToolResult(false, "项目下暂无章节")
        val detail = list.take(80).joinToString("\n") { c ->
            val status = when (c.status) { 2 -> "✅"; 1 -> "✍"; else -> "⚪" }
            "$status 第${c.chapterIndex}章《${c.title.ifBlank { "未命名" }}》 ${c.wordCount}字"
        }
        return ToolResult(true, "共 ${chs.size} 章（${list.size} 显示）", detail)
    }

    suspend fun readChapter(pid: Long, idx: Int): ToolResult {
        val c = withContext(Dispatchers.IO) { Repo.dao.chapters(pid).firstOrNull { it.chapterIndex == idx } }
            ?: return ToolResult(false, "第 $idx 章不存在")
        return ToolResult(true, "第${idx}章《${c.title}》", c.content.ifBlank { c.outline }, navigateTo = "editor/${c.id}")
    }

    suspend fun writeNextChapter(pid: Long): ToolResult {
        val cfg = withContext(Dispatchers.IO) { Repo.dao.activeApi() } ?: return ToolResult(false, "请先在【AI模型】中添加并启用一个模型")
        val chs = withContext(Dispatchers.IO) { Repo.dao.chapters(pid) }
        val project = withContext(Dispatchers.IO) { Repo.dao.project(pid) } ?: return ToolResult(false, "项目不存在")
        val next = chs.firstOrNull { it.content.isBlank() } ?: return ToolResult(false, "全部章节都已写完 ✅")
        return try {
            WriterEngine.writeOne(project, cfg, Repo.dao, next)
            val fresh = withContext(Dispatchers.IO) { Repo.dao.chapter(next.id) }!!
            ToolResult(true, "✅ 第${fresh.chapterIndex}章《${fresh.title}》已写入", "${fresh.wordCount} 字 · 摘要：${fresh.summary}", navigateTo = "editor/${fresh.id}")
        } catch (e: Exception) {
            ToolResult(false, "写作失败：${e.message?.take(200)}")
        }
    }

    suspend fun rewriteChapter(pid: Long, idx: Int): ToolResult {
        val ch = withContext(Dispatchers.IO) { Repo.dao.chapters(pid).firstOrNull { it.chapterIndex == idx } }
            ?: return ToolResult(false, "第 $idx 章不存在")
        val err = WriterEngine.rewriteChapter(ch.id)
        return if (err == null) ToolResult(true, "第 $idx 章已重写完成") else ToolResult(false, err)
    }

    // ---------- 自动写作 / 大纲 ----------

    suspend fun startAutoWrite(pid: Long, from: Int, to: Int): ToolResult {
        // 自定义范围 1~600；实际章节不足时按实际章节遍历，写完自动停
        val f = from.coerceIn(1, 600)
        val t = to.coerceIn(f, 600)
        AutoWriteManager.start(pid, f, t)
        return ToolResult(true, "🚀 自动写作已开始", "范围：第 $f ~ $t 章（实际章节不足时写到最后一章自动停）。进度在聊天里实时播报，随时说「停止写作」中止。", navigateTo = "autowrite/$pid")
    }

    suspend fun stopAutoWrite(): ToolResult { AutoWriteManager.stop(); return ToolResult(true, "已请求停止自动写作") }

    suspend fun generateOutlines(pid: Long): ToolResult {
        return withContext(Dispatchers.IO) {
            val err = WriterEngine.ensureOutlines(pid)
            if (err != null) ToolResult(false, err) else ToolResult(true, "已补齐缺失的分章大纲")
        }
    }

    suspend fun inspireFromText(pid: Long, inspiration: String): ToolResult {
        if (inspiration.isBlank()) return ToolResult(false, "请把你的灵感 / 需求发给我")
        return withContext(Dispatchers.IO) {
            val err = WriterEngine.generateCardsFromInspire(pid, inspiration)
            if (err != null) return@withContext ToolResult(false, err)
            // 灵感落地后自动补齐分章大纲，让"一个灵感发过去就自动完成"
            val outlineErr = WriterEngine.ensureOutlines(pid)
            ToolResult(true, "已根据你的灵感生成完整设定卡" + (if (outlineErr == null) "，并自动补齐了分章大纲" else ""),
                "可以继续聊天修改设定，或直接说「写下一章」「自动写作」开写。")
        }
    }

    /** 删除会话（= 删除小说项目，级联删章节/设定卡/聊天记录） */
    suspend fun deleteProject(pid: Long): ToolResult {
        val p = withContext(Dispatchers.IO) { Repo.dao.project(pid) } ?: return ToolResult(false, "会话不存在")
        withContext(Dispatchers.IO) {
            Repo.dao.deleteChaptersOf(pid)
            Repo.dao.deleteCardsOf(pid)
            Repo.dao.clearMessages(pid)
            Repo.dao.deleteProject(p)
        }
        return ToolResult(true, "已删除会话《${p.title}》", "其章节、设定卡、聊天记录已一并清除。")
    }

    /**
     * 上下文注入预览：查看「写下一章」时 AI 将看到什么（豆包式透明化）。
     * 与 Prompts.buildChapterMessages 完全同源，保证所见即所发。
     */
    suspend fun contextPreview(pid: Long): ToolResult {
        val project = withContext(Dispatchers.IO) { Repo.dao.project(pid) } ?: return ToolResult(false, "项目不存在")
        val cards = withContext(Dispatchers.IO) { Repo.dao.cards(pid) }
        val chapters = withContext(Dispatchers.IO) { Repo.dao.chapters(pid) }
        val next = chapters.firstOrNull { it.content.isBlank() }
            ?: return ToolResult(false, "全部章节已写完，无下一章可预览")

        val selected = com.lele.novelmaster.data.Prompts.selectCards(cards, next.outline + next.title)
        val recent = chapters.filter { it.chapterIndex < next.chapterIndex && it.summary.isNotBlank() }.takeLast(5)
        val prev = chapters.firstOrNull { it.chapterIndex == next.chapterIndex - 1 }
        val neighbors = chapters
            .filter { it.chapterIndex in (next.chapterIndex - 1)..(next.chapterIndex + 1) && it.outline.isNotBlank() }
            .joinToString("\n") { "第${it.chapterIndex}章《${it.title}》:${it.outline.take(60)}" }

        val chars = com.lele.novelmaster.data.Prompts.buildChapterMessages(project, cards, chapters, next)
            .sumOf { it.content.length }
        val detail = buildString {
            appendLine("▶ 目标：第 ${next.chapterIndex} 章《${next.title.ifBlank { "未命名" }}》")
            appendLine()
            appendLine("【必发设定卡 + 未回收伏笔 + 相关卡】${selected.size} 张")
            selected.forEach { appendLine("  • ${it.category}/${it.name}（${it.content.length}字）") }
            appendLine()
            appendLine("【前5章剧情摘要】${recent.size} 条")
            recent.forEach { appendLine("  • 第${it.chapterIndex}章：${it.summary.take(50)}…") }
            appendLine()
            appendLine("【上一章结尾】${if (prev != null && prev.content.isNotBlank()) "${prev.content.takeLast(600).length} 字（取结尾600字）" else "无"}")
            appendLine()
            appendLine("【相邻章节大纲】")
            appendLine(if (neighbors.isBlank()) "  无" else neighbors)
            appendLine()
            append("本次注入合计约 $chars 字（≈${chars * 4 / 10}0 tokens），正文不随历史章节数膨胀。")
        }
        return ToolResult(true, "下一章（第${next.chapterIndex}章）将注入的上下文：", detail)
    }

    // ---------- AI / 模型 ----------

    suspend fun listApis(): ToolResult {
        val apis = withContext(Dispatchers.IO) { Repo.dao.apiConfigsFlow().first() }
        if (apis.isEmpty()) return ToolResult(false, "还没添加任何 AI 接口", "去侧边菜单「AI模型」添加一个吧（推荐智谱 glm-4-flash，免费）")
        val detail = apis.joinToString("\n") { "  ${if (it.isActive) "🟢" else "⚪"} ${it.name} · ${it.model.ifBlank { "(未选模型)" }} · ${it.baseUrl}" }
        return ToolResult(true, "你添加了 ${apis.size} 个 AI 接口：", detail)
    }

    suspend fun testApi(apiId: Long): ToolResult {
        val all = withContext(Dispatchers.IO) { Repo.dao.apiConfigsFlow().first() }
        val cfg = all.firstOrNull { it.id == apiId } ?: return ToolResult(false, "找不到该接口")
        if (cfg.model.isBlank()) return ToolResult(false, "请先选择模型再测试")
        return try {
            val r = com.lele.novelmaster.data.AiClient.testConnection(cfg)
            ToolResult(true, "✅ 连接成功", "模型回复：$r")
        } catch (e: Exception) {
            ToolResult(false, "❌ 连接失败：${e.message?.take(200)}")
        }
    }

    // ---------- 导出 ----------

    suspend fun exportTxt(pid: Long, context: Context?): ToolResult {
        val project = withContext(Dispatchers.IO) { Repo.dao.project(pid) } ?: return ToolResult(false, "项目不存在")
        val chs = withContext(Dispatchers.IO) { Repo.dao.chapters(pid) }.filter { it.content.isNotBlank() }
        if (chs.isEmpty()) return ToolResult(false, "项目下还没有已写章节")
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
        val name = "${project.title}_${fmt.format(Date())}.txt"
        val body = buildString {
            appendLine("《${project.title}》  ${project.genre}")
            appendLine("=".repeat(40)); appendLine(project.description); appendLine()
            chs.forEach { c ->
                appendLine(); appendLine("第${c.chapterIndex}章 ${c.title}"); appendLine("-".repeat(30)); appendLine(c.content)
            }
        }
        val outDir = File(context?.getExternalFilesDir(null)?.absolutePath ?: ".")
        val outFile = File(outDir, name)
        outFile.writeText(body, Charsets.UTF_8)
        return ToolResult(true, "已导出 ${chs.size} 章到本地", "路径：${outFile.absolutePath}\n可使用文件管理器分享到起点/番茄/七猫等平台。")
    }

    // ---------- 章节移动 / 复制 ----------

    /** 把第 fromIdx 章移动到 toIdx 位置（中间章节序号整体平移） */
    suspend fun moveChapter(pid: Long, fromIdx: Int, toIdx: Int): ToolResult {
        if (fromIdx == toIdx) return ToolResult(false, "位置相同，无需移动")
        return withContext(Dispatchers.IO) {
            val chs = Repo.dao.chapters(pid).toMutableList()
            val from = chs.firstOrNull { it.chapterIndex == fromIdx }
                ?: return@withContext ToolResult(false, "第 $fromIdx 章不存在")
            val target = toIdx.coerceIn(1, chs.size)
            // 摘出被移动章
            val moved = chs.first { it.id == from.id }
            val others = chs.filter { it.id != from.id }.sortedBy { it.chapterIndex }
            // 重新编号并落库
            val reordered = buildList {
                addAll(others.filter { it.chapterIndex < target })
                add(moved)
                addAll(others.filter { it.chapterIndex >= target })
            }.mapIndexed { i, c -> c.copy(chapterIndex = i + 1) }
            reordered.forEach { Repo.dao.updateChapter(it) }
            ToolResult(true, "已移动", "第 $fromIdx 章《${moved.title.ifBlank { "未命名" }}》已移动到第 $target 章")
        }
    }

    /** 复制第 idx 章为新的一章（排在末尾） */
    suspend fun copyChapter(pid: Long, idx: Int): ToolResult {
        return withContext(Dispatchers.IO) {
            val chs = Repo.dao.chapters(pid)
            val src = chs.firstOrNull { it.chapterIndex == idx }
                ?: return@withContext ToolResult(false, "第 $idx 章不存在")
            val newIdx = (chs.maxOfOrNull { it.chapterIndex } ?: 0) + 1
            val nid = Repo.dao.insertChapter(
                src.copy(id = 0, chapterIndex = newIdx, title = (src.title.ifBlank { "未命名" }) + "（副本）", summary = "")
            )
            ToolResult(true, "已复制", "第 $idx 章 → 新的第 $newIdx 章（id=$nid）")
        }
    }

    // ---------- 统一分发器（供 AI 工具协议 / IntentRouter 调用） ----------

    /**
     * AI 工具协议的分发入口。
     * name/args 来自 AI 回复中的 <tool>{"name":..,"args":{..}}</tool> 块。
     * 返回 null 表示未知工具名。
     */
    suspend fun dispatch(pid: Long, name: String, args: org.json.JSONObject, context: Context?): ToolResult? = try {
        when (name) {
            "createProject" -> createProject(
                args.optString("title"), args.optString("genre"), args.optString("desc"),
                args.optInt("totalCh", 300), args.optInt("chWords", 2500)
            )
            "addCard" -> addCard(
                pid, args.optString("category"), args.optString("name"), args.optString("content"),
                if (args.has("priority")) args.optInt("priority") else null
            )
            "deleteCard" -> deleteCard(pid, args.optLong("cardId"))
            "writeNextChapter" -> writeNextChapter(pid)
            "rewriteChapter" -> rewriteChapter(pid, args.optInt("index"))
            "startAutoWrite" -> startAutoWrite(pid, args.optInt("from", 1), args.optInt("to", 300))
            "stopAutoWrite" -> stopAutoWrite()
            "generateOutlines" -> generateOutlines(pid)
            "inspireFromText" -> inspireFromText(pid, args.optString("inspiration"))
            "readChapter" -> readChapter(pid, args.optInt("index"))
            "listCards" -> listCards(pid, args.optString("category").ifBlank { null })
            "listChapters" -> listChapters(pid, args.optBoolean("onlyMissing", false))
            "exportTxt" -> exportTxt(pid, context)
            "deleteProject" -> deleteProject(pid)
            "contextPreview" -> contextPreview(pid)
            "moveChapter" -> moveChapter(pid, args.optInt("from"), args.optInt("to"))
            "copyChapter" -> copyChapter(pid, args.optInt("index"))
            else -> null
        }
    } catch (e: Exception) {
        ToolResult(false, "工具执行失败：${e.message?.take(200)}")
    }
}
