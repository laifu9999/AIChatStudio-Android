package com.lele.novelmaster.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 写作引擎：大纲生成 / 设定卡生成 / 单章写作 / 续写 / 重写。
 * v5：每次注入都在聊天里播报「📥 已注入内容」；正文/大纲/设定卡全部同步保存到会话项目文件夹。
 */
object WriterEngine {

    /** 会话项目子文件夹（正文/大纲/设定卡…），context 为空时返回 null（仅跳过落盘，不影响写作） */
    private fun dir(context: Context?, pid: Long, sub: String): File? {
        if (context == null) return null
        return try {
            val base = File(context.filesDir, "novels/$pid/files/$sub")
            base.mkdirs()
            base
        } catch (e: Exception) { null }
    }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifBlank { "未命名" }

    /** v5.5：把 AI 返回中的标题行、代码围栏、meta 说明等非正文内容去掉，只保留正文。 */
    private fun cleanBody(text: String, chapterIndex: Int, title: String): String {
        var s = text.trim()
        // 去掉 ``` 围栏
        s = s.replace(Regex("```[\\s\\S]*?```"), "")
        // 去掉开头可能出现的「第X章 标题」行（多种写法）
        val variants = listOf(
            "第\\s*$chapterIndex\\s*章",
            "第\\s*$chapterIndex\\s*章[《]${Regex.escape(title)}[》]",
            "第\\s*$chapterIndex\\s*章\\s*${Regex.escape(title)}",
            "[《]${Regex.escape(title)}[》]"
        )
        for (v in variants) {
            s = s.replace(Regex("^\\s*$v\\s*[:：]?\\s*\\n?", RegexOption.IGNORE_CASE), "")
        }
        // 过滤掉明显是 meta 的行
        s = s.lines().filterNot { line ->
            val l = line.trim()
            l.startsWith("【回收伏笔】") ||
                l.startsWith("作者有话") ||
                l.startsWith("本章小结") ||
                l.startsWith("剧情总结") ||
                l.startsWith("注：") ||
                l.matches(Regex("^第\\s*\\d+\\s*章[：:\\s].*")) ||
                l.matches(Regex("^第\\s*\\d+\\s*章\\s*[《].*[》].*"))
        }.joinToString("\n")
        return s.trim()
    }

    /** 补齐缺失的分章大纲（只处理 from..to 范围内） */
    suspend fun ensureOutlines(projectId: Long, from: Int = 1, to: Int = Int.MAX_VALUE, context: Context? = null): String? {
        val dao = Repo.dao
        val cfg = dao.activeApi() ?: return "请先在【AI模型】中添加并启用一个模型"
        val project = dao.project(projectId) ?: return "项目不存在"
        val cards = dao.cards(projectId)
        val chapters = dao.chapters(projectId)
        val missing = chapters.filter { it.outline.isBlank() && it.chapterIndex in from..to }
        if (missing.isEmpty()) return null

        val batches = missing.chunked(15)
        for (batch in batches) {
            val f = batch.first().chapterIndex
            val t = batch.last().chapterIndex
            val user = Prompts.buildOutlineUser(project, cards, chapters, f, t, batch.size)
            val reply = AiClient.chat(
                cfg,
                listOf(ChatMsg("system", Prompts.OUTLINE_SYSTEM), ChatMsg("user", user)),
                temperature = 0.7,
                maxTokens = 8192
            )
            applyOutlines(dao, chapters, reply)
        }

        // 大纲全量落盘：files/大纲/分章大纲.md
        try {
            dir(context, projectId, "大纲")?.let { d ->
                val fresh = dao.chapters(projectId)
                val md = buildString {
                    appendLine("# 《${project.title}》分章大纲")
                    appendLine()
                    fresh.filter { it.outline.isNotBlank() }.forEach {
                        appendLine("## 第${it.chapterIndex}章 ${it.title}")
                        appendLine(it.outline)
                        appendLine()
                    }
                }
                File(d, "分章大纲.md").writeText(md, Charsets.UTF_8)
            }
        } catch (_: Exception) { }
        return null
    }

