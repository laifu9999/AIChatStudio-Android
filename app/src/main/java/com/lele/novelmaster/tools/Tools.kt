package com.lele.novelmaster.tools

import android.content.Context
import com.lele.novelmaster.data.AutoWriteManager
import com.lele.novelmaster.data.CardCategories
import com.lele.novelmaster.data.Chapter
import com.lele.novelmaster.data.Project
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.data.Message
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
        // v6.9.6：「写作禁忌」由写后自检系统维护，AI 不得创建/覆盖
        if (name == "写作禁忌") return ToolResult(false, "「写作禁忌」由系统自检自动维护，无需手动创建")
        if (name == "分章大纲" && category == "全书大纲") return ToolResult(false, "「分章大纲」卡由系统自动同步维护，无需手动创建")
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
                // v6.7：全书大纲类的回退匹配排除 分章大纲 系统卡，防止覆盖
                ?: if (category in singleCats) Repo.dao.cards(pid).firstOrNull {
                    it.category == category && it.name !in com.lele.novelmaster.data.WriterEngine.OUTLINE_CARD_NAMES
                } else null
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
        // v6.9.15：硬约束设定（人物/世界观/圣经）变更而项目已有正文时，主动提醒全书自检
        var detail = "已完整保存到 设定卡/$category/$name.md，写正文时自动注入；聊天里不再重复显示全文，想看随时到设定卡页查看。"
        if (category in setOf("人物设定", "世界观", "设定圣经")) {
            val hasBody = Repo.dao.chapters(pid).any { it.content.isNotBlank() }
            if (hasBody) detail += "\n⚠️ 该设定已变更，此前写的章节基于旧设定，建议说「全书自检」让系统逐章校验并自动修正冲突。"
        }
        return ToolResult(
            true,
            "✅ 已保存设定卡：$category / $name（${content.length} 字）",
            detail
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
        val cfg = withContext(Dispatchers.IO) { Repo.apiFor(pid) } ?: return ToolResult(false, "请先在【AI模型】中添加并启用一个模型")
        val chs = withContext(Dispatchers.IO) { Repo.dao.chapters(pid) }
        val project = withContext(Dispatchers.IO) { Repo.dao.project(pid) } ?: return ToolResult(false, "项目不存在")
        val next = chs.firstOrNull { it.content.isBlank() } ?: return ToolResult(false, "全部章节都已写完 ✅")
        // v6.4：硬门槛——设定卡八类全部建全 + 分章大纲卡就绪才允许写正文；
        // 缺什么自动先补什么（聊天里实时播报），补不齐绝不放行，杜绝"没建完设定就开始写第一章"
        val gateErr = WriterEngine.ensurePreconditions(pid, context)
        if (gateErr != null) return ToolResult(false, gateErr)
        return try {
            // 补全过程改过数据库，重新取最新章节（标题/大纲已就位）
            val fresh = withContext(Dispatchers.IO) { Repo.dao.chapters(pid) }
                .firstOrNull { it.id == next.id } ?: next
            WriterEngine.writeOne(project, cfg, Repo.dao, fresh, context)
            val done = withContext(Dispatchers.IO) { Repo.dao.chapter(fresh.id) }!!
            ToolResult(true, "✅ 第${done.chapterIndex}章《${done.title}》已写入（${done.wordCount} 字）",
                "完整正文已在上方聊天里实时显示，并已保存到章节库和正文文件，不会重复输出。\n摘要：${done.summary.take(120)}",
                navigateTo = "editor/${done.id}")
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
        // v6.4：自动写作同样过硬门槛——设定卡+分章大纲不齐全，先自动补全再开写
        val gateErr = WriterEngine.ensurePreconditions(pid, context)
        if (gateErr != null) return ToolResult(false, gateErr)
        // 自定义范围 1~600；实际章节不足时按实际章节遍历，写完自动停
        val f = from.coerceIn(1, 600)
        val t = to.coerceIn(f, 600)
        AutoWriteManager.start(pid, f, t, context)
        return ToolResult(true, "🚀 自动写作已开始", "范围：第 $f ~ $t 章（实际章节不足时写到最后一章自动停）。进度在聊天里实时播报，随时说「停止写作」中止。", navigateTo = "autowrite/$pid")
    }

    suspend fun stopAutoWrite(pid: Long? = null): ToolResult {
        // v6.9.34 多书并行：只停当前这本书的任务（其他书不受影响）；当前书没在写时才停全部
        return when {
            pid != null && AutoWriteManager.isRunning(pid) -> {
                AutoWriteManager.stop(pid)
                ToolResult(true, "已请求停止本书的自动写作（其他并行任务不受影响）")
            }
            AutoWriteManager.anyRunning() -> {
                AutoWriteManager.stop()
                ToolResult(true, "已请求停止全部自动写作")
            }
            else -> ToolResult(true, "当前没有进行中的自动写作")
        }
    }

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
        // v6.9.40：删除前停掉该书关联任务（自动写作/页面长任务），防书没了任务还继续烧 token。
        // 注意不能停 ChatEngine——聊天里执行本指令时 ChatEngine 正 busy 在本书上，停掉会把删除指令自己掐断
        AutoWriteManager.stop(pid)
        com.lele.novelmaster.engine.AppTasks.cancelProject(pid)
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
        // v6.9.41：与实际注入同源——摘要章数可配（默认0=不注入），相邻窗口前N章可配
        val appCtx = Repo.app
        val sumN = if (appCtx != null) com.lele.novelmaster.data.InjectPrefs.summaryCount(appCtx) else 0
        val winPrev = if (appCtx != null) com.lele.novelmaster.data.InjectPrefs.windowPrev(appCtx) else 2
        val recent = chapters.filter { it.chapterIndex < next.chapterIndex && it.summary.isNotBlank() }.takeLast(sumN)
        val prev = chapters.firstOrNull { it.chapterIndex == next.chapterIndex - 1 }
        val neighbors = chapters
            .filter { it.chapterIndex in (next.chapterIndex - winPrev)..(next.chapterIndex + 1) && it.outline.isNotBlank() }
            .joinToString("\n") { "第${it.chapterIndex}章《${it.title}》:${it.outline.take(60)}" }

        val chars = com.lele.novelmaster.data.Prompts.buildChapterMessages(project, cards, chapters, next, sumN, winPrev)
            .sumOf { it.content.length }
        val detail = buildString {
            appendLine("▶ 目标：第 ${next.chapterIndex} 章《${next.title.ifBlank { "未命名" }}》")
            appendLine()
            appendLine("【必发设定卡 + 未回收伏笔 + 相关卡】${selected.size} 张")
            selected.forEach { appendLine("  • ${it.category}/${it.name}（${it.content.length}字）") }
            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("【前情摘要】${recent.size} 条")
                recent.forEach { appendLine("  • 第${it.chapterIndex}章：${it.summary.take(50)}…") }
            }
            appendLine()
            appendLine("【上一章结尾】${if (prev != null && prev.content.isNotBlank()) "${prev.content.takeLast(300).length} 字" else "无"}")
            appendLine()
            appendLine("【相邻章节大纲（前${winPrev}章+本章+后一章）】")
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

    /** v6.9.35 发布打磨：去AI味+开头入戏+节奏+对话潜台词+章末钩子，一键达到可发布水平（替换前自动备份） */
    suspend fun publishPolish(pid: Long, idx: Int): ToolResult {
        val (n, ch) = locate(pid, idx)
        if (ch == null || ch.content.isBlank()) return ToolResult(false, "没有可打磨的章节")
        val (err, _) = WriterEngine.chapterTask(
            pid, n,
            "发布级深度打磨（这是发布前的最后一道工序，目标是让读者看不出AI痕迹）：\n" +
                "1) 去AI味：删除任何总结本章的段落；删掉「仿佛/似乎/宛如」式堆砌比喻；清除「眸中闪过一丝X」「嘴角勾起一抹弧度」「空气仿佛凝固」「不知道过了多久」等套路句；不滥用成语和四字排比；\n" +
                "2) 开头直接入戏：前100字必须进入场景或冲突，删掉铺垫式开头；\n" +
                "3) 节奏：每300~500字要有一个新信息点或小转折，删除拖沓的过渡句和重复交代；\n" +
                "4) 对话至少占三成，对话要有潜台词和信息差，能推动剧情；全知解说（他知道/他明白/殊不知）改为用动作细节流露；\n" +
                "5) 章末钩子必须是具体事件（危机/反转/来客/秘密/决定），删掉空泛的情绪渲染式结尾；\n" +
                "6) 铁律：剧情走向、人物性格、设定事实、伏笔一律不变，字数与原章相近。\n" +
                "只输出打磨后的完整正文。",
            replace = true,
            task = com.lele.novelmaster.data.TaskModels.POLISH // v6.9.41：打磨可走专用模型
        )
        return if (err == null)
            ToolResult(true, "🚀 第${n}章发布打磨完成", "去AI味/开头/节奏/钩子已按发布标准处理，剧情未变。原文已自动备份，可直接导出发布。")
        else ToolResult(false, err)
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

    /** 4.5 补写第N章缺失剧情：把本章大纲要求但遗漏的关键事件自然融入正文，不必重写整章（v6.9.14） */
    suspend fun supplementChapter(pid: Long, idx: Int): ToolResult {
        val (n, ch) = locate(pid, idx)
        if (ch == null || ch.content.isBlank()) return ToolResult(false, "没有可补写的章节")
        if (ch.outline.isBlank()) return ToolResult(false, "第${n}章还没有分章大纲，无法确定应补写哪些剧情。可先说「生成分章大纲」。")
        val (err, _) = WriterEngine.chapterTask(
            pid, n,
            "本章正文可能遗漏了大纲要求的部分关键剧情。请对照大纲与现有正文补写：\n" +
                "【本章大纲】${ch.outline.take(300)}\n" +
                "要求：把大纲中要求但正文里没有的关键事件自然融入（在合适位置插入段落或重写衔接段，保持原文风格，与已有情节不冲突）；" +
                "正文里已经写对的部分原样保留，不要重写全文。输出补写后的完整正文。",
            replace = true
        )
        return if (err == null) ToolResult(true, "✅ 第${n}章缺失剧情已补写", "正文已更新：原版已自动备份（说「撤销第${n}章自检修改」可恢复），随后自动跑了写后自检。") else ToolResult(false, err)
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
            "任务：全书一致性体检。各章摘要如下：\n$sumBlock\n\n请检查：1) 设定矛盾（能力/称谓/人数/物品）；2) 时间线错误；3) 未回收或异常的伏笔；4) 平台敏感内容风险提示。逐条列出（注明章号）并给修复建议；没问题就明确说【通过】。\n（本体检只列问题不修改；如需自动修正，可说「全书自检」）"
        )
        val tip = if (err == null) "\n\n💡 本报告只诊断不修改。想让系统逐章自动修正矛盾，请说「全书自检」。" else ""
        return if (err == null) ToolResult(true, "全书体检报告（${written.size} 章已检查）：", out + tip) else ToolResult(false, err)
    }

    /** 8.0b 伏笔体检：对照伏笔钩子卡与各章摘要，评估埋设/回收状态并给回收建议（只分析不修改） */
    suspend fun foreshadowCheck(pid: Long): ToolResult {
        val dao = Repo.dao
        val hooks = dao.cards(pid).filter { it.category == "伏笔钩子" }
        if (hooks.isEmpty()) return ToolResult(false, "这本书还没有「伏笔钩子」卡。写正文时 AI 会自动记录伏笔，也可在设定卡里手动添加。")
        val open = hooks.filter { it.status != "已回收" }
        val done = hooks.filter { it.status == "已回收" }
        val chapters = dao.chapters(pid).filter { it.content.isNotBlank() }.sortedBy { it.chapterIndex }
        val summaryBlock = chapters.takeLast(30)
            .joinToString("\n") { "第${it.chapterIndex}章《${it.title}》：${it.summary.take(60)}" }
        val (err, out) = WriterEngine.freeTask(
            pid,
            "任务：伏笔体检（只分析，不修改任何卡与正文）。\n" +
                "【伏笔清单】\n" +
                (if (open.isNotEmpty()) "未回收：\n" + open.joinToString("\n") { "· ${it.name}：${it.content.take(80)}" } + "\n" else "") +
                (if (done.isNotEmpty()) "已回收：\n" + done.joinToString("\n") { "· ${it.name}" } + "\n" else "") +
                "\n【最近章节摘要】\n$summaryBlock\n\n" +
                "请输出：\n" +
                "1) 每条未回收伏笔一行：评估状态——「已过很久未回收（剧情已推进较远，读者可能遗忘）」/「仍在合理埋设期」/「摘要显示其实已回收但未标记」；\n" +
                "2) 对应尽快回收的伏笔，给出建议的回收时机与方式（各一句话）；\n" +
                "3) 已回收伏笔若与最近摘要明显矛盾也要指出。\n" +
                "按紧急度从高到低排序，不要解释格式。"
        )
        return if (err == null) ToolResult(true, "🪝 伏笔体检（未回收 ${open.size} 条 / 已回收 ${done.size} 条）：", out) else ToolResult(false, err)
    }

    /** 8.0c 标记伏笔已回收：伏笔体检发现「已回收但未标记」时的人工确认入口（v6.9.16） */
    suspend fun markHookRecovered(pid: Long, name: String): ToolResult {
        if (name.isBlank()) return ToolResult(false, "请说明要标记哪条伏笔，如：标记伏笔「神秘令牌」已回收")
        val dao = Repo.dao
        val hooks = dao.cards(pid).filter { it.category == "伏笔钩子" }
        val target = hooks.firstOrNull { it.name == name }
            ?: hooks.firstOrNull { it.name.contains(name) || name.contains(it.name) }
            ?: return ToolResult(false, "找不到伏笔「$name」。可用「伏笔体检」或设定卡页查看现有伏笔清单。")
        if (target.status == "已回收") return ToolResult(false, "伏笔「${target.name}」已经是已回收状态")
        dao.updateCard(target.copy(status = "已回收", updatedAt = System.currentTimeMillis()))
        return ToolResult(true, "🪝 已标记伏笔「${target.name}」为已回收", "该伏笔不再出现在「未回收」清单中。若标错了，可在设定卡页改回「埋设中」。")
    }

    /** 8.0d 支线任务体检：对照支线任务卡与各章摘要，评估推进状态并给收束建议（只分析不修改，v6.9.18） */
    suspend fun subplotCheck(pid: Long): ToolResult {
        val dao = Repo.dao
        val subs = dao.cards(pid).filter { it.category == "支线任务" }
        if (subs.isEmpty()) return ToolResult(false, "这本书还没有「支线任务」卡。可在设定卡里添加支线（名称+一句话目标），或聊天里让 AI 创建。")
        val chapters = dao.chapters(pid).filter { it.content.isNotBlank() }.sortedBy { it.chapterIndex }
        val summaryBlock = chapters.takeLast(30)
            .joinToString("\n") { "第${it.chapterIndex}章《${it.title}》：${it.summary.take(60)}" }
        val (err, out) = WriterEngine.freeTask(
            pid,
            "任务：支线任务体检（只分析，不修改任何卡与正文）。\n" +
                "【支线任务清单】\n" + subs.joinToString("\n") { "· ${it.name}：${it.content.take(80)}" } + "\n\n" +
                "【最近章节摘要】\n$summaryBlock\n\n" +
                "请输出：\n" +
                "1) 每条支线一行：评估状态——「活跃推进中」/「多章未推进，可能被读者遗忘」/「摘要显示实际已完成，可正式收束」/「与主线冲突或价值存疑，建议砍掉」；\n" +
                "2) 对停滞或该收束的支线，给出建议（何时推进/如何收束/如何并入主线，各一句话）；\n" +
                "按紧迫度从高到低排序，不要解释格式。"
        )
        return if (err == null) ToolResult(true, "🧵 支线任务体检（共 ${subs.size} 条支线）：", out) else ToolResult(false, err)
    }

    /** 8.0e 设定体检：检查全部设定卡与分章大纲是否自洽合理，能改文字解决的矛盾自动修复卡片（v6.9.22） */
    // v6.9.39：设定体检并发闸门——聊天指令与设定卡页「设定体检」双入口互斥，防双跑烧 token、报告互相覆盖
    private val cardsCheckGate = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun cardsCheck(pid: Long): ToolResult {
        if (!cardsCheckGate.compareAndSet(false, true)) return ToolResult(false, "设定体检正在进行中，请等当前体检完成")
        return try { cardsCheckInner(pid) } finally { cardsCheckGate.set(false) }
    }

    private suspend fun cardsCheckInner(pid: Long): ToolResult {
        val dao = Repo.dao
        val cards = dao.cards(pid)
        if (cards.isEmpty()) return ToolResult(false, "这本书还没有设定卡。可到设定卡页点右上角「灵感分析」自动生成。")
        // 控 token：普通卡每张截 160 字，分章大纲卡截 900 字
        val cardBlock = cards.joinToString("\n") {
            val cap = if (it.name == "分章大纲") 900 else 160
            "· [${it.category}] ${it.name}：" + it.content.replace(Regex("\\s+"), " ").take(cap)
        }
        val chs = dao.chapters(pid)
        val stat = "（已建${chs.size}章，其中${chs.count { it.outline.isNotBlank() }}章有大纲）"
        val (err, out) = WriterEngine.freeTask(
            pid,
                "任务：设定体检——检查所有设定卡与分章大纲是否自洽、合理、精炼。\n" +
                "【设定卡清单】\n$cardBlock\n\n【分章大纲】$stat\n\n" +
                "检查：1) 卡与卡矛盾（人物/世界观/主线/冲突/圣经互相冲突）；2) 设定与分章大纲矛盾（大纲走向违背设定或主线）；3) 事实性错误（同一设定前后说法不一）；" +
                "4) 冗余重复（多张卡写了同一件事——如主线剧情/核心冲突/全书大纲互相复述；或单卡啰嗦超长、塞满空话）。\n" +
                "重要：卡片「内容不够详细/单薄」不算问题——这类放进【建议】行即可，严禁为它们输出【修复】（注入截断导致你只看到卡片前160字，不代表卡片内容真的单薄）。\n" +
                "重要：只报硬伤（真矛盾/真错误/明显重复）。【问题】最多列 6 条，按严重程度从高到低排序；措辞差异、详略不同、风格偏好这类不算问题，严禁凑数罗列。\n" +
                "重要：全程只用简体中文输出，严禁英文，严禁输出思考过程/内心独白，直接按格式给结果。\n" +
                "输出格式严格（不要任何其他解释）：\n" +
                "【问题】每条一行：涉及卡名｜问题一句话（只列真实矛盾/错误/明显重复，没有就不输出此行）\n" +
                "【建议】每条一行：卡名｜一句话扩写方向（可选）\n" +
                "【修复】只对能直接改文字解决的真实矛盾（最多5张，严禁修复「剧情进度」和「分章大纲」卡——它们由系统自动维护），每条一行：卡名｜修正后的完整卡片内容\n" +
                "【精简】只对明显重复/啰嗦的卡去重压缩（最多8张，严禁精简「剧情进度」「分章大纲」「写作禁忌」卡），每条一行：卡名｜去重后的完整卡片内容（抓重点：每卡60~200字，只留对写作有用的干货，不新增不改设定事实）\n" +
                "整体没有问题就只输出【通过】。",
            task = com.lele.novelmaster.data.TaskModels.CHECK // v6.9.41：体检可走专用模型
        )
        if (err != null) return ToolResult(false, err)
        // v6.9.42：解析【修复】/【精简】行并应用——抽成共用函数，「设定体检」与「按报告一键修复」走同一条保存链路
        val ar = applyCheckLines(pid, cards, out)
        val head = "🧾 设定体检完成（${cards.size} 张卡$stat）"
        val fixNote = if (ar.fixedNames.isEmpty() && ar.slimmedNames.isEmpty())
            "\n\n未修改任何卡。若上面的【问题】确实需要改卡，点报告下方「确认修复」按钮，系统会按报告逐张改好并自动保存到项目文件夹。"
        else
            "\n\n✅ 已自动修复 ${ar.fixedNames.size} 张卡${if (ar.slimmedNames.isNotEmpty()) "、去重精简 ${ar.slimmedNames.size} 张卡（约省 ${ar.savedChars} 字）" else ""}：${(ar.fixedNames + ar.slimmedNames).joinToString("、")}（原内容已备份到 设定卡/备份/设定体检备份.md）"
        // v6.9.28：报告落盘存档（设定卡页弹窗路径不进聊天记录，落盘保证可回查）
        try {
            val appCtx = Repo.app
            if (appCtx != null) {
                val d = File(FileTools.baseDir(appCtx, pid), "设定卡/备份")
                d.mkdirs()
                val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
                File(d, "设定体检报告-$ts.md").writeText("# 设定体检报告 $ts\n\n$out$fixNote\n", Charsets.UTF_8)
            }
        } catch (_: Exception) { }
        return ToolResult(true, head, out + fixNote + "\n\n📄 报告已存档到 设定卡/备份/")
    }

    /** v6.9.42：体检修复行应用结果 */
    private data class CheckApplyResult(
        val fixedNames: List<String>,
        val slimmedNames: List<String>,
        val savedChars: Int
    )

    /** v6.9.42：解析【修复】/【精简】行并应用到卡——备份原卡 → 改库 → 写回项目文件夹 → 全卡兜底同步。
     *  「设定体检」与「按报告一键修复」共用，保证两条入口的保存行为完全一致 */
    private suspend fun applyCheckLines(pid: Long, cards: List<SettingCard>, out: String): CheckApplyResult {
        val dao = Repo.dao
        val fixed = mutableListOf<String>()
        val slimmed = mutableListOf<String>()
        var savedChars = 0
        val seen = mutableSetOf<String>()
        for (line in out.lines()) {
            val t = line.trim()
            val isFix = t.startsWith("【修复】")
            val isSlim = t.startsWith("【精简】")
            if (!isFix && !isSlim) continue
            val parts = t.removePrefix(if (isFix) "【修复】" else "【精简】").split("｜", "|", limit = 2)
            if (parts.size < 2) continue
            val name = parts[0].trim()
            val newContent = parts[1].trim()
            if (name.isBlank() || newContent.length < 20 || !seen.add(name)) continue
            if (name == "分章大纲" || name == "剧情进度" || name == "写作禁忌") continue
            val card = cards.firstOrNull { it.name == name }
                ?: cards.firstOrNull { it.name.contains(name) || name.contains(it.name) }
                ?: continue
            if (card.name == "分章大纲" || card.name == "剧情进度" || card.name == "写作禁忌" || card.content == newContent) continue
            // 精简必须真的变短才应用（防止 AI 复读原文）
            if (isSlim && newContent.length >= card.content.length) continue
            val appCtx = Repo.app
            if (appCtx != null) try {
                val d = File(FileTools.baseDir(appCtx, pid), "设定卡/备份")
                d.mkdirs()
                File(d, "设定体检备份.md").appendText(
                    "==== ${card.category}/${card.name}（${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())} 体检修改前） ====\n${card.content}\n\n",
                    Charsets.UTF_8
                )
            } catch (_: Exception) { }
            savedChars += (card.content.length - newContent.length).coerceAtLeast(0)
            dao.updateCard(card.copy(content = newContent, updatedAt = System.currentTimeMillis()))
            // v6.9.41：改卡必须同步写回项目文件夹（用户踩坑：只进了库，files/设定卡/*.md 还是旧内容）
            com.lele.novelmaster.engine.AppTasks.setProgress("cardsCheck:$pid", "🧾 正在保存修复：已更新 ${fixed.size + slimmed.size + 1} 张卡…")
            WriterEngine.exportCardFile(pid, card.copy(content = newContent), Repo.app)
            if (isFix) fixed.add(card.name) else slimmed.add(card.name)
        }
        // v6.9.41：全卡兜底同步——确保项目文件夹与库完全一致
        try { WriterEngine.syncAllCardsToFiles(pid, Repo.app) } catch (_: Exception) { }
        return CheckApplyResult(fixed, slimmed, savedChars)
    }

    /** v6.9.42 体检一键修复：体检报告只推理出问题、没改卡时，作者在报告里点「确认修复」→
     *  读最近一份体检报告，让 AI 按报告逐张给出修复内容，走 applyCheckLines 自动改卡并保存到项目文件
     *  v6.9.44：与「设定体检」共用同一把闸门——修复也是一次大 AI 调用（同一模型），若允许并行，
     *  两个请求会被同一 API Key 串行排队，后发的那个 5 分钟收不到首字节被看门狗误杀（用户实测踩坑） */
    suspend fun cardsApplyRepair(pid: Long): ToolResult {
        if (!cardsCheckGate.compareAndSet(false, true))
            return ToolResult(false, "设定体检/修复正在进行中，请等它完成再试（一般几分钟）。")
        return try { cardsApplyRepairInner(pid) } finally { cardsCheckGate.set(false) }
    }

    private suspend fun cardsApplyRepairInner(pid: Long): ToolResult {
        val appCtx = Repo.app ?: return ToolResult(false, "无法访问存储")
        val d = File(FileTools.baseDir(appCtx, pid), "设定卡/备份")
        val f = d.listFiles { x -> x.name.startsWith("设定体检报告-") && x.name.endsWith(".md") }
            ?.maxByOrNull { it.name }
            ?: return ToolResult(false, "还没有体检报告。先说「设定体检」跑一次，再点修复。")
        val report = f.readText(Charsets.UTF_8)
        if (report.contains("【通过】")) return ToolResult(false, "最近一次体检结论是【通过】，没有需要修复的问题。")
        val dao = Repo.dao
        val cards = dao.cards(pid)
        if (cards.isEmpty()) return ToolResult(false, "这本书还没有设定卡。")
        com.lele.novelmaster.engine.AppTasks.setProgress("cardsRepair:$pid", "🔧 正在按体检报告修复设定卡…")
        val (err, out) = WriterEngine.freeTask(
            pid,
            "任务：设定体检修复执行——下面是上一轮体检报告，把报告里指出的问题逐个修复成修正后的完整卡片内容。\n" +
                "【体检报告】\n$report\n\n" +
                "要求：\n" +
                "1) 全程只用简体中文，严禁英文，严禁输出思考过程/内心分析，直接给结果；\n" +
                "2) 只处理报告里指出的、能用改文字解决的真实矛盾；严禁动「剧情进度」「分章大纲」「写作禁忌」卡；\n" +
                "3) 跨卡统一：同一事实（能力代价、关键事件的经过、专有名词与数字）在多张卡里必须统一成同一个版本，需要改几张就分几条【修复】输出，严禁改一张留一张互相矛盾；\n" +
                "4) 只输出格式行，不要任何其他解释。\n" +
                "输出格式严格：\n" +
                "【修复】每条一行：卡名｜修正后的完整卡片内容（最多8条）\n" +
                "【精简】每条一行：卡名｜去重后的完整卡片内容（最多8条，每卡60~200字，只留干货）\n" +
                "报告里的问题没有能改文字解决的，就只输出【无修复项】。",
            task = com.lele.novelmaster.data.TaskModels.CHECK
        )
        if (err != null) return ToolResult(false, err)
        if (out.contains("【无修复项】") || (!out.contains("【修复】") && !out.contains("【精简】"))) {
            return ToolResult(false, "AI 判断报告里的问题无法直接改卡解决（可能要改大纲/剧情进度）。可以在聊天里直接说「修改××卡的××」，或重新体检一次。")
        }
        val ar = applyCheckLines(pid, cards, out)
        val head = "🔧 按体检报告修复完成（共 ${cards.size} 张卡）"
        val note = if (ar.fixedNames.isEmpty() && ar.slimmedNames.isEmpty())
            "\n\n未能匹配到可修改的卡（AI 给的卡名和现有卡对不上）。建议在聊天里直接说「修改××卡的××」手动指定。"
        else
            "\n\n✅ 已修复 ${ar.fixedNames.size} 张卡${if (ar.slimmedNames.isNotEmpty()) "、精简 ${ar.slimmedNames.size} 张卡（约省 ${ar.savedChars} 字）" else ""}：${(ar.fixedNames + ar.slimmedNames).joinToString("、")}\n已同步保存到项目文件夹（设定卡/*.md），原内容备份在 设定卡/备份/设定体检备份.md。"
        // 结果同样存档，方便回查
        try {
            val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
            File(d, "设定体检修复-$ts.md").writeText("# 按报告修复 $ts\n\n$out$note\n", Charsets.UTF_8)
        } catch (_: Exception) { }
        return ToolResult(true, head, out + note)
    }

    /** v6.9.29 查看体检报告：读 设定卡/备份/ 下最近的「设定体检报告-*.md」，只读不跑AI */
    fun cardsCheckReport(pid: Long): ToolResult {
        val appCtx = Repo.app ?: return ToolResult(false, "无法访问存储")
        val d = File(FileTools.baseDir(appCtx, pid), "设定卡/备份")
        val files = d.listFiles { f -> f.name.startsWith("设定体检报告-") && f.name.endsWith(".md") }
            ?.sortedByDescending { it.name } ?: emptyList()
        if (files.isEmpty()) return ToolResult(false, "还没有体检报告。先说「设定体检」跑一次，报告会自动存档到这里。")
        val head = "📄 共 ${files.size} 份体检报告，最新：${files[0].name}（${files[0].length() / 1024}KB）"
        val content = files[0].readText(Charsets.UTF_8)
        // 控 token：报告太长只回传前 3000 字，其余指引用文件管理器看
        val body = if (content.length > 3000) content.take(3000) + "\n\n…（报告超长已截断，全文见 设定卡/备份/${files[0].name}）" else content
        val older = if (files.size > 1) "\n\n（更早的报告：${files.drop(1).take(3).joinToString("、") { it.name }}${if (files.size > 4) " 等" else ""}）" else ""
        return ToolResult(true, head, body + older)
    }

    /** v6.9.35 设定瘦身：全部设定卡一次性查重去冗——重复内容只留在最相关的卡里，其余卡压缩成干货（主线/冲突/大纲互抄的克星） */
    suspend fun cardsSlim(pid: Long): ToolResult {
        val dao = Repo.dao
        val cards = dao.cards(pid).filter { it.name != "分章大纲" && it.name != "剧情进度" && it.name != "写作禁忌" }
        if (cards.isEmpty()) return ToolResult(false, "这本书还没有设定卡，无需瘦身。")
        // 瘦身需要看到较完整内容：每卡截 500 字（比体检的 160 宽），控总量
        val cardBlock = cards.joinToString("\n") {
            "· [${it.category}] ${it.name}：" + it.content.replace(Regex("\\s+"), " ").take(500)
        }
        val (err, out) = WriterEngine.freeTask(
            pid,
            "任务：设定卡瘦身——全书设定卡去重、压缩、抓重点。只动内容，不改任何设定事实，不新增设定。\n" +
                "【全部设定卡】\n$cardBlock\n\n" +
                "规则：\n" +
                "1) 多张卡写了同一件事（主线剧情/核心冲突/全书大纲互相复述是最常见的病）：同一件事只保留在最相关的一张卡里，其余卡删掉这部分；\n" +
                "2) 每卡只留对写作有用的干货：主线剧情=目标+阻力+结局走向(≤120字)；核心冲突=对立双方与不可调和点(≤80字)；全书大纲每条=阶段变化(≤80字)；世界观/圣经=规则体系；人物=身份/性格/能力/关系/目标；\n" +
                "3) 内容超过200字的卡必须压缩；空话套话、抒情排比、与剧情无关的背景介绍全部删除；\n" +
                "4) 严禁改动数值、名词、人物关系等设定事实本身。\n" +
                "输出格式严格（不要任何解释）：\n" +
                "【精简】卡名｜精简后的完整卡片内容（一行一张卡；只输出需要修改的卡，没有就只输出【通过】）",
            task = com.lele.novelmaster.data.TaskModels.CHECK // v6.9.41：瘦身与体检共用专用模型
        )
        if (err != null) return ToolResult(false, err)
        if (out.contains("【通过】")) return ToolResult(true, "✂️ 设定瘦身完成：各卡已经很精炼，无需修改", out)
        var applied = 0
        var savedChars = 0
        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (line in out.lines()) {
            val t = line.trim()
            if (!t.startsWith("【精简】")) continue
            val parts = t.removePrefix("【精简】").split("｜", "|", limit = 2)
            if (parts.size < 2) continue
            val name = parts[0].trim()
            val newContent = parts[1].trim()
            if (name.isBlank() || newContent.length < 20 || !seen.add(name)) continue
            val card = cards.firstOrNull { it.name == name }
                ?: cards.firstOrNull { it.name.contains(name) || name.contains(it.name) }
                ?: continue
            if (card.content == newContent || newContent.length >= card.content.length) continue
            val appCtx = Repo.app
            if (appCtx != null) try {
                val d = File(FileTools.baseDir(appCtx, pid), "设定卡/备份")
                d.mkdirs()
                File(d, "设定瘦身备份.md").appendText(
                    "==== ${card.category}/${card.name}（${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())} 瘦身前） ====\n${card.content}\n\n",
                    Charsets.UTF_8
                )
            } catch (_: Exception) { }
            savedChars += (card.content.length - newContent.length).coerceAtLeast(0)
            dao.updateCard(card.copy(content = newContent, updatedAt = System.currentTimeMillis()))
            // v6.9.41：改卡必须同步写回项目文件夹
            com.lele.novelmaster.engine.AppTasks.setProgress("cardsSlim:$pid", "✂️ 正在保存：已精简 ${applied + 1} 张卡…")
            WriterEngine.exportCardFile(pid, card.copy(content = newContent), Repo.app)
            names.add(card.name)
            applied++
        }
        // v6.9.41：全卡兜底同步——确保项目文件夹与库完全一致
        try { WriterEngine.syncAllCardsToFiles(pid, Repo.app) } catch (_: Exception) { }
        val head = if (applied > 0) "✂️ 设定瘦身完成：精简 $applied 张卡，共减 $savedChars 字" else "✂️ 设定瘦身完成：没有需要精简的卡"
        val note = if (applied > 0)
            "\n\n已修改：${names.joinToString("、")}（原内容备份在 设定卡/备份/设定瘦身备份.md）\n注入 AI 的内容随之变少：响应更快、更省 token。"
        else ""
        return ToolResult(true, head, out.take(1500) + note)
    }

    /** v6.9.31 手动修改第N章大纲：唯一的大纲人工修改入口（UI 只读），改完自动同步分章大纲镜像卡 */
    suspend fun setChapterOutline(pid: Long, idx: Int, text: String): ToolResult {
        val clean = text.trim()
        if (idx <= 0 || clean.isEmpty())
            return ToolResult(false, "用法：修改第3章大纲：新大纲内容（也可《新标题》：新大纲 同时改标题）")
        val dao = Repo.dao
        val chs = dao.chapters(pid)
        val ch = chs.firstOrNull { it.chapterIndex == idx }
            ?: return ToolResult(false, "第 $idx 章不存在（本书已建 ${chs.size} 章）")
        var title = ch.title
        var outline = clean.take(500)
        // 《新标题》：新大纲 同时改标题；否则只改大纲
        Regex("^《([^》]{1,40})》[：:]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL).find(clean)?.let { m ->
            title = m.groupValues[1]
            outline = m.groupValues[2].trim().take(500)
        }
        dao.updateChapter(ch.copy(title = title, outline = outline, updatedAt = System.currentTimeMillis()))
        WriterEngine.syncChapterOutlineCard(pid, Repo.app)
        val titleNote = if (title != ch.title) "（标题改为《$title》）" else ""
        return ToolResult(true, "✅ 第${idx}章大纲已更新$titleNote，分章大纲卡已同步", "新大纲：$outline")
    }

    /** 8.0 全书逐章自检修复：对每章跑写后自检（发现矛盾自动修正），与「全书体检」（只列问题不修）互补 */
    suspend fun fullSelfCheck(pid: Long): ToolResult {
        val dao = Repo.dao
        val cfg = Repo.apiFor(pid) ?: return ToolResult(false, "请先在【AI模型】中启用一个模型")
        val project = dao.project(pid) ?: return ToolResult(false, "项目不存在")
        val (err, out) = WriterEngine.fullSelfCheck(cfg, dao, project, Repo.app)
        return if (err == null) ToolResult(true, "🔬 全书逐章自检已完成（详见报告）", out) else ToolResult(false, err)
    }

    /** 8.1 单章自检（写后自检的手动版）：对指定章跑体检逻辑，发现矛盾自动修正；chapterIndex<=0 表示最新已写章 */
    suspend fun chapterSelfCheck(pid: Long, chapterIndex: Int): ToolResult {
        val dao = Repo.dao
        val cfg = Repo.apiFor(pid) ?: return ToolResult(false, "请先在【AI模型】中启用一个模型")
        val project = dao.project(pid) ?: return ToolResult(false, "项目不存在")
        val ch0 = if (chapterIndex > 0)
            dao.chapters(pid).firstOrNull { it.chapterIndex == chapterIndex }
        else
            dao.chapters(pid).lastOrNull { it.content.isNotBlank() }
        val ch = ch0 ?: return ToolResult(false, if (chapterIndex > 0) "第 $chapterIndex 章不存在或还没写" else "还没有已写章节")
        if (chapterIndex > 0 && ch.content.isBlank()) return ToolResult(false, "第 $chapterIndex 章还没有正文")
        val before = ch.content
        try {
            WriterEngine.selfCheckChapter(cfg, dao, project, ch, Repo.app)
        } catch (e: Exception) {
            return ToolResult(false, "自检失败：${e.message?.take(120)}")
        }
        val after = dao.chapters(pid).firstOrNull { it.chapterIndex == ch.chapterIndex }
        val fixed = after != null && after.content != before
        return ToolResult(
            true,
            "🔍 第${ch.chapterIndex}章自检完成：",
            if (fixed) "发现与设定/前情矛盾并已自动修正（详见上方消息）。"
            else "未发现可自动修正的矛盾；若有疑似问题会在上方列出，供你复核。"
        )
    }

    /** 8.2 撤销第N章最近一次自检修改：用正文备份恢复（v6.9.10 安全网） */
    suspend fun undoSelfCheck(pid: Long, chapterIndex: Int): ToolResult {
        val dao = Repo.dao
        val cfg = Repo.apiFor(pid) ?: return ToolResult(false, "请先在【AI模型】中启用一个模型")
        val project = dao.project(pid) ?: return ToolResult(false, "项目不存在")
        if (chapterIndex <= 0) return ToolResult(false, "请说明要撤销哪一章，如：撤销第5章自检修改")
        val (err, out) = WriterEngine.restoreLastSelfCheck(cfg, dao, project, chapterIndex, Repo.app)
        return if (err == null) ToolResult(true, "↩️ 第${chapterIndex}章已恢复到自检前版本", out) else ToolResult(false, err)
    }

    /** 8.3 自检进度：哪些章已自检/修过/待复核，哪些还没检（v6.9.13） */
    suspend fun selfCheckProgress(pid: Long): ToolResult {
        val dao = Repo.dao
        val project = dao.project(pid) ?: return ToolResult(false, "项目不存在")
        val chapters = dao.chapters(pid).filter { it.content.isNotBlank() }.sortedBy { it.chapterIndex }
        if (chapters.isEmpty()) return ToolResult(false, "还没有已写章节")
        val rec = WriterEngine.readSelfCheckRecord(pid, Repo.app)
        val idx = chapters.map { it.chapterIndex }
        val pass = idx.filter { rec[it] == "pass" }
        val fixed = idx.filter { rec[it] == "fixed" }
        val suspect = idx.filter { rec[it] == "suspect" }
        // v6.9.18：restored（撤销后）视为未检
        val unchecked = idx.filter { rec[it] !in setOf("pass", "fixed", "suspect") }
        val sb = StringBuilder("已自检 ${rec.size}/${chapters.size} 章\n")
        if (unchecked.isNotEmpty()) sb.append("· ⬜ 未自检（${unchecked.size}）：第${unchecked.joinToString("、")}章\n")
        if (fixed.isNotEmpty()) sb.append("· 🔧 自检时修复过（${fixed.size}）：第${fixed.joinToString("、")}章\n")
        if (suspect.isNotEmpty()) sb.append("· ⚠️ 有疑似矛盾待复核（${suspect.size}）：第${suspect.joinToString("、")}章\n")
        if (pass.isNotEmpty()) sb.append("· ✅ 通过（${pass.size}）：第${pass.joinToString("、")}章\n")
        sb.append(if (unchecked.isEmpty()) "全书所有章节均已自检过。" else "可说「全书自检」或「自检第N章」补检。")
        return ToolResult(true, "📋 自检进度：", sb.toString())
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
            "stopAutoWrite" -> stopAutoWrite(pid)
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
            "publishPolish" -> publishPolish(pid, args.optInt("index", -1))
            "expandDialogue" -> expandDialogue(pid, args.optInt("index", -1))
            "styleRewrite" -> styleRewrite(pid, args.optInt("index", -1), args.optString("style"))
            "hookChapter" -> hookChapter(pid, args.optInt("index", -1))
            "goldenLines" -> goldenLines(pid, args.optInt("index", -1))
            "plotBrainstorm" -> plotBrainstorm(pid)
            "characterCheck" -> characterCheck(pid, args.optString("name"))
            "consistencyCheck" -> consistencyCheck(pid)
            // v6.9.16：一致性体系新工具（v6.9.8~v6.9.15）接入 AI 工具协议
            "chapterSelfCheck" -> chapterSelfCheck(pid, args.optInt("index", -1))
            "fullSelfCheck" -> fullSelfCheck(pid)
            "selfCheckProgress" -> selfCheckProgress(pid)
            "undoSelfCheck" -> undoSelfCheck(pid, args.optInt("index", 0))
            "supplementChapter" -> supplementChapter(pid, args.optInt("index", -1))
            "foreshadowCheck" -> foreshadowCheck(pid)
            "markHookRecovered" -> markHookRecovered(pid, args.optString("name"))
            "subplotCheck" -> subplotCheck(pid)
            "cardsCheck" -> cardsCheck(pid)
            "cardsCheckReport" -> cardsCheckReport(pid)
            "cardsSlim" -> cardsSlim(pid)
            "setChapterOutline" -> setChapterOutline(pid, args.optInt("index"), args.optString("text"))
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
