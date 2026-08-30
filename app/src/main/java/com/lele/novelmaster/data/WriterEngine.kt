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

    /** 补齐缺失的分章大纲（只处理 from..to 范围内）；v6.7：无论本轮是否补了大纲，结尾都同步「分卷大纲/分章大纲」设定卡 */
    suspend fun ensureOutlines(projectId: Long, from: Int = 1, to: Int = Int.MAX_VALUE, context: Context? = null): String? {
        val dao = Repo.dao
        val project = dao.project(projectId) ?: return "项目不存在"
        val cards = dao.cards(projectId)
        val chapters = dao.chapters(projectId)
        val missing = chapters.filter { it.outline.isBlank() && it.chapterIndex in from..to }

        if (missing.isNotEmpty()) {
            val cfg = dao.activeApi() ?: return "请先在【AI模型】中添加并启用一个模型"
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
        }

        // v6.7：分章大纲文件只放 设定卡/分章大纲/ 文件夹，不再写顶级「大纲/」文件夹（消除重复生成）
        syncChapterOutlineCard(projectId, context)
        syncVolumeOutlineCard(projectId, context)
        return null
    }

    /** v6.4：写正文必须建全的八类核心设定卡，一张都不能少 */
    val REQUIRED_CATS = listOf(
        "世界观", "人物设定", "主线剧情", "核心冲突", "支线任务", "伏笔钩子", "设定圣经", "全书大纲"
    )

    /** v6.5：单卡分类——一个会话（一本书）里该分类只允许存在一张卡 */
    val SINGLE_CATS = setOf("世界观", "主线剧情", "核心冲突", "设定圣经", "全书大纲", "剧情进度")

    /**
     * v6.5：单卡分类去重兜底——把老版本堆出来的重复卡（例如两张不同名字的世界观）合并：
     * 每个单卡分类只保留最早的一张，其余全部删除。返回合并掉的数量。
     */
    /** v6.7：与「全书大纲」主卡共存的系统卡名——单类去重时按名字分别保留，绝不互相吞并 */
    val OUTLINE_CARD_NAMES = setOf("分卷大纲", "分章大纲")

    suspend fun mergeDuplicateSingles(projectId: Long): Int {
        val dao = Repo.dao
        var merged = 0
        for (cat in SINGLE_CATS) {
            val list = dao.cards(projectId).filter { it.category == cat }
            if (list.size <= 1) continue
            val keep: List<SettingCard> = if (cat == "全书大纲") {
                // v6.7：全书大纲类允许 主卡+分卷大纲+分章大纲 各留一张共存，其余同名的仍只留最早一张
                val protected = list.filter { it.name == cat || it.name in OUTLINE_CARD_NAMES }
                    .groupBy { it.name }.mapNotNull { g -> g.value.minByOrNull { c -> c.id } }
                val others = list.filter { c -> c.name != cat && c.name !in OUTLINE_CARD_NAMES }
                    .groupBy { it.name }.mapNotNull { g -> g.value.minByOrNull { c -> c.id } }
                protected + others
            } else {
                listOf(list.minByOrNull { it.id } ?: continue)
            }
            list.filter { c -> keep.none { it.id == c.id } }.forEach {
                dao.deleteCard(it)
                merged++
            }
        }
        return merged
    }

    /**
     * v6.5：把作者在本地文件里做的修改同步回数据库（磁盘 → 库）。
     * 作者可以直接改/新增以下文件，之后所有注入与续写都以最新内容为准：
     *   files/设定卡/{分类}/{名称}.md —— 改卡内容、新增卡
     *   files/正文/第N章-标题.txt    —— 改正文（摘要自动作废，下次写作前自动刷新）
     *   files/大纲/分章大纲.md        —— 改章节标题与大纲
     * 按文件修改时间判断新旧：文件比库新才采纳，绝不覆盖作者刚在 APP 里保存的内容。
     */
    suspend fun syncFromLocalFiles(projectId: Long, context: Context?): Int {
        if (context == null) return 0
        val dao = Repo.dao
        val base = try { File(context.filesDir, "novels/$projectId/files") } catch (_: Exception) { return 0 }
        if (!base.isDirectory) return 0
        var changed = 0

        // 1) 设定卡：files/设定卡/{分类}/{名称}.md
        val cardRoot = File(base, "设定卡")
        if (cardRoot.isDirectory) {
            val cards = dao.cards(projectId)
            cardRoot.listFiles()?.filter { it.isDirectory }?.forEach { catDir ->
                val cat = catDir.name
                if (cat !in CardCategories.all) return@forEach
                catDir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.forEach { f ->
                    val name = f.name.removeSuffix(".md")
                    val body = f.readText(Charsets.UTF_8).lines()
                        .dropWhile { it.startsWith("# ") || it.isBlank() }
                        .joinToString("\n").trim()
                    if (body.isBlank()) return@forEach
                    val exist = cards.firstOrNull { it.category == cat && it.name == name }
                    if (exist == null) {
                        dao.insertCard(
                            SettingCard(
                                projectId = projectId, category = cat, name = name, content = body,
                                priority = if (cat == "世界观" || cat == "人物设定" || cat == "设定圣经") 2 else 1,
                                status = if (cat == "伏笔钩子") "埋设中" else "",
                                updatedAt = f.lastModified()
                            )
                        )
                        changed++
                    } else if (f.lastModified() > exist.updatedAt && body != exist.content) {
                        dao.updateCard(exist.copy(content = body, updatedAt = f.lastModified()))
                        changed++
                    }
                }
            }
        }

        // 2) 正文：files/正文/第N章-标题.txt
        val txtDir = File(base, "正文")
        if (txtDir.isDirectory) {
            val chs = dao.chapters(projectId)
            val re = Regex("第\\s*(\\d+)\\s*章")
            txtDir.listFiles()?.filter { it.isFile && it.name.endsWith(".txt") }?.forEach { f ->
                val idx = re.find(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                val ch = chs.firstOrNull { it.chapterIndex == idx } ?: return@forEach
                val body = f.readText(Charsets.UTF_8).trim()
                if (body.isBlank() || body == ch.content) return@forEach
                if (f.lastModified() > ch.updatedAt) {
                    dao.updateChapter(
                        ch.copy(
                            content = body, wordCount = body.length, summary = "",
                            status = 2, updatedAt = f.lastModified()
                        )
                    )
                    dao.insertMessage(
                        Message(projectId = projectId, role = "tool", kind = "tool",
                            content = "🔄 检测到第${idx}章正文在本地被修改（${body.length} 字），已采用最新版本，后续写作以修改后的内容为准。")
                    )
                    changed++
                }
            }
        }

        // 3) 分章大纲：v6.7 优先读 设定卡/分章大纲/分章大纲.md；旧项目顶级 大纲/分章大纲.md 仍兼容读取
        val outlineNew = File(File(base, "设定卡" + File.separator + "分章大纲"), "分章大纲.md")
        val outlineLegacy = File(File(base, "大纲"), "分章大纲.md")
        val outlineFile = if (outlineNew.exists()) outlineNew else outlineLegacy
        if (outlineFile.isFile) {
            val chs = dao.chapters(projectId)
            val newestCh = chs.maxOfOrNull { it.updatedAt } ?: 0L
            if (outlineFile.lastModified() > newestCh) {
                val re = Regex("^##\\s*第\\s*(\\d+)\\s*章\\s*(.*)$")
                var curIdx = 0
                var curTitle = ""
                val buf = StringBuilder()
                suspend fun flush() {
                    if (curIdx == 0) return
                    val ch = chs.firstOrNull { it.chapterIndex == curIdx } ?: return
                    val outline = buf.toString().trim()
                    val title = curTitle.trim().removeSurrounding("《", "》")
                    if ((outline.isNotBlank() && outline != ch.outline) || (title.isNotBlank() && title != ch.title)) {
                        dao.updateChapter(
                            ch.copy(
                                title = title.ifBlank { ch.title },
                                outline = outline.ifBlank { ch.outline },
                                updatedAt = outlineFile.lastModified()
                            )
                        )
                        changed++
                    }
                }
                outlineFile.readText(Charsets.UTF_8).lines().forEach { line ->
                    val m = re.find(line.trim())
                    if (m != null) {
                        flush()
                        curIdx = m.groupValues[1].toIntOrNull() ?: 0
                        curTitle = m.groupValues[2]
                        buf.setLength(0)
                    } else if (curIdx > 0 && !line.trim().startsWith("#")) {
                        buf.appendLine(line)
                    }
                }
                flush()
            }
        }
        return changed
    }

    /**
     * v6.4：写正文的硬性前置门槛（writeNextChapter / startAutoWrite 都要过这道门）。
     * 返回 null = 全部就绪可以开写；否则返回缺失说明。
     * 检测到缺什么会先自动补什么（并全程在聊天里播报进度），补不齐绝不放行。
     */
    suspend fun ensurePreconditions(projectId: Long, context: Context? = null): String? {
        val dao = Repo.dao
        val project = dao.project(projectId) ?: return "项目不存在"

        // v6.5：先把作者在本地文件里的修改同步回库（磁盘→库），再合并重复的单卡分类
        try { syncFromLocalFiles(projectId, context) } catch (_: Exception) { }
        try { mergeDuplicateSingles(projectId) } catch (_: Exception) { }

        // 1) 设定卡：八类核心一张都不能少
        suspend fun missingCats(): List<String> {
            val have = dao.cards(projectId).map { it.category }.toSet()
            return REQUIRED_CATS.filter { it !in have }
        }
        var missing = missingCats()
        if (missing.isNotEmpty()) {
            dao.insertMessage(
                Message(projectId = projectId, role = "tool", kind = "tool",
                    content = "🧱 还不能开写：设定卡还没建全（缺：${missing.joinToString("、")}）。正在自动补全全部设定卡…")
            )
            val inspiration = buildString {
                append("书名《${project.title}》")
                if (project.genre.isNotBlank()) append("，类型：${project.genre}")
                append("。")
                if (project.description.isNotBlank()) append("创作方向：${project.description}")
                else append("请依据书名与类型展开完整设定。")
            }
            val err = generateCardsFromInspire(projectId, inspiration, context)
            if (err != null) return "设定卡未建全（缺：${missing.joinToString("、")}），自动补全失败：$err"
            missing = missingCats()
            if (missing.isNotEmpty()) {
                return "设定卡仍缺少：${missing.joinToString("、")}。必须先用 addCard 把这些卡补全，才能开始写正文。"
            }
        }

        // 2) 分章大纲：下一章必须有标题+剧情要点，否则写的章节没有标题
        val chs = dao.chapters(projectId)
        val nextCh = chs.firstOrNull { it.content.isBlank() }
        if (nextCh != null && nextCh.outline.isBlank()) {
            dao.insertMessage(
                Message(projectId = projectId, role = "tool", kind = "tool",
                    content = "🧭 还不能开写：第${nextCh.chapterIndex}章还没有大纲。正在自动生成分章大纲（每章标题+剧情核心）…")
            )
            val err = ensureOutlines(projectId, context = context)
            if (err != null) return "分章大纲生成失败：$err"
        }

        // 3) 分卷大纲卡：设定卡里必须有「分卷大纲」（含旧项目升级补建）
        val hasVol = dao.cards(projectId).any { it.name == "分卷大纲" }
        if (!hasVol) syncVolumeOutlineCard(projectId, context)

        // 4) v6.6：分章大纲卡：设定卡里必须有「分章大纲」（含旧项目升级补建）
        val hasChOutline = dao.cards(projectId).any { it.name == "分章大纲" }
        if (!hasChOutline) syncChapterOutlineCard(projectId, context)

        return null
    }

    /**
     * v6.3：把章节标题+大纲核心按卷整理成「分卷大纲」设定卡。
     * 用户要求"设定卡里面要包含分卷大纲"——这样在设定卡页面也能看到全书骨架，
     * 同时写章时会作为必发卡注入，600 章也不跑偏。
     */
    suspend fun syncVolumeOutlineCard(projectId: Long, context: Context? = null) {
        val dao = Repo.dao
        val cards = dao.cards(projectId)
        val withOutline = dao.chapters(projectId).filter { it.outline.isNotBlank() || it.title.isNotBlank() }
        if (withOutline.isEmpty()) return

        val perVolume = 20
        val volumes = withOutline.chunked(perVolume)
        val md = buildString {
            volumes.forEachIndexed { vi, list ->
                val from = list.first().chapterIndex
                val to = list.last().chapterIndex
                appendLine("【第${vi + 1}卷】第${from}~${to}章")
                list.forEach { ch ->
                    val title = ch.title.ifBlank { "未命名" }
                    val core = ch.outline.replace(Regex("\\s+"), " ").take(40)
                    appendLine("  第${ch.chapterIndex}章《$title》：${core.ifBlank { "（待补大纲）" }}")
                }
                appendLine()
            }
        }

        // v6.7：分卷大纲归位「全书大纲」类（之前错放在辅助设定），文件放 设定卡/全书大纲/
        val exist = cards.firstOrNull { it.name == "分卷大纲" }
        val text = md.trim()
        if (exist != null) dao.updateCard(exist.copy(category = "全书大纲", content = text, priority = 2))
        else dao.insertCard(
            SettingCard(projectId = projectId, category = "全书大纲", name = "分卷大纲", content = text, priority = 2)
        )
        try {
            dir(context, projectId, "设定卡/全书大纲")?.let { d ->
                File(d, safeName("分卷大纲") + ".md").writeText("# 全书大纲 · 分卷大纲\n\n$text\n", Charsets.UTF_8)
            }
            // v6.7：清理旧位置的重复文件（辅助设定/ 下的旧分卷大纲）
            dir(context, projectId, "设定卡/辅助设定")?.let { d -> File(d, "分卷大纲.md").delete() }
        } catch (_: Exception) { }
    }

    /**
     * v6.6：把分章大纲（每章标题+剧情核心）整卡存进设定卡「辅助设定/分章大纲」。
     * 用户要求：设定卡里必须有分章大纲，章节标题也从这里调取，生成时不漏掉。
     * 写章注入只取该卡的窗口（前两章+当前章+后一章），不再整卡注入。
     */
    suspend fun syncChapterOutlineCard(projectId: Long, context: Context? = null) {
        val dao = Repo.dao
        val withOutline = dao.chapters(projectId).filter { it.outline.isNotBlank() || it.title.isNotBlank() }
        if (withOutline.isEmpty()) return

        val md = buildString {
            appendLine("分章大纲 = 每章的标题与剧情核心。写作时章节标题一律以此为准。")
            appendLine()
            withOutline.forEach { ch ->
                val title = ch.title.ifBlank { "未命名" }
                val core = ch.outline.replace(Regex("\\s+"), " ").take(80)
                appendLine("第${ch.chapterIndex}章《$title》：${core.ifBlank { "（待补大纲）" }}")
            }
        }
        val text = md.trim()

        val exist = dao.cards(projectId).firstOrNull { it.name == "分章大纲" }
        if (exist != null) dao.updateCard(exist.copy(category = "全书大纲", content = text, priority = 2))
        else dao.insertCard(
            SettingCard(projectId = projectId, category = "全书大纲", name = "分章大纲", content = text, priority = 2)
        )
        try {
            // v6.7：分章大纲文件夹建在设定卡文件夹里：设定卡/分章大纲/分章大纲.md
            dir(context, projectId, "设定卡/分章大纲")?.let { d ->
                File(d, "分章大纲.md").writeText("# 全书大纲 · 分章大纲（章节标题以此为准）\n\n$text\n", Charsets.UTF_8)
            }
            // v6.7：清理旧位置的重复文件（辅助设定/ 与顶级 大纲/ 下的旧分章大纲）
            dir(context, projectId, "设定卡/辅助设定")?.let { d -> File(d, "分章大纲.md").delete() }
            dir(context, projectId, "大纲")?.let { d -> File(d, "分章大纲.md").delete() }
        } catch (_: Exception) { }
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

        // v6.5：单卡分类先去重（老版本可能堆出两张世界观），再开始生成
        mergeDuplicateSingles(projectId)

        // v6.7：先检索已有设定卡——八类齐全就全部沿用，绝不重复生成；缺哪类才补哪类
        val preHave = dao.cards(projectId)
        val preNeed = required.filter { cat -> preHave.none { c -> c.category == cat } }
        if (preNeed.isEmpty()) {
            val haveNames = preHave.filter { it.category in required.toSet() }
                .groupBy { it.category }.entries.joinToString("、") { "${it.key}×${it.value.size}" }
            dao.insertMessage(
                Message(projectId = projectId, role = "tool", kind = "tool",
                    content = "🔍 设定卡检索：已建齐（$haveNames），全部沿用现有设定，不重复生成。\n" +
                        "要改哪张卡直接说，例如「修改世界观里的力量体系」。")
            )
            return null
        }
        if (preNeed.size < required.size) {
            val haveList = preHave.filter { it.category in required.toSet() }
                .groupBy { it.category }.keys.joinToString("、")
            dao.insertMessage(
                Message(projectId = projectId, role = "tool", kind = "tool",
                    content = "🔍 设定卡检索：已有（${haveList.ifBlank { "无" }}）；还缺：${preNeed.joinToString("、")}——只补缺的，已有的不重做。")
            )
        }

        // v6.5：内容多一次保存不完就分多轮——每轮开始前播报「已保存好哪些/还缺哪些」，
        // 自动继续补全，作者不用盯着；已保存的部分绝不重做。
        while (pass < 4) {
            val have = dao.cards(projectId)
            val need = required.filter { cat -> have.none { c -> c.category == cat } }
            if (need.isEmpty()) break
            pass++
            if (pass > 1) {
                val savedList = have.groupBy { it.category }
                    .entries.joinToString("、") { "${it.key}×${it.value.size}" }
                dao.insertMessage(
                    Message(projectId = projectId, role = "tool", kind = "tool",
                        content = "📋 设定保存进度：已保存 $savedList。\n⚠️ 还缺：${need.joinToString("、")}——继续生成中，已保存的不会重做。")
                )
            }
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

        // v6.5：收尾播报——保存好了哪些、还缺哪些（内容都在设定卡里，写作时自动注入，聊天不刷全文）
        if (count > 0) {
            val have2 = dao.cards(projectId)
            val stillMissing = required.filter { cat -> have2.none { c -> c.category == cat } }
            val savedList = have2.filter { it.category in required }.groupBy { it.category }
                .entries.joinToString("、") { "${it.key}×${it.value.size}" }
            val msg = if (stillMissing.isEmpty())
                "✅ 设定卡已全部建齐并保存：$savedList。内容都在设定卡和项目文件里，写正文时自动注入，聊天里不再重复刷全文。"
            else
                "⚠️ 设定卡还没建齐：已保存 $savedList；还缺：${stillMissing.joinToString("、")}。说「继续」我就接着补全缺的部分，已保存的不会重做。"
            dao.insertMessage(Message(projectId = projectId, role = "tool", kind = "tool", content = msg))
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
        // v6.2：防重复——同名同分类 → 跳过；单卡分类（世界观/主线/冲突/圣经/全书大纲/剧情进度）
        // 一类只允许一张，模型又输出同类不同名的 → 更新已有卡，绝不新建
        val singleCats = setOf("世界观", "主线剧情", "核心冲突", "设定圣经", "全书大纲", "剧情进度")
        val prio = if (cat == "世界观" || cat == "人物设定" || cat == "设定圣经") 2 else 1
        val status = if (cat == "伏笔钩子") "埋设中" else ""
        val sameName = dao.findCard(projectId, cat, name)
        // v6.7：全书大纲类的回退匹配要排除 分卷大纲/分章大纲 系统卡，防止 AI 存主卡时覆盖掉它们
        val dupCard = sameName ?: if (cat in singleCats)
            dao.cards(projectId).firstOrNull { it.category == cat && it.name !in OUTLINE_CARD_NAMES } else null
        if (dupCard != null) {
            dao.updateCard(dupCard.copy(content = content, priority = prio, status = status))
            try {
                dir(context, projectId, "设定卡/$cat")?.let { d ->
                    File(d, safeName(dupCard.name) + ".md").writeText("# $cat · ${dupCard.name}\n\n$content\n", Charsets.UTF_8)
                }
            } catch (_: Exception) { }
            return 0   // 更新不算新增，避免刺激上层再补一轮
        }
        dao.insertCard(
            SettingCard(
                projectId = projectId,
                category = cat,
                name = name,
                content = content,
                priority = prio,
                status = status
            )
        )
        // 落盘 files/设定卡/{分类}/{名称}.md
        try {
            dir(context, projectId, "设定卡/$cat")?.let { d ->
                File(d, safeName(name) + ".md").writeText("# $cat · $name\n\n$content\n", Charsets.UTF_8)
            }
        } catch (_: Exception) { }
        // v6.5：只报「保存好了哪些」，不再把卡片全文刷进聊天——
        // 内容完整保存在设定卡和项目文件里，写正文时自动注入，想看随时去设定卡页
        dao.insertMessage(
            Message(
                projectId = projectId, role = "tool", kind = "tool",
                content = "✅ 已保存设定卡：$cat / $name（${content.length} 字）"
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
            val charCards = cards.filter { it.category == "人物设定" }
            append(" · 人物卡 ${charCards.size} 张已完整注入${if (charCards.isNotEmpty()) "（" + charCards.joinToString("、") { it.name } + "）" else ""}")
            val worldCards = cards.filter { it.category == "世界观" || it.category == "设定圣经" }
            append(" · 世界观/圣经 ${worldCards.size} 张完整注入${if (worldCards.isNotEmpty()) "（" + worldCards.joinToString("、") { it.name } + "）" else ""}")
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
        var lastSave = 0L
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
                // v6.5：边生成边保存——每 3 秒把已生成部分写进章节库 + 正文文件，中途断了也不丢
                if (live.length > 500 && now - lastSave > 3000) {
                    lastSave = now
                    dao.updateChapter(ch0.copy(content = live.toString(), wordCount = live.length, updatedAt = now))
                    try {
                        dir(context, project.id, "正文")?.let { d ->
                            File(d, safeName("第${ch0.chapterIndex}章-$liveTitle") + ".txt")
                                .writeText(live.toString(), Charsets.UTF_8)
                        }
                    } catch (_: Exception) { }
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

        // v6.8.1：摘要 + 伏笔回收 + 剧情进度卡抽成公共函数（重写/润色替换正文后也走同一套，不再断链）
        ch = regenerateSummary(cfg, dao, project, ch)
    }

    /**
     * v6.8.1：重生成章节摘要 + 伏笔回收 + 剧情进度卡。
     * 之前 rewriteChapter / chapterTask(replace=true) 重写正文后 summary 置空且从不重新生成，
     * 导致该章从此不进「前情摘要」（下一章断链）、剧情进度卡不更新；统一走这里修复。
     */
    suspend fun regenerateSummary(cfg: ApiConfig, dao: NovelDao, project: Project, ch0: Chapter): Chapter {
        var ch = ch0
        try {
            val sumReply = AiClient.chat(
                cfg,
                listOf(
                    ChatMsg("system", "你是小说编辑，只输出被要求的内容。"),
                    ChatMsg(
                        "user",
                        "请用120字以内概括以下章节的剧情要点（含出场人物、关键事件、新埋的伏笔）。" +
                            "若本章明确回收了之前的伏笔，最后另起一行，用【回收伏笔】开头列出名称；否则不要输出该行。\n" +
                            "第${ch.chapterIndex}章《${ch.title}》正文：\n${ch.content.take(4000)}"
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
            if (summary.isNotBlank()) {
                ch = ch.copy(summary = summary)
                dao.updateChapter(ch)
            }
            if (recovered.isNotBlank()) {
                dao.cards(project.id)
                    .filter { it.category == "伏笔钩子" && it.status != "已回收" && recovered.contains(it.name) }
                    .forEach { dao.updateCard(it.copy(status = "已回收", updatedAt = System.currentTimeMillis())) }
            }
        } catch (_: Exception) {
            // 摘要失败不影响正文
        }
        // 自动维护「剧情进度」卡
        if (ch.summary.isNotBlank()) {
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
        return ch
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
        val newCh = ch.copy(
            content = content,
            wordCount = content.length,
            status = 1,
            summary = "",
            updatedAt = System.currentTimeMillis()
        )
        dao.updateChapter(newCh)
        // v6.8.1：重写后把新正文同步到本地文件（之前只改库，本地文件滞留旧版）
        try {
            val ctx = Repo.app
            if (ctx != null) {
                dir(ctx, ch.projectId, "正文")?.let { d ->
                    File(d, safeName("第${newCh.chapterIndex}章-${newCh.title.ifBlank { "未命名" }}") + ".txt")
                        .writeText(content + "\n", Charsets.UTF_8)
                }
            }
        } catch (_: Exception) { }
        // v6.8.1：重新生成摘要 + 伏笔回收 + 剧情进度卡（之前 summary 置空不再生成，前情摘要断链）
        regenerateSummary(cfg, dao, project, newCh)
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
            val newCh = ch.copy(
                content = out,
                wordCount = out.length,
                status = 1,
                summary = "",
                updatedAt = System.currentTimeMillis()
            )
            dao.updateChapter(newCh)
            // v6.8.1：替换正文后同步本地文件 + 重新生成摘要/剧情进度卡（之前断链）
            try {
                val ctx = Repo.app
                if (ctx != null) {
                    dir(ctx, projectId, "正文")?.let { d ->
                        File(d, safeName("第${newCh.chapterIndex}章-${newCh.title.ifBlank { "未命名" }}") + ".txt")
                            .writeText(out + "\n", Charsets.UTF_8)
                    }
                }
            } catch (_: Exception) { }
            regenerateSummary(cfg, dao, project, newCh)
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

                // v6.4：硬门槛——设定卡八类+分章大纲+分卷大纲不齐全，先自动补全再开写
                state.value = state.value.copy(currentChapter = "检查设定卡与大纲…")
                val gateErr = WriterEngine.ensurePreconditions(projectId, context)
                if (gateErr != null) { log(gateErr); return@launch }
                log("设定卡与大纲已就绪")

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