    /** 解析AI返回的大纲行并写回章节 */
    private suspend fun applyOutlines(dao: NovelDao, chapters: List<Chapter>, reply: String) {
        val regex = Regex("第\\s*(\\d+)\\s*章")
        for (raw in reply.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = regex.find(line) ?: continue
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            val ch = chapters.firstOrNull { it.chapterIndex == idx } ?: continue
            var rest = line.substring(m.range.last + 1).trim()
            var title = ch.title
            var outline = ch.outline
            val sep = rest.indexOfFirst { it == '：' || it == ':' }
            if (sep >= 0) {
                val t = rest.substring(0, sep).trim().removeSurrounding("《", "》")
                if (t.isNotBlank()) title = t
                outline = rest.substring(sep + 1).trim()
            } else {
                outline = rest.removeSurrounding("《", "》").trim()
            }
            if (outline.isNotBlank() || title != ch.title) {
                dao.updateChapter(ch.copy(title = title, outline = outline))
            }
        }
    }

    /**
     * 灵感分析：自动生成一整套设定卡（并全部落盘到 files/设定卡/）
     * v5.9：改为流式生成——AI 每写完一行就立即落库落盘并在聊天里显示一张，
     *      边输出边保存边显示，不再"卡很久然后突然全部显示"。
     */
    suspend fun generateCardsFromInspire(projectId: Long, inspiration: String, context: Context? = null): String? {
        val dao = Repo.dao
        val cfg = dao.activeApi() ?: return "请先在【AI模型】中添加并启用一个模型"
        val project = dao.project(projectId) ?: return "项目不存在"

        // 一本小说必须建齐的核心分类
        val required = listOf("世界观", "人物设定", "主线剧情", "核心冲突", "设定圣经", "全书大纲", "支线任务", "伏笔钩子")
        var count = 0
        var pass = 0
        var lastSnippet = ""

        while (pass < 3) {
            val have = dao.cards(projectId)
            val need = required.filter { cat -> have.none { c -> c.category == cat } }
            if (need.isEmpty()) break
            pass++
            val msgs = listOf(
                ChatMsg("system", Prompts.INSPIRE_SYSTEM),
                ChatMsg("user", Prompts.buildInspireUser(project, inspiration, need, have))
            )
            val sb = StringBuilder()
            var processed = 0
            var passCount = 0
            try {
                AiClient.chatStream(cfg, msgs, temperature = 0.8, maxTokens = 8192) { delta ->
                    sb.append(delta)
                    // 每凑齐一行就立刻解析保存一张 —— 输出的同时保存并显示
                    while (true) {
                        val nl = sb.indexOf('\n', processed)
                        if (nl < 0) break
                        val line = sb.substring(processed, nl)
                        processed = nl + 1
                        passCount += applyInspireLine(projectId, line, context)
                    }
                }
                // 最后一行可能没有换行结尾
                passCount += applyInspireLine(projectId, sb.substring(processed), context)
            } catch (e: Exception) {
                if (count == 0 && passCount == 0) return "AI 生成设定失败：${e.message?.take(200)}"
                break
            }
            count += passCount
            if (passCount == 0) {
                lastSnippet = sb.toString().take(200)
                break
            }
        }
        return if (count == 0)
            "AI未能生成可识别的设定（回复片段：$lastSnippet）。建议换一个模型，或把灵感说得更具体一点。"
        else null
    }

