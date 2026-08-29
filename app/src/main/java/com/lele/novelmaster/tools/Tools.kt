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

    /** v5.4：回显保存内容 —— 超过 max 字折叠，但保证用户能在聊天里看到实际存了什么 */
    fun preview(s: String, max: Int = 3000): String =
        if (s.length > max) s.take(max) + "\n…（全文共 ${s.length} 字，已完整保存，可到「项目文件」查看）" else s

    // ---------- 项目 / 设定卡 ----------

    suspend fun listProjects(): ToolResult {
        val snap: List<Project> = withContext(Dispatchers.IO) { Repo.dao.projectsFlow().first() }
        if (snap.isEmpty()) return ToolResult(false, "还没有任何小说项目。")
        val text = snap.withIndex().joinToString("\n") { (i, p) ->
            "${i + 1}. 《${p.title}》 (ID=${p.id}, ${if (p.genre.isBlank()) "未分类" else p.genre}, 目标${p.targetChapters}章)"
        }
        return ToolResult(true, "你共有 ${snap.size} 本小说：", text)
    }

    /**
     * 建会话（= 一本书）。
     * v5.4 防串台：同名会话默认直接复用（force=false），不再重复创建；
     * 手动「新建会话」时传 force=true 才允许同名新建。
     */
    suspend fun createProject(
        title: String, genre: String, desc: String,
        totalCh: Int, chWords: Int, force: Boolean = false, context: Context? = null
    ): ToolResult {
        val t = title.trim().ifBlank { "未命名小说" }
        var reused = false
        val pid = withContext(Dispatchers.IO) {
            if (!force) {
                val exist = Repo.dao.projectsFlow().first()
                    .firstOrNull { it.title.trim().equals(t, ignoreCase = true) }
                if (exist != null) {
                    reused = true
                    return@withContext exist.id
                }
            }
            val p = Project(
                title = t,
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
        if (context != null) runCatching { FileTools.baseDir(context, pid).mkdirs() }
        return if (reused) {
            ToolResult(true, "已复用同名会话《$t》", "该会话已存在（ID=$pid），没有重复创建。一个会话就是一本小说。", newProjectId = pid)
        } else {
            ToolResult(true, "已创建会话《$t》", "会话 ID = $pid · 已生成 ${totalCh.coerceAtLeast(1)} 章骨架\n本会话全部资料独立保存在 novels/$pid/files/ 下。", newProjectId = pid)
        }
    }

    suspend fun switchProject(pid: Long): ToolResult {
        val p = withContext(Dispatchers.IO) { Repo.dao.project(pid) } ?: return ToolResult(false, "找不到该 ID 的项目")
        return ToolResult(true, "已切换到会话《${p.title}》", "目标 ${p.targetChapters} 章 / 每章 ${p.chapterWordTarget} 字 · 本会话资料独立", newProjectId = pid)
    }

    /** v5.5：修改当前会话的书名/类型/简介/目标章数/每章字数（不切换会话） */
    suspend fun updateProject(
        pid: Long,
        title: String? = null,
        genre: String? = null,
        desc: String? = null,
        totalCh: Int? = null,
        chWords: Int? = null
    ): ToolResult {
        val p = withContext(Dispatchers.IO) { Repo.dao.project(pid) } ?: return ToolResult(false, "项目不存在")
        val oldChapters = withContext(Dispatchers.IO) { Repo.dao.chapters(pid) }
        var needChapters = false
        val newTotal = totalCh?.coerceIn(1, 600)
        val newProject = p.copy(
            title = title?.trim()?.ifBlank { null } ?: p.title,
            genre = genre?.trim() ?: p.genre,
            description = desc ?: p.description,
            targetChapters = newTotal ?: p.targetChapters,
            chapterWordTarget = chWords?.coerceAtLeast(500) ?: p.chapterWordTarget
        )
        if (newTotal != null && newTotal != oldChapters.size) {
            needChapters = true
        }
        withContext(Dispatchers.IO) {
            Repo.dao.updateProject(newProject)
            if (needChapters) {
                // 补齐缺失的章节骨架；不删除已有章节
                val maxIdx = oldChapters.maxOfOrNull { it.chapterIndex } ?: 0
                val nt = newTotal
                if (nt != null && nt > maxIdx) {
                    val add = ((maxIdx + 1)..nt).map { Chapter(projectId = pid, chapterIndex = it) }
                    Repo.dao.insertChapters(add)
                }
            }
        }
        val changed = buildList {
            if (title != null) add("书名→《${newProject.title}》")
            if (genre != null) add("类型→${newProject.genre.ifBlank { "未分类" }}")
            if (desc != null) add("简介已更新")
            if (newTotal != null) add("目标章数→${newProject.targetChapters}")
            if (chWords != null) add("每章字数→${newProject.chapterWordTarget}")
        }.joinToString("、")
        return ToolResult(true, "✅ $changed（仍是《${newProject.title}》，继续在本会话工作）", "")
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

    suspend fun addCard(pid: Long, category: String, name: String, content: String, priority: Int? = null, context: Context? = null): ToolResult {
        if (category !in CardCategories.all) return ToolResult(false, "未知分类「$category」", "可选：" + CardCategories.all.joinToString("、"))
        if (name.isBlank() || content.isBlank()) return ToolResult(false, "名称和内容不能为空")
        val prio = priority ?: when (category) {
            "设定圣经", "世界观", "主线剧情", "核心冲突", "剧情进度" -> 2
            "伏笔钩子" -> 1
            else -> 1
        }
        val status = if (category == "伏笔钩子") "埋设中" else ""
        val id = withContext(Dispatchers.IO) {
            // v6.2：单卡分类（世界观/主线/冲突/圣经/全书大纲/剧情进度）一类只允许一张——
            // AI 换个名字再存也不会堆出重复卡，只会更新该类唯一一张
            val singleCats = setOf("世界观", "主线剧情", "核心冲突", "设定圣经", "全书大纲", "剧情进度")
            val exist = Repo.dao.findCard(pid, category, name)
                ?: if (category in singleCats) Repo.dao.cards(pid).firstOrNull { it.category == category } else null
            if (exist != null) {
                Repo.dao.updateCard(exist.copy(name = name, content = content, priority = prio, status = status))
                exist.id
            } else {
                Repo.dao.insertCard(
                    SettingCard(projectId = pid, category = category, name = name, content = content, priority = prio, status = status)
                )
            }
        }
        // 同步落盘 files/设定卡/{分类}/{名称}.md
        if (context != null) {
            try {
                val d = com.lele.novelmaster.tools.FileTools.baseDir(context, pid).resolve("设定卡/$category")
                d.mkdirs()
                java.io.File(d, name.replace(Regex("[\\/:*?\"<>|]"), "_").take(40) + ".md")
                    .writeText("# $category · $name\n\n$content\n", Charsets.UTF_8)
            } catch (_: Exception) { }
        }
        return ToolResult(
            true,
            "✅ 已存入设定卡：$category / $name（${content.length} 字 · id=$id）",
            "📄 设定卡/$category/$name.md\n\n" + preview(content) +
                "\n\n——以上内容已完整保存，并同步写入本会话项目文件夹。"
        )
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

    suspend fun writeNextChapter(pid: Long, context: Context? = null): ToolResult {
        val cfg = withContext(Dispatchers.IO) { Repo.dao.activeApi() } ?: return ToolResult(false, "请先在【AI模型】中添加并启用一个模型")
        val chs = withContext(Dispatchers.IO) { Repo.dao.chapters(pid) }
        val project = withContext(Dispatchers.IO) { Repo.dao.project(pid) } ?: return ToolResult(false, "项目不存在")
        val next = chs.firstOrNull { it.content.isBlank() } ?: return ToolResult(false, "全部章节都已写完 ✅")
        return try {
            // v6.3：写章前先确保分章大纲（标题+剧情要点）已生成——
            // 之前 AI 常常跳过 generateOutlines 直接开写，导致章节全是「未命名」且注入里没有大纲核心
            val noOutline = chs.count { it.outline.isBlank() }
            if (next.outline.isBlank()) {
                withContext(Dispatchers.IO) {
                    Repo.dao.insertMessage(
                        Message(projectId = pid, role = "tool", kind = "tool",
                            content = "🧭 检测到 $noOutline 章还没有大纲，正在自动补全分章大纲（标题+剧情要点）…")
                    )
                }
                WriterEngine.ensureOutlines(pid, context = context)
            }
            WriterEngine.writeOne(project, cfg, Repo.dao, next, context)
            val fresh = withContext(Dispatchers.IO) { Repo.dao.chapter(next.id) }!!
            ToolResult(true, "✅ 第${fresh.chapterIndex}章《${fresh.title}》已写入（${fresh.wordCount} 字）",
                "完整正文已在上方聊天里实时显示，并已保存到章节库和正文文件，不会重复输出。\n摘要：${fresh.summary.take(120)}",
                navigateTo = "editor/${fresh.id}")
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

    suspend fun startAutoWrite(pid: Long, from: Int, to: Int, context: Context? = null): ToolResult {
        // 自定义范围 1~600；实际章节不足时按实际章节遍历，写完自动停
        val f = from.coerceIn(1, 600)
        val t = to.coerceIn(f, 600)
        AutoWriteManager.start(pid, f, t, context)
        return ToolResult(true, "🚀 自动写作已开始", "范围：第 $f ~ $t 章（实际章节不足时写到最后一章自动停）。进度在聊天里实时播报，随时说「停止写作」中止。", navigateTo = "autowrite/$pid")
    }

    suspend fun stopAutoWrite(): ToolResult { AutoWriteManager.stop(); return ToolResult(true, "已请求停止自动写作") }

    suspend fun generateOutlines(pid: Long, context: Context? = null): ToolResult {
        return withContext(Dispatchers.IO) {
            val err = WriterEngine.ensureOutlines(pid, context = context)
            if (err != null) ToolResult(false, err) else ToolResult(true, "已补齐缺失的分章大纲")
        }
    }

    suspend fun inspireFromText(pid: Long, inspiration: String, context: Context? = null): ToolResult {
        if (inspiration.isBlank()) return ToolResult(false, "请把你的灵感 / 需求发给我")
        return withContext(Dispatchers.IO) {
            val err = WriterEngine.generateCardsFromInspire(pid, inspiration, context)
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
            appendLine("【上一章结尾】${if (prev != null && prev.content.isNotBlank()) "${prev.content.takeLast(300).length} 字" else "无"}")
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
        val ctx = context ?: return ToolResult(false, "导出需要文件系统支持，请重启APP后重试")
        val outDir = File(FileTools.baseDir(ctx, pid), "导出").apply { mkdirs() }
        val outFile = File(outDir, name)
        outFile.writeText(body, Charsets.UTF_8)
        return ToolResult(true, "已导出 ${chs.size} 章到本会话文件夹", "路径：novels/$pid/files/导出/$name\n可在「项目文件」或系统文件管理里查看分享。")
    }

    // ---------- 专家级写作功能（10 项） ----------

    /** 定位目标章：显式 idx，或 -1 表示最新已写章 */
    private suspend fun locate(pid: Long, idx: Int): Pair<Int, com.lele.novelmaster.data.Chapter?> {
        val chs = Repo.dao.chapters(pid)
        val target = if (idx > 0) chs.firstOrNull { it.chapterIndex == idx }
        else chs.lastOrNull { it.content.isNotBlank() }
        val n = target?.chapterIndex ?: -1
        return n to target
    }

    /** 1. 章节润色：提升文笔，不动剧情 */
    suspend fun polishChapter(pid: Long, idx: Int): ToolResult {
        val (n, ch) = locate(pid, idx)
        if (ch == null || ch.content.isBlank()) return ToolResult(false, "没有可润色的章节")
        val (err, _) = WriterEngine.chapterTask(
            pid, n,
            "请对以下正文进行润色：提升文笔与画面感、优化节奏、修正病句与重复用词，剧情、对白含义、人物性格一律不变，字数与原章相近。只输出润色后的完整正文。",
            replace = true
        )
        return if (err == null) ToolResult(true, "✅ 第${n}章润色完成", "文笔已升级，剧情未变。可在章节列表查看。") else ToolResult(false, err)
    }

    /** 2. 对话扩写：把叙述改造成生动对话场景 */
    suspend fun expandDialogue(pid: Long, idx: Int): ToolResult {
        val (n, ch) = locate(pid, idx)
        if (ch == null || ch.content.isBlank()) return ToolResult(false, "没有可扩写的章节")
        val (err, _) = WriterEngine.chapterTask(
            pid, n,
            "把本章中过于依赖叙述的部分改写为生动的对话与动作场景：人物语气符合各自设定，加入微表情与小动作，剧情走向不变，字数增加30%~50%。只输出改写后的完整正文。",
            replace = true
        )
        return if (err == null) ToolResult(true, "✅ 第${n}章对话扩写完成", "叙述已改造成对话场景，字数增加。") else ToolResult(false, err)
    }

    /** 3. 风格改写：模仿指定作家风格 */
    suspend fun styleRewrite(pid: Long, idx: Int, style: String): ToolResult {
        if (style.isBlank()) return ToolResult(false, "请说明想要的风格，如：模仿烽火戏诸侯/金庸/马尔克斯")
        val (n, ch) = locate(pid, idx)
        if (ch == null || ch.content.isBlank()) return ToolResult(false, "没有可改写的章节")
        val (err, _) = WriterEngine.chapterTask(
            pid, n,
            "用「$style」的文风重写本章：吸收其句式节奏、比喻方式与叙事腔调，但剧情、人物、世界观必须完全保持本文设定。只输出重写后的完整正文。",
            replace = true
        )
        return if (err == null) ToolResult(true, "✅ 第${n}章已按「$style」风格重写") else ToolResult(false, err)
    }

    /** 4. 章末钩子强化 */
    suspend fun hookChapter(pid: Long, idx: Int): ToolResult {
        val (n, ch) = locate(pid, idx)
        if (ch == null || ch.content.isBlank()) return ToolResult(false, "没有可处理的章节")
        val (err, _) = WriterEngine.chapterTask(
            pid, n,
            "保持本章前文不变，仅重写最后约500字的结尾：制造强烈悬念钩子（危机/反转/秘密揭露/意外来客任选最合适的一种），让读者迫切想看下一章。输出完整正文（前文原样+新结尾）。",
            replace = true
        )
        return if (err == null) ToolResult(true, "✅ 第${n}章结尾钩子已强化", "章末悬念已重写。") else ToolResult(false, err)
    }

    /** 5. 金句生成 */
    suspend fun goldenLines(pid: Long, idx: Int): ToolResult {
        val (n, ch) = locate(pid, idx)
        if (ch == null || ch.content.isBlank()) return ToolResult(false, "请先写完一章再生成金句")
        val (err, out) = WriterEngine.chapterTask(
            pid, n,
            "基于本章剧情与人物，写出 6 句适合本章的金句/名场面台词（每句一行，不要编号不要解释）：可以是人物台词，也可以是叙述性金句，要有传播力。",
            replace = false
        )
        return if (err == null) ToolResult(true, "第${n}章金句（可直接用于正文或宣传）：", out) else ToolResult(false, err)
    }

    /** 6. 剧情推演：3 条走向方案 */
    suspend fun plotBrainstorm(pid: Long): ToolResult {
        val chs = Repo.dao.chapters(pid)
        val last = chs.lastOrNull { it.content.isNotBlank() } ?: return ToolResult(false, "还没有已写章节")
        val (err, out) = WriterEngine.chapterTask(
            pid, last.chapterIndex,
            "基于本章结尾，推演后续剧情：给出 3 条走向方案，每条含【方案名】、剧情概要（150字内）、张力来源、风险与机会。三方案风格差异要大（如：热血推进/深挖暗线/引入新势力）。只输出方案。",
            replace = false
        )
        return if (err == null) ToolResult(true, "后续剧情 3 条走向（选定后告诉我，我按方案推进并更新大纲）：", out) else ToolResult(false, err)
    }

    /** 7. 人物一致性检查 */
    suspend fun characterCheck(pid: Long, name: String): ToolResult {
        val cards = Repo.dao.cards(pid)
        val c = cards.firstOrNull { it.category == "人物设定" && it.name.contains(name) }
            ?: return ToolResult(false, "设定卡中找不到人物「$name」，可用 listCards 查看人物列表")
        val (err, out) = WriterEngine.freeTask(
            pid,
            "任务：人物一致性体检。\n人物设定：${c.name} —— ${c.content}\n请对照各章摘要检查该人物：1) 行为/称谓/能力是否与设定矛盾（列出具体章号）；2) 性格是否前后不一；3) 成长弧线是否成立；4) 给出 2~3 条修补建议。无问题也要明确说【通过】。"
        )
        return if (err == null) ToolResult(true, "人物「${c.name}」体检报告：", out) else ToolResult(false, err)
    }

    /** 8. 全书一致性体检（设定矛盾/时间线/伏笔遗漏/敏感提示） */
    suspend fun consistencyCheck(pid: Long): ToolResult {
        val chs = Repo.dao.chapters(pid)
        val written = chs.filter { it.content.isNotBlank() }
        if (written.isEmpty()) return ToolResult(false, "还没有已写章节")
        val sumBlock = written.joinToString("\n") { "第${it.chapterIndex}章《${it.title}》：${it.summary}" }
        val (err, out) = WriterEngine.freeTask(
            pid,
            "任务：全书一致性体检。各章摘要如下：\n$sumBlock\n\n请检查：1) 设定矛盾（能力/称谓/人数/物品）；2) 时间线错误；3) 未回收或异常的伏笔；4) 平台敏感内容风险提示。逐条列出（注明章号）并给修复建议；没问题就明确说【通过】。"
        )
        return if (err == null) ToolResult(true, "全书体检报告（${written.size} 章已检查）：", out) else ToolResult(false, err)
    }

    /** 9. 起名器 */
    suspend fun nameGen(pid: Long, kind: String, count: Int): ToolResult {
        val (err, out) = WriterEngine.freeTask(
            pid,
            "任务：起名。为本小说生成 ${count.coerceIn(1, 20).coerceAtMost(20)} 个「${kind.ifBlank { "人物" } }」名字（人名/地名/功法/门派/法宝/势力等）。\n每行一个：名字 —— 一句话寓意/来源。要贴合本书世界观风格，避免烂大街。"
        )
        return if (err == null) ToolResult(true, "「${kind.ifBlank { "人物" }}」名字 $count 个：", out) else ToolResult(false, err)
    }

    /** 10. 简介+书名生成（发布用），并存入会话文件夹 */
    suspend fun genBlurb(pid: Long, context: Context?): ToolResult {
        val project = Repo.dao.project(pid) ?: return ToolResult(false, "项目不存在")
        val (err, out) = WriterEngine.freeTask(
            pid,
            "任务：为本书生成发布资料。\n1) 3 个备选书名（吸引点击，不撞款）\n2) 一段 200 字内的发布简介（前50字必须抓人，突出核心冲突与爽点，结尾留悬念）\n3) 5 个分类/标签关键词。\n只输出这三部分。"
        )
        if (err != null) return ToolResult(false, err)
        // 自动存到会话文件夹 + 设定卡
        val ctx = context ?: return ToolResult(true, "发布资料已生成", out)
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(FileTools.baseDir(ctx, pid), "发布资料").apply { mkdirs() }
                File(dir, "简介与书名.md").writeText(out, Charsets.UTF_8)
            }
            runCatching {
                val exist = Repo.dao.findCard(pid, "辅助设定", "发布简介")
                if (exist != null) Repo.dao.updateCard(exist.copy(content = out))
                else Repo.dao.insertCard(
                    com.lele.novelmaster.data.SettingCard(projectId = pid, category = "辅助设定", name = "发布简介", content = out, priority = 1)
                )
            }
            ToolResult(true, "发布资料已生成并存档", out + "\n\n（已存入「项目文件/发布资料/简介与书名.md」和辅助设定卡）")
        }
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
    suspend fun dispatch(pid: Long, name: String, args: org.json.JSONObject, context: Context?): ToolResult? {
        // 文件系统工具优先（AI 自由建文件夹/文件，会话隔离）
        if (context != null) {
            val fr = com.lele.novelmaster.tools.FileTools.dispatch(context, pid, name, args)
            if (fr != null) return fr
        }
        return try {
            when (name) {
            "createProject" -> createProject(
                args.optString("title"), args.optString("genre"), args.optString("desc"),
                args.optInt("totalCh", 300), args.optInt("chWords", 1800),
                args.optBoolean("force", false), context
            )
            "listProjects" -> listProjects()
            "switchProject" -> switchProject(args.optLong("pid", args.optLong("projectId", pid)))
            "updateProject" -> updateProject(
                pid,
                args.optString("title").takeIf { it.isNotBlank() },
                args.optString("genre").takeIf { it.isNotBlank() },
                args.optString("desc").takeIf { it.isNotBlank() },
                args.optInt("totalCh", -1).takeIf { it > 0 },
                args.optInt("chWords", -1).takeIf { it > 0 }
            )
            "addCard" -> addCard(
                pid, args.optString("category"), args.optString("name"), args.optString("content"),
                if (args.has("priority")) args.optInt("priority") else null, context
            )
            "deleteCard" -> deleteCard(pid, args.optLong("cardId"))
            "writeNextChapter" -> writeNextChapter(pid, context)
            "rewriteChapter" -> rewriteChapter(pid, args.optInt("index"))
            "startAutoWrite" -> startAutoWrite(pid, args.optInt("from", 1), args.optInt("to", 300), context)
            "stopAutoWrite" -> stopAutoWrite()
            "generateOutlines" -> generateOutlines(pid, context)
            "inspireFromText" -> inspireFromText(pid, args.optString("inspiration"), context)
            "readChapter" -> readChapter(pid, args.optInt("index"))
            "listCards" -> listCards(pid, args.optString("category").ifBlank { null })
            "listChapters" -> listChapters(pid, args.optBoolean("onlyMissing", false))
            "exportTxt" -> exportTxt(pid, context)
            "deleteProject" -> deleteProject(pid)
            "contextPreview" -> contextPreview(pid)
            // 专家级写作功能
            "polishChapter" -> polishChapter(pid, args.optInt("index", -1))
            "expandDialogue" -> expandDialogue(pid, args.optInt("index", -1))
            "styleRewrite" -> styleRewrite(pid, args.optInt("index", -1), args.optString("style"))
            "hookChapter" -> hookChapter(pid, args.optInt("index", -1))
            "goldenLines" -> goldenLines(pid, args.optInt("index", -1))
            "plotBrainstorm" -> plotBrainstorm(pid)
            "characterCheck" -> characterCheck(pid, args.optString("name"))
            "consistencyCheck" -> consistencyCheck(pid)
            "nameGen" -> nameGen(pid, args.optString("kind", "人物"), args.optInt("count", 8))
            "genBlurb" -> genBlurb(pid, context)
            "moveChapter" -> moveChapter(pid, args.optInt("from"), args.optInt("to"))
            "copyChapter" -> copyChapter(pid, args.optInt("index"))
            else -> null
        }
    } catch (e: Exception) {
        ToolResult(false, "工具执行失败：${e.message?.take(200)}")
    }
    }
}