    /** 解析一行「分类｜名称｜内容」，落库落盘并立即在聊天里显示，返回 1/0 */
    private suspend fun applyInspireLine(projectId: Long, rawLine: String, context: Context?): Int {
        val dao = Repo.dao
        var line = rawLine.trim()
            .trimStart('-', '*', '·', '•', '#', '>')
            .trim()
        // 去掉行首的 1. / 1、/ (1) 之类编号
        line = line.replace(Regex("^[0-9]{1,2}[.、)）]\\s*"), "").trim()
        val parts = when {
            line.contains("｜") -> line.split("｜")
            line.contains("|") -> line.split("|")
            line.contains("：") && line.indexOf("：") < 12 -> line.split("：", limit = 3)
            else -> return 0
        }
        if (parts.size < 3) return 0
        var cat = parts[0].trim().removeSurrounding("【", "】").removeSurrounding("[", "]").trim()
        // 兼容 「人物设定 - 林墨」/「人物设定：林墨」这类写法
        if (cat !in CardCategories.all) {
            val hit = CardCategories.all.firstOrNull { cat.contains(it) }
            if (hit != null) {
                cat = hit
            } else return 0
        }
        val name = parts[1].trim().trim('《', '》', '「', '」', '"', '\'').take(50)
        if (name.isBlank() || name.length > 50) return 0
        val content = parts.drop(2).joinToString("｜").trim()
        if (content.length < 4) return 0
        // 同名同分类视为已存在，跳过，避免重复建卡
        if (dao.findCard(projectId, cat, name) != null) return 0
        dao.insertCard(
            SettingCard(
                projectId = projectId,
                category = cat,
                name = name,
                content = content,
                priority = if (cat == "世界观" || cat == "人物设定" || cat == "设定圣经") 2 else 1,
                status = if (cat == "伏笔钩子") "埋设中" else ""
            )
        )
        // 落盘 files/设定卡/{分类}/{名称}.md
        try {
            dir(context, projectId, "设定卡/$cat")?.let { d ->
                File(d, safeName(name) + ".md").writeText("# $cat · $name\n\n$content\n", Charsets.UTF_8)
            }
        } catch (_: Exception) { }
        // v5.9：生成一张立即在聊天里显示一张（保存的同时不隐藏内容）
        val show = if (content.length > 600) content.take(600) + "\n…（全文共 ${content.length} 字，已完整保存）" else content
        dao.insertMessage(
            Message(
                projectId = projectId, role = "tool", kind = "tool",
                content = "✅ 已生成设定卡：$cat / $name（${content.length} 字）\n\n$show"
            )
        )
        return 1
    }

    /** 整段解析（供非流式路径复用） */
    private suspend fun applyInspireLines(projectId: Long, reply: String, context: Context?): Int {
        var count = 0
        for (raw in reply.lines()) count += applyInspireLine(projectId, raw, context)
        return count
    }

    /** v5.6：结尾是否已收束（以句号/叹号/问号/省略号/引号等结尾），用于判断章节是否写完 */
    private fun endsWell(s: String): Boolean {
        val t = s.trimEnd()
        if (t.isEmpty()) return false
        val last = t.last()
        return last in "。！？…」』”\"!?~"
    }

    /**
     * 写单章：
     *  1) 写前在聊天播报「📥 已注入内容」（必发卡/伏笔/摘要/结尾/相邻大纲摘要）
     *  2) 生成正文 → 存库 → 同时落盘 files/正文/第N章-标题.txt
     *  3) 摘要 / 剧情进度 / 伏笔回收
     */
    suspend fun writeOne(project: Project, cfg: ApiConfig, dao: NovelDao, ch0: Chapter, context: Context? = null) {
        val cards = dao.cards(project.id)
        val chapters = dao.chapters(project.id)
        val messages = Prompts.buildChapterMessages(project, cards, chapters, ch0)

        // 播报本次注入内容（每章都提示；插入失败=记录不可靠，抛错让自动写作立即停止）
        val inject = buildString {
            append("📥 已注入本章上下文：")
            append("设定卡 ${cards.size} 张（选中 ${Prompts.selectCards(cards, ch0.outline + ch0.title).size} 张）")
            append(" · 未回收伏笔 ${cards.count { it.category == "伏笔钩子" && it.status != "已回收" }} 条")
            append(" · 前5章摘要+上一章结尾300字+相邻大纲")
            append(" · 合计约 ${messages.sumOf { it.content.length }} 字")
        }
        dao.insertMessage(Message(projectId = project.id, role = "tool", content = inject, kind = "tool"))

        // v6.1：流式写章——正文边生成边实时显示在聊天里（不再"注入完就没下文"）；
        // 之前非流式 + 60s 读超时，章节生成要 1~3 分钟必然超时，这就是"只注入不写"的根因
        val liveTitle = ch0.title.ifBlank { "未命名" }
        var liveId = 0L
        var lastUi = 0L
        val live = StringBuilder()
        val streamed = try {
            AiClient.chatStream(cfg, messages, temperature = 0.85) { delta ->
                live.append(delta)
                val now = System.currentTimeMillis()
                if (liveId == 0L) {
                    liveId = dao.insertMessage(
                        Message(projectId = project.id, role = "assistant", kind = "text",
                            content = "✍️ 第${ch0.chapterIndex}章《$liveTitle》生成中…\n\n" + live.toString())
                    )
                    lastUi = now
                } else if (now - lastUi > 400) {
                    lastUi = now
                    dao.updateMessageContent(liveId, "📖 第${ch0.chapterIndex}章《$liveTitle》\n\n" + live.toString())
                }
            }.text
        } catch (e: Exception) {
            if (liveId != 0L) dao.updateMessageContent(liveId, "⚠️ 第${ch0.chapterIndex}章生成失败：${e.message?.take(200)}")
            throw e
        }

        var content = cleanBody(streamed.trim(), ch0.chapterIndex, ch0.title)
        if (content.isBlank()) throw IllegalStateException("AI返回空内容")

        // v5.6/v5.7：完整性保障——太短或结尾没有收束标点时续写补完（最多5次，保证每章都写完整）
        val target = project.chapterWordTarget
        var fixUps = 0
        while (fixUps < 5 && (content.length < target * 85 / 100 || !endsWell(content))) {
            fixUps++
            try {
                val words = (target - content.length).coerceIn(300, 2200)
                val cont = AiClient.chat(
                    cfg,
                    Prompts.continueMessages(project, cards, ch0, content, words),
                    temperature = 0.85
                ).trim()
                if (cont.isBlank()) break
                val merged = cleanBody(content + "\n" + cont, ch0.chapterIndex, ch0.title)
                if (merged.length <= content.length + 20 && endsWell(content)) break
                content = merged
                if (endsWell(content) && content.length >= target * 85 / 100) break
            } catch (_: Exception) { break }
        }

        var ch = ch0.copy(
            content = content,
            wordCount = content.length,
            status = 1,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateChapter(ch)

        // v6.1：聊天里显示完整正文（不藏摘要后面）；同时已保存到章节库 + files/正文/
        val fullText = "📖 第${ch.chapterIndex}章《${ch.title.ifBlank { "未命名" }}》（${content.length} 字，已保存）\n\n$content"
        if (liveId != 0L) dao.updateMessageContent(liveId, fullText)
        else dao.insertMessage(Message(projectId = project.id, role = "assistant", kind = "text", content = fullText))

        // 正文落盘 files/正文/第N章-标题.txt（只存正文，不加标题头）
        try {
            dir(context, project.id, "正文")?.let { d ->
                File(d, safeName("第${ch.chapterIndex}章-${ch.title.ifBlank { "未命名" }}") + ".txt")
                    .writeText(content + "\n", Charsets.UTF_8)
            }
        } catch (_: Exception) { }

        // 摘要 + 伏笔回收检测
        try {
            val sumReply = AiClient.chat(
                cfg,
                listOf(
                    ChatMsg("system", "你是小说编辑，只输出被要求的内容。"),
                    ChatMsg(
                        "user",
                        "请用120字以内概括以下章节的剧情要点（含出场人物、关键事件、新埋的伏笔）。" +
                            "若本章明确回收了之前的伏笔，最后另起一行，用【回收伏笔】开头列出名称；否则不要输出该行。\n" +
                            "第${ch.chapterIndex}章《${ch.title}》正文：\n${content.take(4000)}"
                    )
                ),
                temperature = 0.3,
                maxTokens = 2048
            )
            val recovered = sumReply.lineSequence()
                .firstOrNull { it.contains("回收伏笔") }
                ?.substringAfter("】")?.trim().orEmpty()
            val summary = sumReply.lines()
                .filter { !it.contains("回收伏笔") }
                .joinToString(" ").trim()
            ch = ch.copy(summary = summary)
            dao.updateChapter(ch)

            if (recovered.isNotBlank()) {
                dao.cards(project.id)
                    .filter { it.category == "伏笔钩子" && it.status != "已回收" && recovered.contains(it.name) }
                    .forEach { dao.updateCard(it.copy(status = "已回收", updatedAt = System.currentTimeMillis())) }
            }
        } catch (_: Exception) {
            // 摘要失败不影响正文
        }

        // 自动维护「剧情进度」卡
        val progressText = "已完成至第${ch.chapterIndex}章《${ch.title}》:${ch.summary}"
        val existing = dao.findCard(project.id, "剧情进度", "最新进度")
        if (existing != null) {
            dao.updateCard(existing.copy(content = progressText, updatedAt = System.currentTimeMillis()))
        } else {
            dao.insertCard(
                SettingCard(projectId = project.id, category = "剧情进度", name = "最新进度", content = progressText)
            )
        }
    }

    /** 编辑器：续写当前章 */
    suspend fun continueChapter(chapterId: Long, currentText: String): String {
        val dao = Repo.dao
        val cfg = dao.activeApi() ?: throw IllegalStateException("请先在【AI模型】中启用一个模型")
        val ch = dao.chapter(chapterId) ?: throw IllegalStateException("章节不存在")
        val project = dao.project(ch.projectId) ?: throw IllegalStateException("项目不存在")
        val cards = dao.cards(ch.projectId)
        val messages = Prompts.continueMessages(project, cards, ch, currentText)
        val out = AiClient.chat(cfg, messages, temperature = 0.9)
        return currentText + "\n" + out.trim()
    }

    /** 编辑器：AI重写整章 */
    suspend fun rewriteChapter(chapterId: Long): String? {
        val dao = Repo.dao
        val cfg = dao.activeApi() ?: return "请先在【AI模型】中启用一个模型"
        val ch = dao.chapter(chapterId) ?: return "章节不存在"
        val project = dao.project(ch.projectId) ?: return "项目不存在"
        val cards = dao.cards(ch.projectId)
        val chapters = dao.chapters(ch.projectId)
        val messages = Prompts.buildChapterMessages(project, cards, chapters, ch).toMutableList()
        messages.add(ChatMsg("user", "注意：这是重写版本，请给出质量更高、更精彩的全新写法，只输出正文。"))
        val content = cleanBody(AiClient.chat(cfg, messages).trim(), ch.chapterIndex, ch.title)
        if (content.isBlank()) return "AI返回空内容"
        dao.updateChapter(
            ch.copy(
                content = content,
                wordCount = content.length,
                status = 1,
                summary = "",
                updatedAt = System.currentTimeMillis()
            )
        )
        return null
    }

    /**
     * 通用章节任务引擎（专家级写作功能的基础设施）。
     * instruction: 对本章做什么；replace: 是否用 AI 返回内容替换正文。
     * 返回 null=成功（结果已应用/展示），否则返回错误信息。
     */
    suspend fun chapterTask(
        projectId: Long,
        chapterIndex: Int,
        instruction: String,
        replace: Boolean
    ): Pair<String?, String> { // (err, output)
        val dao = Repo.dao
        val cfg = dao.activeApi() ?: return "请先在【AI模型】中启用一个模型" to ""
        val project = dao.project(projectId) ?: return "项目不存在" to ""
        val chapters = dao.chapters(projectId)
        val ch = chapters.firstOrNull { it.chapterIndex == chapterIndex }
            ?: return "第 $chapterIndex 章不存在" to ""
        val cards = dao.cards(projectId)
        val messages = Prompts.buildChapterMessages(project, cards, chapters, ch).toMutableList()
        messages.add(ChatMsg("user", instruction))
        val out = cleanBody(AiClient.chat(cfg, messages, temperature = 0.8).trim(), chapterIndex, ch.title)
        if (out.isBlank()) return "AI返回为空" to ""
        if (replace && out.length >= 300) {
            dao.updateChapter(
                ch.copy(
                    content = out,
                    wordCount = out.length,
                    status = 1,
                    summary = "",
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        return null to out
    }

    /** 非章节类自由任务（起名/简介/体检等）：带核心设定上下文问 AI */
    suspend fun freeTask(projectId: Long, instruction: String): Pair<String?, String> {
        val dao = Repo.dao
        val cfg = dao.activeApi() ?: return "请先在【AI模型】中启用一个模型" to ""
        val project = dao.project(projectId) ?: return "项目不存在" to ""
        val cards = dao.cards(projectId)
        val sys = buildString {
            appendLine("你是资深网文主编。基于以下设定完成任务，只输出要求的内容。")
            appendLine("《${project.title}》类型：${project.genre}")
            val core = cards.filter { it.priority == 2 || it.category in CardCategories.KEY_CATS || it.category == "人物设定" }
            if (core.isNotEmpty()) appendLine(Prompts.cardBlock(core))
        }
        val out = AiClient.chat(
            cfg,
            listOf(ChatMsg("system", sys), ChatMsg("user", instruction)),
            temperature = 0.85,
            maxTokens = 8192
        ).trim()
        if (out.isBlank()) return "AI返回为空" to ""
        return null to out
    }
}

/**
 * 自动写作管理器：跨界面保持状态，逐章写完全书。
 * 每写完一章自动保存到数据库（即"保存到手机"），并自动生成摘要供后续章节引用。
 */
object AutoWriteManager {

    data class Progress(
        val running: Boolean = false,
        val projectId: Long = 0,
        val currentChapter: String = "",
        val done: Int = 0,
        val total: Int = 0,
        val logs: List<String> = emptyList()
    )

    val state = MutableStateFlow(Progress())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: kotlinx.coroutines.Job? = null

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private fun log(msg: String) = state.update {
        it.copy(logs = (listOf("${fmt.format(Date())} $msg") + it.logs).take(120))
    }

    fun start(projectId: Long, from: Int, to: Int, context: Context? = null) {
        if (state.value.running) return
        job = scope.launch {
            state.value = Progress(running = true, projectId = projectId)
            try {
                val dao = Repo.dao
                val project = dao.project(projectId) ?: run { log("项目不存在"); return@launch }
                val cfg = dao.activeApi() ?: run { log("未启用AI模型：请到【AI模型】添加并设为启用"); return@launch }
                log("开始自动写作《${project.title}》第$from~$to 章（模型：${cfg.model}）")

                // 1) 先补齐范围内缺失的大纲
                state.value = state.value.copy(currentChapter = "生成缺失大纲中…")
                val err = WriterEngine.ensureOutlines(projectId, from, to, context)
                if (err != null) { log(err); return@launch }
                log("大纲已就绪")

                // 2) 逐章写作
                val targets = dao.chapters(projectId).filter { it.chapterIndex in from..to }
                state.value = state.value.copy(total = targets.size, done = 0)
                var done = 0
                var fail = 0
                for (t in targets) {
                    if (!isActive || !state.value.running) break
                    state.value = state.value.copy(currentChapter = "第${t.chapterIndex}章 ${t.title.ifBlank { "写作中…" }}")
                    try {
                        val fresh = dao.chapter(t.id) ?: t
                        if (fresh.content.isNotBlank() && fresh.status == 2) {
                            log("跳过第${t.chapterIndex}章（已编辑定稿，不覆盖）")
                            done++
                            state.value = state.value.copy(done = done)
                            continue
                        }
                        WriterEngine.writeOne(project, cfg, dao, fresh, context)
                        fail = 0
                        done++
                        state.value = state.value.copy(done = done)
                        log("✅ 第${t.chapterIndex}章完成（$done/${targets.size}）")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fail++
                        log("❌ 第${t.chapterIndex}章失败：${e.message?.take(200)}")
                        if (fail >= 3) { log("连续失败3次，已自动停止"); break }
                    }
                }
                log("任务结束：完成 $done/${targets.size} 章")
            } finally {
                state.value = state.value.copy(running = false, currentChapter = "")
            }
        }
    }

    fun stop() {
        state.value = state.value.copy(running = false)
        job?.cancel()
    }
}
