package com.lele.novelmaster.data

import android.content.Context
import com.lele.novelmaster.engine.GenerationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    /** 会话项目子文件夹（正文/大纲/设定卡…），context 为空时返回 null（仅跳过落盘，不影响写作）。
     *  v6.9.32：create=false 用于清理/读取旧路径——只定位不 mkdirs，避免清出空文件夹（用户踩坑：多出「大纲」文件夹） */
    private fun dir(context: Context?, pid: Long, sub: String, create: Boolean = true): File? {
        if (context == null) return null
        return try {
            val base = File(context.filesDir, "novels/$pid/files/$sub")
            if (create) base.mkdirs()
            base
        } catch (e: Exception) { null }
    }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifBlank { "未命名" }

    /** v6.9.41：注入偏好读取（无全局上下文时用默认值：摘要0章、窗口前2章） */
    private fun sumCount() = Repo.app?.let { InjectPrefs.summaryCount(it) } ?: 0
    private fun winPrev() = Repo.app?.let { InjectPrefs.windowPrev(it) } ?: 2

    /** v6.9.41：把一张设定卡写回项目文件 files/设定卡/{分类}/{名}.md——改卡必须同步落盘（用户踩坑：体检修复只进了库，项目文件夹里还是旧内容） */
    fun exportCardFile(projectId: Long, card: SettingCard, context: Context?) {
        if (context == null) return
        try {
            val d = dir(context, projectId, "设定卡/${card.category}") ?: return
            File(d, safeName(card.name) + ".md").writeText("# ${card.category} · ${card.name}\n\n${card.content}\n", Charsets.UTF_8)
        } catch (_: Exception) { }
    }

    /** v6.9.41：全部设定卡同步到项目文件——只写「文件不存在或文件不比卡新且内容不一致」的，绝不覆盖作者改了还没导入的新文件 */
    suspend fun syncAllCardsToFiles(projectId: Long, context: Context?): Int {
        if (context == null) return 0
        var n = 0
        for (c in Repo.dao.cards(projectId)) {
            try {
                val d = dir(context, projectId, "设定卡/${c.category}") ?: continue
                val f = File(d, safeName(c.name) + ".md")
                val body = "# ${c.category} · ${c.name}\n\n${c.content}\n"
                val stale = !f.exists() || (f.lastModified() <= c.updatedAt &&
                    (runCatching { f.readText(Charsets.UTF_8) != body }.getOrDefault(true)))
                if (stale) { f.writeText(body, Charsets.UTF_8); n++ }
            } catch (_: Exception) { }
        }
        return n
    }

    /** v6.9.41：归一化名去重——「慕昭（魔尊/反派）」和「慕昭（魔尊_反派）」这类只差符号的重复卡合并成最早一张
     *  （灵感生成写文件用了 safeName 把 / 转成 _，随后文件同步按精确名匹配建出重复卡；历史遗留全在这里清） */
    suspend fun dedupeNormalized(projectId: Long): Int {
        val dao = Repo.dao
        var merged = 0
        for (cat in CardCategories.all) {
            val groups = dao.cards(projectId).filter { it.category == cat }
                .groupBy { Prompts.normCardName(it.name).ifBlank { it.name } }
            for ((_, g) in groups) {
                if (g.size <= 1) continue
                val keep = g.minByOrNull { it.id } ?: continue
                g.filter { it.id != keep.id }.forEach { dao.deleteCard(it); merged++ }
            }
        }
        return merged
    }

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

    /** 补齐缺失的分章大纲（只处理 from..to 范围内）；结尾同步「分章大纲」设定卡并清理已废弃的分卷大纲 */
    // v6.9.38：补大纲并发闸门——聊天指令/设定卡页/章节页/灵感落地四个入口共用，同时只允许一个在跑，防并发重复烧 token
    private val outlineGate = java.util.concurrent.atomic.AtomicBoolean(false)

    // v6.9.39：多入口 AI 任务引擎级并发闸门
    // inspireGate：灵感设定（聊天灵感/设定卡页灵感分析/写章自动补全设定 三入口互斥）
    private val inspireGate = java.util.concurrent.atomic.AtomicBoolean(false)
    // chapterGates：同章正文任务（编辑器AI重写/聊天重写/润色/扩写/补写/发布打磨/自动写作写章 按 书id:章号 互斥）
    private val chapterGates = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()

    private fun chapterGate(pid: Long, idx: Int) =
        chapterGates.computeIfAbsent("$pid:$idx") { java.util.concurrent.atomic.AtomicBoolean(false) }

    suspend fun ensureOutlines(projectId: Long, from: Int = 1, to: Int = Int.MAX_VALUE, context: Context? = null): String? {
        if (!outlineGate.compareAndSet(false, true)) return "分章大纲正在补齐中，请等当前补齐完成"
        return try { ensureOutlinesInner(projectId, from, to, context) } finally { outlineGate.set(false) }
    }

    private suspend fun ensureOutlinesInner(projectId: Long, from: Int = 1, to: Int = Int.MAX_VALUE, context: Context? = null): String? {
        val dao = Repo.dao
        val project = dao.project(projectId) ?: return "项目不存在"
        val cards = dao.cards(projectId)
        val chapters = dao.chapters(projectId)
        val missing = chapters.filter { it.outline.isBlank() && it.chapterIndex in from..to }

        if (missing.isNotEmpty()) {
            // v6.9.41：大纲任务可用「大纲专用模型」（后台AI模型设置里分块配置），未绑定则回落本书/全局模型
            val cfg = TaskModels.apiFor(context ?: Repo.app, projectId, TaskModels.OUTLINE)
                ?: return "请先在【AI模型】中添加并启用一个模型"
            // v6.9.41：批次并行（3路并发）——之前 300 章要串行跑 30 批，每批都要等上一批，太慢；
            // 现在同时跑 3 批，速度约 3 倍。进度实时上报（设定卡页可见），聊天里仍按批播报
            // v6.9.43：第一批先跑定基调——其余批次全部注入开篇大纲作为承接基准
            //（此前并行批各拿同一份旧快照互相失忆，是「后面的章忘记前面的章」的根因之一）
            val batches = missing.sortedBy { it.chapterIndex }.chunked(10)
            val sem = Semaphore(3)
            val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
            var seed: List<Chapter> = emptyList()
            if (batches.isNotEmpty()) {
                val first = batches.first()
                val f = first.first().chapterIndex
                val t = first.last().chapterIndex
                val user = Prompts.buildOutlineUser(project, cards, chapters, f, t, first.size)
                val reply = AiClient.chat(
                    cfg,
                    listOf(ChatMsg("system", Prompts.OUTLINE_SYSTEM), ChatMsg("user", user)),
                    temperature = 0.7,
                    maxTokens = 6000
                )
                applyOutlines(dao, chapters, reply)
                val done = doneCount.addAndGet(first.size)
                com.lele.novelmaster.engine.AppTasks.setProgress("outline:$projectId", "🧭 正在生成分章大纲 $done/${missing.size} 章…")
                dao.insertMessage(
                    Message(projectId = projectId, role = "tool", kind = "tool",
                        content = "🧭 分章大纲进度：已生成 $done/${missing.size} 章…")
                )
                seed = dao.chapters(projectId)
                    .filter { it.chapterIndex in f..t && it.outline.isNotBlank() }
                    .sortedBy { it.chapterIndex }.takeLast(3)
            }
            coroutineScope {
                for (batch in batches.drop(1)) {
                    launch {
                        sem.withPermit {
                            val f = batch.first().chapterIndex
                            val t = batch.last().chapterIndex
                            val user = Prompts.buildOutlineUser(project, cards, chapters, f, t, batch.size, seed)
                            val reply = AiClient.chat(
                                cfg,
                                listOf(ChatMsg("system", Prompts.OUTLINE_SYSTEM), ChatMsg("user", user)),
                                temperature = 0.7,
                                maxTokens = 6000
                            )
                            applyOutlines(dao, chapters, reply)
                            val done = doneCount.addAndGet(batch.size)
                            com.lele.novelmaster.engine.AppTasks.setProgress("outline:$projectId", "🧭 正在生成分章大纲 $done/${missing.size} 章…")
                            // v6.9.44：并行批次完成顺序随机，不再显示「第f~t章」区间（区间乱序让作者误以为大纲生成乱序；
                            // 内容始终按章号写入，与完成顺序无关），只报累计进度，数字天然递增
                            dao.insertMessage(
                                Message(projectId = projectId, role = "tool", kind = "tool",
                                    content = "🧭 分章大纲进度：已生成 $done/${missing.size} 章…")
                            )
                        }
                    }
                }
            }

            // v6.9.5：漏网验证——AI 偶尔少写几行导致部分章仍无大纲（用户多次踩坑"漏掉分章大纲"）。
            // 重查一遍，仍缺的按 5 章小批次自动补一轮，补完播报最终结果
            val chaptersNow = dao.chapters(projectId)
            val stillMissing = chaptersNow.filter { it.outline.isBlank() && it.chapterIndex in from..to }
            if (stillMissing.isNotEmpty()) {
                dao.insertMessage(
                    Message(projectId = projectId, role = "tool", kind = "tool",
                        content = "🔍 检测到 ${stillMissing.size} 章大纲仍缺失（第${stillMissing.first().chapterIndex}~${stillMissing.last().chapterIndex}章），自动补齐中…")
                )
                for (batch in stillMissing.chunked(5)) {
                    val f = batch.first().chapterIndex
                    val t = batch.last().chapterIndex
                    // v6.9.43：逐批取最新快照——前一小批刚补的大纲立刻成为下一批的上下文
                    val snap = dao.chapters(projectId)
                    val user = Prompts.buildOutlineUser(project, cards, snap, f, t, batch.size)
                    val reply = AiClient.chat(
                        cfg,
                        listOf(ChatMsg("system", Prompts.OUTLINE_SYSTEM), ChatMsg("user", user)),
                        temperature = 0.7,
                        maxTokens = 3000
                    )
                    applyOutlines(dao, chaptersNow, reply)
                }
                val left = dao.chapters(projectId).count { it.outline.isBlank() && it.chapterIndex in from..to }
                dao.insertMessage(
                    Message(projectId = projectId, role = "tool", kind = "tool",
                        content = if (left == 0) "✅ 分章大纲补齐完成：范围内所有章节均有标题与剧情核心"
                        else "⚠️ 仍有 $left 章大纲缺失，开写前系统会再次尝试自动补齐"
                )
                )
            }

            // v6.9.41：标题修复——AI 输出漏了《标题》：段时该章大纲写进去了但标题空着（用户踩坑「生成了又没标题」）。
            // 一次小调用统一补齐：只起标题，便宜且快
            val needTitle = dao.chapters(projectId).filter {
                it.chapterIndex in from..to && it.outline.isNotBlank() && it.title.isBlank()
            }
            if (needTitle.isNotEmpty()) {
                val ask = buildString {
                    appendLine("为以下各章各起一个2~8字的章节标题（网文风格、有钩子感）。")
                    appendLine("严格每章一行输出「第N章《标题》」，不要任何解释、不要遗漏：")
                    needTitle.forEach { appendLine("第${it.chapterIndex}章：${it.outline.take(60)}") }
                }
                val reply = AiClient.chat(
                    cfg,
                    listOf(ChatMsg("system", Prompts.OUTLINE_SYSTEM), ChatMsg("user", ask)),
                    temperature = 0.5,
                    maxTokens = 2000
                )
                val tre = Regex("^第\\s*(\\d+)\\s*章\\s*[《\\[]([^》\\]]+)[》\\]]")
                for (line in reply.lines()) {
                    val m = tre.find(line.trim()) ?: continue
                    val idx = m.groupValues[1].toIntOrNull() ?: continue
                    val t = m.groupValues[2].trim()
                    val ch = needTitle.firstOrNull { it.chapterIndex == idx } ?: continue
                    if (t.isNotBlank()) dao.updateChapter(ch.copy(title = t))
                }
            }
        }

        // v6.7：分章大纲文件只放 设定卡/分章大纲/ 文件夹；v6.9：分卷大纲废弃，清理旧卡
        syncChapterOutlineCard(projectId, context)
        try { cleanupVolumeOutline(projectId, context) } catch (_: Exception) { }
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
    /** v6.9：分卷大纲与分章大纲内容重复，用户要求去掉——分卷大纲升级时自动删除（见 cleanupVolumeOutline） */
    val OUTLINE_CARD_NAMES = setOf("分章大纲")

    suspend fun mergeDuplicateSingles(projectId: Long): Int {
        val dao = Repo.dao
        var merged = 0
        for (cat in SINGLE_CATS) {
            val list = dao.cards(projectId).filter { it.category == cat }
            if (list.size <= 1) continue
            val keep: List<SettingCard> = if (cat == "全书大纲") {
                // v6.9：全书大纲类允许 主卡+分章大纲 各留一张共存，其余同名的仍只留最早一张
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
        // v6.9.41：全分类归一化名去重（人物设定等非单卡分类的符号差异重名卡）
        merged += dedupeNormalized(projectId)
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
                    // v6.9.41：匹配加 safeName 口径——AI 存卡时文件名经过 safeName 转义（/ → _），
                    // 之前精确匹配会为同一个人物建出第二张卡（用户踩坑：人物设定里慕昭/温书衡各出现两次）
                    val exist = cards.firstOrNull { it.category == cat && (it.name == name || safeName(it.name) == name) }
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

        // 3) v6.9：分卷大纲已废弃（与分章大纲重复），升级时自动清理旧卡与旧文件
        try { cleanupVolumeOutline(projectId, context) } catch (_: Exception) { }

        // 4) v6.6：分章大纲卡：设定卡里必须有「分章大纲」（含旧项目升级补建）
        val hasChOutline = dao.cards(projectId).any { it.name == "分章大纲" }
        if (!hasChOutline) syncChapterOutlineCard(projectId, context)

        return null
    }

    /**
     * v6.9：分卷大纲与分章大纲内容高度重复，用户要求去掉分卷大纲、只留全书大纲主卡+分章大纲。
     * 升级时自动删除库里所有「分卷大纲」卡与本地文件，全书骨架由分章大纲卡承担。
     */
    suspend fun cleanupVolumeOutline(projectId: Long, context: Context? = null) {
        val dao = Repo.dao
        dao.cards(projectId).filter { it.name == "分卷大纲" }.forEach { dao.deleteCard(it) }
        try {
            dir(context, projectId, "设定卡/全书大纲", create = false)?.let { d -> File(d, "分卷大纲.md").delete() }
            dir(context, projectId, "设定卡/辅助设定", create = false)?.let { d -> File(d, "分卷大纲.md").delete() }
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
            // v6.7：清理旧位置的重复文件（辅助设定/ 与顶级 大纲/ 下的旧分章大纲）——只定位不创建（v6.9.32）
            dir(context, projectId, "设定卡/辅助设定", create = false)?.let { d -> File(d, "分章大纲.md").delete() }
            dir(context, projectId, "大纲", create = false)?.let { d -> File(d, "分章大纲.md").delete() }
        } catch (_: Exception) { }
    }

    /** 解析AI返回的大纲行并写回章节 */
    private suspend fun applyOutlines(dao: NovelDao, chapters: List<Chapter>, reply: String) {
        // v6.9.32 加固：1) 只认行首「第N章」——AI 说明文字里出现「第N章」不再被当成大纲写进章节；
        // 2) 章号后含「缺失/说明/如下」等 meta 词的行直接跳过（用户踩坑：说明文字被写进大纲）；
        // 3) 项目符号续行并入上一章（支持多行大纲）；4) 结束时统一写回
        val regex = Regex("^第\\s*(\\d+)\\s*章")
        val meta = Regex("缺失|暂缺|待补|已补|补齐|补全|说明|如下|示例|格式|汇总|整理")
        class Acc(val ch: Chapter, var title: String, var outline: String)
        val acc = LinkedHashMap<Int, Acc>()
        var last: Acc? = null
        for (raw in reply.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = regex.find(line)
            if (m == null) {
                val a = last ?: continue
                if (line.length in 4..150 && !meta.containsMatchIn(line) &&
                    (line.startsWith("·") || line.startsWith("•") || line.startsWith("-") ||
                        line.startsWith("*") || line.startsWith("——") || Regex("^\\d+[.、)]").containsMatchIn(line))
                ) {
                    a.outline = (a.outline + "；" + line.trimStart('·', '•', '-', '*', ' ', '—')).take(400)
                }
                continue
            }
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            val ch = chapters.firstOrNull { it.chapterIndex == idx } ?: continue
            var rest = line.substring(m.range.last + 1).trim()
            val sep = rest.indexOfFirst { it == '：' || it == ':' }
            val headPart = if (sep >= 0) rest.substring(0, sep) else rest.take(16)
            if (meta.containsMatchIn(headPart)) continue
            var title = ch.title
            var outline = ch.outline
            if (sep >= 0) {
                val t = rest.substring(0, sep).trim().removeSurrounding("《", "》")
                if (t.isNotBlank()) title = t
                outline = rest.substring(sep + 1).trim()
            } else {
                outline = rest.removeSurrounding("《", "》").trim()
            }
            if (outline.isNotBlank() || title != ch.title) {
                last = Acc(ch, title, outline)
                acc[idx] = last
            }
        }
        for (a in acc.values) {
            if (a.outline.isNotBlank() || a.title != a.ch.title) {
                dao.updateChapter(a.ch.copy(title = a.title, outline = a.outline))
            }
        }
    }

    /**
     * v6.9.30 单章大纲兜底：重写/润色/补写等手动指定章的场景，该章大纲空白时先补一条再动手，
     * 避免正文脱离主线自由发挥。复用批次大纲的 prompt（全书大纲卡预算注入+窗口），单次小调用。
     */
    private suspend fun ensureOneOutline(project: Project, ch: Chapter, context: Context? = null): Chapter {
        if (ch.outline.isNotBlank()) return ch
        val dao = Repo.dao
        val cfg = Repo.apiFor(project.id) ?: return ch
        val cards = dao.cards(project.id)
        val chapters = dao.chapters(project.id)
        dao.insertMessage(
            Message(projectId = project.id, role = "tool", kind = "tool",
                content = "🧭 第${ch.chapterIndex}章还没有大纲，先自动补一条再动手（避免正文偏离主线）…")
        )
        return try {
            val user = Prompts.buildOutlineUser(project, cards, chapters, ch.chapterIndex, ch.chapterIndex, 1)
            val reply = AiClient.chat(
                cfg,
                listOf(ChatMsg("system", Prompts.OUTLINE_SYSTEM), ChatMsg("user", user)),
                temperature = 0.7,
                maxTokens = 1000
            )
            applyOutlines(dao, chapters, reply)
            syncChapterOutlineCard(project.id, context)
            val fresh = dao.chapters(project.id).firstOrNull { it.chapterIndex == ch.chapterIndex } ?: ch
            if (fresh.outline.isBlank()) {
                dao.insertMessage(
                    Message(projectId = project.id, role = "tool", kind = "tool",
                        content = "⚠️ 第${ch.chapterIndex}章大纲自动补全失败，本次按前情摘要续写，建议稍后用「AI 补全缺失大纲」补齐")
                )
            }
            fresh
        } catch (_: Exception) {
            ch // 生成失败不阻塞原任务
        }
    }

    /**
     * 灵感分析：自动生成一整套设定卡（并全部落盘到 files/设定卡/）
     * v5.9：改为流式生成——AI 每写完一行就立即落库落盘并在聊天里显示一张，
     *      边输出边保存边显示，不再"卡很久然后突然全部显示"。
     */
    suspend fun generateCardsFromInspire(projectId: Long, inspiration: String, context: Context? = null): String? {
        // v6.9.39：灵感设定并发闸门——聊天灵感/设定卡页灵感分析/写章自动补全设定 三入口互斥，防重复生成互相覆盖
        if (!inspireGate.compareAndSet(false, true)) return "设定灵感分析正在进行中，请等当前分析完成"
        return try { generateCardsFromInspireInner(projectId, inspiration, context) } finally { inspireGate.set(false) }
    }

    private suspend fun generateCardsFromInspireInner(projectId: Long, inspiration: String, context: Context? = null): String? {
        val dao = Repo.dao
        val cfg = Repo.apiFor(projectId) ?: return "请先在【AI模型】中添加并启用一个模型"
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
        // v6.9.6：「写作禁忌」由写后自检系统维护，灵感分析/设定生成不得创建或覆盖
        if (name == "写作禁忌") return 0
        val content = parts.drop(2).joinToString("｜").trim()
        if (content.length < 4) return 0
        // v6.2：防重复——同名同分类 → 跳过；单卡分类（世界观/主线/冲突/圣经/全书大纲/剧情进度）
        // 一类只允许一张，模型又输出同类不同名的 → 更新已有卡，绝不新建
        val singleCats = setOf("世界观", "主线剧情", "核心冲突", "设定圣经", "全书大纲", "剧情进度")
        val prio = if (cat == "世界观" || cat == "人物设定" || cat == "设定圣经") 2 else 1
        val status = if (cat == "伏笔钩子") "埋设中" else ""
        val sameName = dao.findCard(projectId, cat, name)
            // v6.9.41：归一化名兜底——AI 这次输出「慕昭（魔尊_反派）」、上次存的是「慕昭（魔尊/反派）」也认得是同一张
            ?: dao.cards(projectId).firstOrNull { it.category == cat && Prompts.normCardName(it.name) == Prompts.normCardName(name) }
        // v6.9：全书大纲类的回退匹配要排除 分章大纲 系统卡，防止 AI 存主卡时覆盖掉它们
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
        // v6.9.39：同章正文任务闸门——自动写作写到某章时，若该章正被编辑器AI重写/聊天润色等占用，
        // 抛错让本书自动写作立即中止（避免两路同时写同一章互相覆盖）；等该任务完成后可重启自动写作
        val gate = chapterGate(project.id, ch0.chapterIndex)
        if (!gate.compareAndSet(false, true)) throw IllegalStateException(
            "第${ch0.chapterIndex}章正在被其他AI任务处理（编辑器AI重写/聊天润色等），自动写作已中止；请等该任务完成后再启动自动写作"
        )
        try {
            writeOneInner(project, cfg, dao, ch0, context)
        } finally { gate.set(false) }
    }

    private suspend fun writeOneInner(project: Project, cfg: ApiConfig, dao: NovelDao, ch0: Chapter, context: Context? = null) {
        val cards = dao.cards(project.id)
        val chapters = dao.chapters(project.id)
        val messages = Prompts.buildChapterMessages(project, cards, chapters, ch0, sumCount(), winPrev())

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

        // v6.9：写后轻量自检——用「全书体检」的对照原理只查本章（详见 selfCheckChapter）
        // v6.9.47：受后台「每章自动体检」开关控制（手动单章自检不受影响）
        if (autoCheckOn(context)) try { selfCheckChapter(cfg, dao, project, ch, context) } catch (_: Exception) { }
    }

    /** v6.9.47：每章自动体检是否开启（AI后台功能开关；ctx 为空时兜底用全局 Context，无 Context 视为开启） */
    private fun autoCheckOn(ctx: Context?): Boolean {
        val c = ctx ?: Repo.app ?: return true
        return InjectPrefs.autoCheck(c)
    }

    /**
     * v6.9：写后轻量自检（全书体检的单章版）。
     * 全书体检的原理 = 拿设定卡对照各章摘要找矛盾；这里拿两类标准对照本章正文：
     *  1) 硬约束设定（人物/世界观/圣经）——体质/灵根/功法/称谓/规则；
     *  2) v6.9.4：前 5 章摘要——时间线/人数/物品/事件衔接（全书体检最有价值的部分）。
     * 约 2500~3500 token（正文成本的 ~18%），不浪费。
     * AI 输出「原文=>修正」修正对，程序只做精确匹配替换——替换不动就只播报问题，绝不瞎改。
     * v6.9.9：返回状态 "pass"|"fixed"|"suspect"；quiet=true 时「通过」不插消息（全书逐章自检防刷屏），修复/疑似仍播报。
     */
    suspend fun selfCheckChapter(cfg: ApiConfig, dao: NovelDao, project: Project, ch0: Chapter, context: Context? = null, quiet: Boolean = false): String {
        val cards = dao.cards(project.id)
        val hard = cards.filter { it.category == "人物设定" || it.category == "世界观" || it.category == "设定圣经" }
        if (hard.isEmpty()) {
            recordSelfCheck(project.id, ch0.chapterIndex, "pass", context)
            return "pass"
        }
        val settingBlock = run {
            // v6.9.25：人物卡按本章正文出场过滤（长篇人物多时自检注入随卡数线性膨胀）——
            // 出场人物/必发人物全量对照；未出场人物只留名单供称谓/人数对照，设定省略
            val body = ch0.content
            fun bareName(n: String) = n.substringAfterLast('·', n).substringAfterLast('•', n).trim()
            val present = hard.filter { it.category != "人物设定" || it.priority == 2 || body.contains(bareName(it.name)) }
            val absent = hard.filter { it.category == "人物设定" && it.priority != 2 && !body.contains(bareName(it.name)) }
            present.joinToString("\n") { "【${it.category}·${it.name}】${it.content.take(300)}" } +
                (if (absent.isNotEmpty())
                    "\n【本章未出场人物（仅供称谓/人数对照）】" + absent.joinToString("、") { bareName(it.name) }
                else "")
        }
        // v6.9.4：前 5 章摘要——检查章与章之间的矛盾（时间线/称谓/人数/物品/事件）
        val recentBlock = dao.chapters(project.id)
            .filter { it.chapterIndex < ch0.chapterIndex && it.summary.isNotBlank() }
            .takeLast(5)
            .joinToString("\n") { "第${it.chapterIndex}章《${it.title}》：${it.summary.take(80)}" }
        // v6.9.11：本章大纲——正文漏写关键事件/剧情走向跑偏也是「AI 不听」，纳入自检对照
        val outlineBlock = if (ch0.outline.isNotBlank()) "【本章大纲（正文应完成它）】\n${ch0.outline.take(250)}\n\n" else ""
        val reply = AiClient.chat(
            cfg,
            listOf(
                ChatMsg("system", "你是小说一致性校对员，只按格式输出，不解释。"),
                ChatMsg("user",
                    "【设定（硬性标准）】\n$settingBlock\n\n" +
                        (if (recentBlock.isNotBlank()) "【前情摘要（本章不得与之矛盾）】\n$recentBlock\n\n" else "") +
                        outlineBlock +
                        "【第${ch0.chapterIndex}章正文】\n${ch0.content.take(3000)}\n\n" +
                        "任务：只检查正文是否矛盾——1) 违背设定（体质/灵根/境界/功法/称谓/世界观规则）；\n" +
                        "2) 与前情摘要冲突（时间线颠倒、人数/物品对不上、事件衔接矛盾）；\n" +
                        "3) 遗漏或违背本章大纲：大纲要求的关键事件没写、剧情走向明显跑偏（轻微措辞/详略差异不算）。\n" +
                        "无矛盾：只输出【通过】。\n" +
                        "有矛盾：每行一条，格式：原文片段=>修正后片段（原文片段必须是正文里连续出现的文字，最多3条，改最小的范围）。\n" +
                        "只允许修正矛盾本身，严禁借机改写剧情、增删情节或润色其他文字；拿不准的地方不要动。\n" +
                        "若是大纲关键事件遗漏等无法局部修改的结构性问题，输出一行：重大偏离：<一句话说明>。"
                    )
            ),
            temperature = 0.2,
            maxTokens = 1024
        )
        val text = reply.trim()
        if (text.contains("【通过】") || text.isBlank()) {
            if (!quiet) dao.insertMessage(Message(projectId = project.id, role = "tool", kind = "tool",
                content = "✅ 第${ch0.chapterIndex}章自检通过：与设定、前情及本章大纲均无矛盾"))
            recordSelfCheck(project.id, ch0.chapterIndex, "pass", context)
            return "pass"
        }
        var content = ch0.content
        val fixes = mutableListOf<String>()
        text.lines().filter { it.contains("=>") }.take(5).forEach { line ->
            val (bad, good) = line.split("=>", limit = 2).map { it.trim().trim('`', '"') }
            if (bad.length in 2..120 && good.isNotBlank()) {
                if (content.contains(bad)) {
                    content = content.replace(bad, good)
                    fixes.add("「${bad.take(30)}」→「${good.take(30)}」")
                } else if (bad.length >= 4) {
                    // v6.9.10：宽松匹配兜底——AI 给的片段常因空白/标点差异对不上精确匹配
                    val loose = Regex(bad.map { Regex.escape(it.toString()) }.joinToString("\\s*"))
                    if (loose.containsMatchIn(content)) {
                        content = loose.replace(content) { good }
                        fixes.add("「${bad.take(30)}」→「${good.take(30)}」（宽松匹配）")
                    }
                }
            }
        }
        if (fixes.isNotEmpty()) {
            // v6.9.10：修复前备份原文到「正文备份」目录（供撤销恢复），绝不无备份地改正文
            try {
                dir(context, project.id, "正文备份")?.let { d ->
                    File(d, safeName("第${ch0.chapterIndex}章-${ch0.title.ifBlank { "未命名" }}") + "-备份" + System.currentTimeMillis() + ".txt")
                        .writeText(ch0.content + "\n", Charsets.UTF_8)
                }
            } catch (_: Exception) { }
            dao.updateChapter(ch0.copy(content = content, wordCount = content.length, updatedAt = System.currentTimeMillis()))
            try {
                dir(context, project.id, "正文")?.let { d ->
                    File(d, safeName("第${ch0.chapterIndex}章-${ch0.title.ifBlank { "未命名" }}") + ".txt")
                        .writeText(content + "\n", Charsets.UTF_8)
                }
            } catch (_: Exception) { }
            // v6.9.2：把修正过的矛盾模式记入「写作禁忌」卡（必发注入），后续章节不再重犯同类错误
            try { rememberTaboo(dao, project.id, fixes) } catch (_: Exception) { }
            dao.insertMessage(Message(projectId = project.id, role = "tool", kind = "tool",
                content = "🔧 第${ch0.chapterIndex}章自检发现 ${fixes.size} 处与设定矛盾，已自动修正：\n" +
                    fixes.joinToString("\n") { "· $it" }))
            recordSelfCheck(project.id, ch0.chapterIndex, "fixed", context)
            return "fixed"
        } else {
            // AI 报了矛盾但给出的片段无法精确匹配——只播报，让作者决定是否重写
            // v6.9.46：kind=selfcheck_fix——聊天页渲染「确认修复」按钮，点击后按问题清单+大纲重写本章并保存
            dao.insertMessage(Message(projectId = project.id, role = "tool", kind = "selfcheck_fix",
                content = "⚠️ 第${ch0.chapterIndex}章自检发现疑似设定矛盾，请复核：\n${text.take(300)}\n\n👇 点下方按钮可按大纲重写本章并修复以上问题（原正文自动备份，可撤销）。"))
            recordSelfCheck(project.id, ch0.chapterIndex, "suspect", context)
            return "suspect"
        }
    }

    /** v6.9.13：把单章自检结果追加到「自检记录/记录.txt」（每行：章号|状态|时间戳），供自检进度查询 */
    private fun recordSelfCheck(pid: Long, chapterIndex: Int, status: String, context: Context?) {
        try {
            dir(context, pid, "自检记录")?.let { d ->
                File(d, "记录.txt").appendText("$chapterIndex|$status|${System.currentTimeMillis()}\n", Charsets.UTF_8)
            }
        } catch (_: Exception) { }
    }

    /** v6.9.13：读取自检记录，返回每章最新状态 Map<章号, pass|fixed|suspect>（后写覆盖先写=最新） */
    fun readSelfCheckRecord(pid: Long, context: Context?): Map<Int, String> {
        return try {
            val f = dir(context, pid, "自检记录", create = false)?.let { File(it, "记录.txt") } ?: return emptyMap()
            if (!f.exists()) return emptyMap()
            f.readLines(Charsets.UTF_8).mapNotNull { l ->
                val p = l.split("|")
                p.getOrNull(0)?.toIntOrNull()?.let { idx -> idx to (p.getOrNull(1) ?: "pass") }
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    /**
     * v6.9.54：发布级润色（去AI味终修）——整章重写文字表达，剧情/事件/对话内容/设定不得增删改。
     * 长度校验（原文 60%~160%）兜底，异常润色稿直接放弃保安全；改前备份、改后落盘正文文件。
     * 返回是否采用。
     */
    private suspend fun polishForPublish(cfg: ApiConfig, dao: NovelDao, project: Project, ch: Chapter, context: Context?): Boolean {
        val orig = ch.content
        val reply = try {
            AiClient.chat(
                cfg,
                listOf(
                    ChatMsg("system", "你是资深网文编辑，做发布前终修。只输出润色后的完整正文，不要任何解释、标题或说明。"),
                    ChatMsg("user",
                        "润色下面这章小说正文：只优化文字表达，剧情走向、事件、对话内容、人物设定一律不得增删改。\n" +
                            "总原则：宁可少改，不可错改——任何拿不准、可能改变剧情或语义的地方，保持原文不动；你只做文字层面的机械替换与删减，不做创作。\n" +
                            "严禁事项：不得新增事件、人物、物件或伏笔；不得删除任何情节；不得改变对话的含义与结论；不得改动人物的能力/称谓/关系；不得合并或拆分段落情节。\n" +
                            "去AI味硬要求（仅限替换句式与删套话）：\n" +
                            "1) 删掉总结式结尾段（结尾必须落在具体动作/对话/悬念上）；\n" +
                            "2) 删「仿佛/宛如/似乎」式堆砌比喻，删「眸中闪过一丝」「嘴角勾起一抹」「空气仿佛凝固」套路句；\n" +
                            "3) 长短句交替、节奏有变化，不写等长段落；\n" +
                            "4) 对话口语化、符合人物身份，去书面腔（只改说法，不改对话传达的信息）；\n" +
                            "5) 心理用动作与细节呈现，删「他知道/他明白/殊不知」式作者旁白；\n" +
                            "6) 不滥用成语与四字排比，形容词少而准。\n" +
                            "字数与原文相当（上下不超过两成）。\n" +
                            "【第${ch.chapterIndex}章正文】\n$orig")
                ),
                temperature = 0.6,
                maxTokens = AiClient.MAX_TOKENS_HUGE
            )
        } catch (_: Exception) { return false }
        val t = reply.trim()
        if (t.isBlank() || t.length < orig.length * 0.6 || t.length > orig.length * 1.6) return false
        // 改前备份（供撤销恢复）
        try {
            dir(context, project.id, "正文备份")?.let { d ->
                File(d, safeName("第${ch.chapterIndex}章-${ch.title.ifBlank { "未命名" }}") + "-备份" + System.currentTimeMillis() + ".txt")
                    .writeText(orig + "\n", Charsets.UTF_8)
            }
        } catch (_: Exception) { }
        dao.updateChapter(ch.copy(content = t, wordCount = t.length, updatedAt = System.currentTimeMillis()))
        try {
            dir(context, project.id, "正文")?.let { d ->
                File(d, safeName("第${ch.chapterIndex}章-${ch.title.ifBlank { "未命名" }}") + ".txt")
                    .writeText(t + "\n", Charsets.UTF_8)
            }
        } catch (_: Exception) { }
        return true
    }

    /**
     * v6.9.9：全书逐章自检修复——把「全书体检」（只查不修）和「单章自检」（能修）合二为一。
     * 对每章依次跑 selfCheckChapter：发现矛盾自动修正并沉淀禁忌卡；每 3 章插一次进度播报。
     * 成本：每章一次小调用（~18% 正文），N 章约等于 0.2N 章正文的 token。
     * v6.9.54：新增「同步润色去AI味」开关（InjectPrefs.polishWithCheck，默认开）——
     * 每章查完矛盾（状态非 suspect）后顺手按发布级文风整章润色，跑完一次即直达可发布状态。
     */
    suspend fun fullSelfCheck(cfg: ApiConfig, dao: NovelDao, project: Project, context: Context? = null): Pair<String?, String> {
        val chapters = dao.chapters(project.id).filter { it.content.isNotBlank() }.sortedBy { it.chapterIndex }
        if (chapters.isEmpty()) return "还没有已写章节" to ""
        var passed = 0
        var fixed = 0
        var suspect = 0
        var polished = 0
        val fixedChapters = mutableListOf<Int>()
        val doPolish = polishOn(context)
        chapters.forEachIndexed { i, ch0 ->
            val before = ch0.content
            val status = try {
                selfCheckChapter(cfg, dao, project, ch0, context, quiet = true)
            } catch (_: Exception) { "suspect" }
            when (status) {
                "pass" -> passed++
                "fixed" -> { fixed++; fixedChapters.add(ch0.chapterIndex) }
                else -> suspect++
            }
            // v6.9.54：同步润色去AI味（疑似矛盾章不润色，等作者处理完再说）；用修完的最新正文
            if (status != "suspect" && doPolish) {
                val fresh = dao.chapter(ch0.id) ?: ch0
                if (fresh.content.isNotBlank()) {
                    val ok = try { polishForPublish(cfg, dao, project, fresh, context) } catch (_: Exception) { false }
                    if (ok) polished++
                }
            }
            if ((i + 1) % 3 == 0 || i + 1 == chapters.size) {
                dao.insertMessage(Message(projectId = project.id, role = "tool", kind = "tool",
                    content = "🔬 全书自检进度：已检查 ${i + 1}/${chapters.size} 章" +
                        (if (fixed > 0) "，已修复 $fixed 章" else "") +
                        (if (doPolish) "，已润色 $polished 章" else "")))
            }
        }
        val summary = "🔬 全书自检完成（共 ${chapters.size} 章）：\n" +
            "· ✅ 无矛盾：$passed 章\n" +
            "· 🔧 已自动修正：$fixed 章" + (if (fixedChapters.isNotEmpty()) "（第${fixedChapters.joinToString("、")}章）" else "") + "\n" +
            "· ⚠️ 疑似矛盾待复核：$suspect 章\n" +
            (if (doPolish) "· ✨ 已同步润色去AI味：$polished 章（发布级文风，改前均有备份）\n" else "") +
            "修正模式已沉淀进【写作禁忌】卡，后续章节不再重犯同类错误。" +
            (if (doPolish && suspect == 0) "\n📚 全书已达可发布状态：可直接到【导出与发布】导出全书 TXT。" else "")
        dao.insertMessage(Message(projectId = project.id, role = "tool", kind = "tool", content = summary))
        return null to summary
    }

    /** v6.9.54：全书自检修时同步润色去AI味是否开启（ctx 为空时兜底全局 Context，无 Context 视为开启） */
    private fun polishOn(ctx: Context?): Boolean {
        val c = ctx ?: Repo.app ?: return true
        return InjectPrefs.polishWithCheck(c)
    }

    /**
     * v6.9.10：撤销第 N 章最近一次修改——用「正文备份」目录里该章最新的备份覆盖回去
     * （v6.9.14 起备份来源包括：自检修复、补写、润色、扩写、改风格、钩子等所有替换正文的操作）。
     * 恢复后同步本地正文文件并重新生成摘要。返回 (err, msg)。
     */
    suspend fun restoreLastSelfCheck(cfg: ApiConfig, dao: NovelDao, project: Project, chapterIndex: Int, context: Context?): Pair<String?, String> {
        val ch = dao.chapters(project.id).firstOrNull { it.chapterIndex == chapterIndex }
            ?: return "第 $chapterIndex 章不存在" to ""
        val d = dir(context, project.id, "正文备份") ?: return "无法访问备份目录（本章可能从未被自动修正过）" to ""
        val prefix = safeName("第${chapterIndex}章-${ch.title.ifBlank { "未命名" }}") + "-备份"
        val latest = d.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
            ?.maxByOrNull { it.name }
            ?: return "第 ${chapterIndex} 章没有修改备份（该章可能从未被自动修改过）" to ""
        val restored = latest.readText(Charsets.UTF_8).trim()
        if (restored.isBlank()) return "备份文件为空，放弃恢复" to ""
        val newCh = ch.copy(content = restored, wordCount = restored.length, updatedAt = System.currentTimeMillis())
        dao.updateChapter(newCh)
        try {
            dir(context, project.id, "正文")?.let { dd ->
                File(dd, safeName("第${chapterIndex}章-${ch.title.ifBlank { "未命名" }}") + ".txt")
                    .writeText(restored + "\n", Charsets.UTF_8)
            }
        } catch (_: Exception) { }
        regenerateSummary(cfg, dao, project, newCh)
        // v6.9.18：撤销后记录 restored——该章视为未检（徽标消失、进度按未检统计）
        recordSelfCheck(project.id, chapterIndex, "restored", context)
        return null to "↩️ 已把第 ${chapterIndex} 章恢复到最近一次修改（自检修复/补写/润色等）前的版本（备份：${latest.name}），摘要已重新生成。"
    }

    /**
     * v6.9.2：把自检修正过的矛盾模式累积记入「辅助设定·写作禁忌」卡（priority=2 必发注入）。
     * 每条一行（最多 10 条，超出淘汰最旧的），写章系统提示会要求 AI 绝不再犯这些模式。
     */
    private suspend fun rememberTaboo(dao: NovelDao, projectId: Long, fixes: List<String>) {
        if (fixes.isEmpty()) return
        val existing = dao.cards(projectId).firstOrNull { it.category == "辅助设定" && it.name == "写作禁忌" }
        val lines = existing?.content?.lines()?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        for (f in fixes) {
            val line = "· $f"
            if (lines.none { it == line }) lines.add(line)
        }
        while (lines.size > 10) lines.removeAt(0)
        val text = lines.joinToString("\n")
        if (existing != null) {
            dao.updateCard(existing.copy(content = text, priority = 2, updatedAt = System.currentTimeMillis()))
        } else {
            dao.insertCard(
                SettingCard(projectId = projectId, category = "辅助设定", name = "写作禁忌", content = text, priority = 2)
            )
        }
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
                            "若本章新埋了之前没有的伏笔，每条另起一行，用【新埋伏笔】开头，格式：名称：一句话说明；否则不要输出。\n" +
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
                .filter { !it.contains("回收伏笔") && !it.contains("新埋伏笔") }
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
            // v6.9.15：新埋伏笔自动建档（去重）——保证「伏笔体检」有完整数据，不依赖 AI 主动建卡
            sumReply.lines().filter { it.contains("新埋伏笔") }.forEach { line ->
                val body = line.substringAfter("】").trim()
                val name = body.substringBefore("：").substringBefore(":").trim().take(30)
                if (name.isNotBlank()) {
                    val desc = body.substringAfter("：", "").substringAfter(":", "").trim()
                    val exists = dao.cards(project.id).any { it.category == "伏笔钩子" && it.name == name }
                    if (!exists) {
                        dao.insertCard(
                            SettingCard(
                                projectId = project.id, category = "伏笔钩子", name = name,
                                content = desc.ifBlank { "第${ch.chapterIndex}章埋设" },
                                status = "埋设中"
                            )
                        )
                        dao.insertMessage(Message(projectId = project.id, role = "tool", kind = "tool",
                            content = "🪝 新伏笔已记录：【$name】（${desc.ifBlank { "第${ch.chapterIndex}章埋设" }}），可随时说「伏笔体检」查看埋收状态。"))
                    }
                }
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
        val ch = dao.chapter(chapterId) ?: throw IllegalStateException("章节不存在")
        val project = dao.project(ch.projectId) ?: throw IllegalStateException("项目不存在")
        // v6.9.34：本书绑定了独立模型就用它，无则回落全局启用；v6.9.46：正文重写可走「任务专用模型」
        val cfg = TaskModels.apiFor(Repo.app, ch.projectId, TaskModels.CHAPTER) ?: throw IllegalStateException("请先在【AI模型】中启用一个模型")
        val cards = dao.cards(ch.projectId)
        val messages = Prompts.continueMessages(project, cards, ch, currentText)
        val out = AiClient.chat(cfg, messages, temperature = 0.9)
        return currentText + "\n" + out.trim()
    }

    /** 编辑器：AI重写整章 */
    suspend fun rewriteChapter(chapterId: Long): String? {
        val dao = Repo.dao
        val ch0 = dao.chapter(chapterId) ?: return "章节不存在"
        // v6.9.39：同章正文任务闸门——编辑器AI重写/聊天重写与润色扩写等、自动写作写章互斥
        val gate = chapterGate(ch0.projectId, ch0.chapterIndex)
        if (!gate.compareAndSet(false, true)) return "第 ${ch0.chapterIndex} 章正在AI处理中，请等当前任务完成"
        try {
            return rewriteChapterInner(chapterId)
        } finally { gate.set(false) }
    }

    private suspend fun rewriteChapterInner(chapterId: Long): String? {
        val dao = Repo.dao
        val ch0 = dao.chapter(chapterId) ?: return "章节不存在"
        val project = dao.project(ch0.projectId) ?: return "项目不存在"
        // v6.9.34：本书绑定了独立模型就用它，无则回落全局启用；v6.9.46：正文重写可走「任务专用模型」
        val cfg = TaskModels.apiFor(Repo.app, ch0.projectId, TaskModels.CHAPTER) ?: return "请先在【AI模型】中启用一个模型"
        // v6.9.30：大纲空白先补一条再动手（AI 不脱轨）
        val ch = ensureOneOutline(project, ch0, Repo.app)
        val cards = dao.cards(ch.projectId)
        val chapters = dao.chapters(ch.projectId)
        val messages = Prompts.buildChapterMessages(project, cards, chapters, ch, sumCount(), winPrev()).toMutableList()
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
        // v6.9.1：重写后同样过一遍写后自检——用户用「重写本章」修复写错设定时也享受矛盾自动修正
        // v6.9.47：受后台「每章自动体检」开关控制
        if (autoCheckOn(null)) try {
            val ctx = Repo.app
            if (ctx != null) selfCheckChapter(cfg, dao, project, newCh, ctx)
        } catch (_: Exception) { }
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
        replace: Boolean,
        task: String = "" // v6.9.41：非空时走「任务专用模型」（如 TaskModels.POLISH 发布打磨）
    ): Pair<String?, String> { // (err, output)
        // v6.9.39：同章正文任务闸门——聊天润色/扩写/补写/发布打磨等与编辑器AI重写、自动写作写章互斥
        val gate = chapterGate(projectId, chapterIndex)
        if (!gate.compareAndSet(false, true)) return "第 $chapterIndex 章正在AI处理中，请等当前任务完成" to ""
        return try { chapterTaskInner(projectId, chapterIndex, instruction, replace, task) } finally { gate.set(false) }
    }

    private suspend fun chapterTaskInner(
        projectId: Long,
        chapterIndex: Int,
        instruction: String,
        replace: Boolean,
        task: String = ""
    ): Pair<String?, String> { // (err, output)
        val dao = Repo.dao
        val project = dao.project(projectId) ?: return "项目不存在" to ""
        // v6.9.34：本书绑定了独立模型就用它，无则回落全局启用；v6.9.41：打磨类任务可用「打磨专用模型」
        val cfg = (if (task.isNotBlank()) com.lele.novelmaster.data.TaskModels.apiFor(Repo.app, projectId, task) else null)
            ?: Repo.apiFor(projectId)
            ?: return "请先在【AI模型】中启用一个模型" to ""
        val chapters = dao.chapters(projectId)
        val ch0 = chapters.firstOrNull { it.chapterIndex == chapterIndex }
            ?: return "第 $chapterIndex 章不存在" to ""
        // v6.9.30：大纲空白先补一条再动手（AI 不脱轨）；重取 chapters 保证窗口里是补好的大纲
        val ch = ensureOneOutline(project, ch0, Repo.app)
        val cards = dao.cards(projectId)
        val messages = Prompts.buildChapterMessages(project, cards, dao.chapters(projectId), ch, sumCount(), winPrev()).toMutableList()
        messages.add(ChatMsg("user", instruction))
        val out = cleanBody(AiClient.chat(cfg, messages, temperature = 0.8).trim(), chapterIndex, ch.title)
        if (out.isBlank()) return "AI返回为空" to ""
        if (replace && out.length >= 300) {
            // v6.9.14：替换正文（润色/扩写/补写/改风格/钩子等）前先备份原文——撤销工具可直接恢复
            try {
                dir(Repo.app, projectId, "正文备份")?.let { d ->
                    File(d, safeName("第${ch.chapterIndex}章-${ch.title.ifBlank { "未命名" }}") + "-备份" + System.currentTimeMillis() + ".txt")
                        .writeText(ch.content + "\n", Charsets.UTF_8)
                }
            } catch (_: Exception) { }
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
            // v6.9.1：润色/扩写/改文风等替换正文后同样过写后自检
            // v6.9.47：受后台「每章自动体检」开关控制
            if (autoCheckOn(null)) try {
                val ctx2 = Repo.app
                if (ctx2 != null) selfCheckChapter(cfg, dao, project, newCh, ctx2)
            } catch (_: Exception) { }
        }
        return null to out
    }

    /** 非章节类自由任务（起名/简介/体检等）：带核心设定上下文问 AI */
    suspend fun freeTask(projectId: Long, instruction: String, task: String = ""): Pair<String?, String> {
        val dao = Repo.dao
        val project = dao.project(projectId) ?: return "项目不存在" to ""
        // v6.9.34：本书绑定了独立模型就用它，无则回落全局启用；v6.9.41：体检/瘦身类可用「体检专用模型」
        val cfg = (if (task.isNotBlank()) TaskModels.apiFor(Repo.app, projectId, task) else null)
            ?: Repo.apiFor(projectId)
            ?: return "请先在【AI模型】中启用一个模型" to ""
        val cards = dao.cards(projectId)
        val sys = buildString {
            appendLine("你是资深网文主编。基于以下设定完成任务，只输出要求的内容。")
            // v6.9.42：体检报告出现大段英文思考——所有自由任务强制全程简体中文、禁止输出思考过程
            appendLine("全程只用简体中文输出，严禁英文，严禁输出思考过程/内心分析，直接给结果。")
            appendLine("《${project.title}》类型：${project.genre}")
            // v6.9.23：分章大纲卡不整卡注入（600章大纲可达数万字，写作走大纲窗口）；核心卡改预算截断，注入总量有界
            val core = cards.filter {
                it.name != "分章大纲" && (it.priority == 2 || it.category in CardCategories.KEY_CATS || it.category == "人物设定")
            }
            if (task == TaskModels.CHECK) {
                // v6.9.46：设定体检/修复必须看到【全部设定卡的完整原文】——此前核心卡 3200/单卡600 截断，
                // AI 自述「看不到设定圣经完整内容，无法输出完整卡，只能凭感觉填」（用户实测踩坑）。
                // 分章大纲卡仍不注入（数万字，且体检禁止改它，只对照统计）。
                val all = cards.filter { it.name != "分章大纲" }
                if (all.isNotEmpty()) {
                    appendLine("【全部设定卡完整原文（你可以据此修改除分章大纲外的任意卡，输出整卡时必须以原文为准，严禁凭印象补写）】")
                    all.forEach { appendLine("【${it.category}·${it.name}】\n${it.content}") }
                }
            } else if (core.isNotEmpty()) appendLine(Prompts.budgetCardBlock(core, budget = 3200, perCard = 600))
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
 * v6.9.34：多书并行——每本书一个独立任务，可同时启动多本书互不影响；
 * 状态按 projectId 存在 Map 里，前台服务/聊天卡/自动写作页都按 pid 取自己那本书的状态；
 * 每本书可用各自绑定的 AI 模型（Repo.apiFor），停止也可以只停某一本书。
 */
object AutoWriteManager {

    /** 单本书的写作任务状态 */
    data class TaskState(
        val projectId: Long = 0,
        val running: Boolean = false,
        val currentChapter: String = "",
        val done: Int = 0,
        val total: Int = 0,
        val logs: List<String> = emptyList()
    )

    /** 全局状态：所有书的任务快照（并行键值） */
    data class Progress(val tasks: Map<Long, TaskState> = emptyMap())

    val state = MutableStateFlow(Progress())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>()

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    fun task(pid: Long): TaskState = state.value.tasks[pid] ?: TaskState(projectId = pid)

    fun isRunning(pid: Long): Boolean = state.value.tasks[pid]?.running == true

    fun anyRunning(): Boolean = state.value.tasks.values.any { it.running }

    private fun update(pid: Long, f: (TaskState) -> TaskState) {
        state.update { st ->
            val cur = st.tasks[pid] ?: TaskState(projectId = pid)
            st.copy(tasks = st.tasks + (pid to f(cur)))
        }
    }

    private fun log(pid: Long, msg: String) = update(pid) {
        it.copy(logs = (listOf("${fmt.format(Date())} $msg") + it.logs).take(120))
    }

    /** v6.9.55：余额/欠费/鉴权类错误识别——这类错误重试无意义，自动写作应立即停止并明确提示 */
    private fun isBillingError(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("401") || m.contains("402") || m.contains("payment required") ||
            m.contains("insufficient") || m.contains("balance") || m.contains("quota") ||
            m.contains("unauthorized") || m.contains("invalid api key") ||
            msg.contains("余额") || msg.contains("欠费") || msg.contains("额度") ||
            msg.contains("充值") || msg.contains("未实名") || msg.contains("已冻结") || msg.contains("鉴权")
    }

    fun start(projectId: Long, from: Int, to: Int, context: Context? = null) {
        if (isRunning(projectId)) return
        // v6.9.33：同步置位再拉起前台服务（服务观察 state，避免启动时读到 running=false 误发"已结束"通知）
        update(projectId) { it.copy(running = true, currentChapter = "", done = 0, total = 0) }
        GenerationService.start(context)
        jobs[projectId] = scope.launch {
            try {
                val dao = Repo.dao
                val project = dao.project(projectId) ?: run { log(projectId, "项目不存在"); return@launch }
                // v6.9.34：本书绑定了独立模型就用它，否则回落全局启用的接口；v6.9.46：正文生成可走「任务专用模型」
                val cfg = TaskModels.apiFor(context ?: Repo.app, projectId, TaskModels.CHAPTER)
                    ?: run { log(projectId, "未启用AI模型：请到【AI模型】添加并设为启用"); return@launch }
                log(projectId, "开始自动写作《${project.title}》第$from~$to 章（模型：${cfg.model}）")

                // v6.4：硬门槛——设定卡八类+分章大纲不齐全，先自动补全再开写
                update(projectId) { it.copy(currentChapter = "检查设定卡与大纲…") }
                val gateErr = WriterEngine.ensurePreconditions(projectId, context)
                if (gateErr != null) { log(projectId, gateErr); return@launch }
                log(projectId, "设定卡与大纲已就绪")

                // 1) 先补齐范围内缺失的大纲
                update(projectId) { it.copy(currentChapter = "生成缺失大纲中…") }
                val err = WriterEngine.ensureOutlines(projectId, from, to, context)
                if (err != null) { log(projectId, err); return@launch }
                log(projectId, "大纲已就绪")

                // 2) 逐章写作
                val targets = dao.chapters(projectId).filter { it.chapterIndex in from..to }
                update(projectId) { it.copy(total = targets.size, done = 0) }
                var done = 0
                var fail = 0
                // v6.9.55：失败/未完成章清单——重跑自动写作时这些章会被补写，已有正文的章自动跳过
                val failedChapters = mutableListOf<Int>()
                var stoppedByBilling = false
                loop@ for (t in targets) {
                    if (!isActive || !isRunning(projectId)) break
                    update(projectId) { it.copy(currentChapter = "第${t.chapterIndex}章 ${t.title.ifBlank { "写作中…" }}") }
                    val fresh0 = dao.chapter(t.id) ?: t
                    // v6.9.55：只要有正文就不覆盖（此前 status==2 才跳过，重跑会把写完未定稿的章整章重写，白烧 token 且违背「不影响写好的」）
                    if (fresh0.content.isNotBlank()) {
                        log(projectId, "跳过第${t.chapterIndex}章（已有正文，不覆盖）")
                        done++
                        update(projectId) { it.copy(done = done) }
                        continue
                    }
                    // v6.9.55：每章自动重试一次——偶发网络/限流失败先自己重试，再不行才记为失败章继续下一章
                    var written = false
                    for (attempt in 1..2) {
                        try {
                            // 重读最新状态：上一次尝试若已把正文落盘（如体检阶段才报错），这次直接视为完成
                            val fresh = dao.chapter(t.id) ?: t
                            if (fresh.content.isNotBlank()) { written = true; break }
                            WriterEngine.writeOne(project, cfg, dao, fresh, context)
                            written = true
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val msg = e.message ?: ""
                            // v6.9.55：余额/欠费/鉴权类错误立即停止——重试无意义，用户需要先充值/换模型
                            if (isBillingError(msg)) {
                                log(projectId, "🛑 模型余额不足或鉴权失败，自动写作已停止：${msg.take(160)}")
                                log(projectId, "请充值或更换启用的AI模型后，重新开启自动写作——已写好的章节会自动跳过，只补写缺失章")
                                stoppedByBilling = true
                                break
                            }
                            if (attempt < 2) log(projectId, "⚠️ 第${t.chapterIndex}章第${attempt}次写作失败（${msg.take(120)}），自动重试…")
                            else log(projectId, "❌ 第${t.chapterIndex}章重试后仍失败：${msg.take(160)}")
                        }
                    }
                    if (stoppedByBilling) break@loop
                    if (written) {
                        fail = 0
                        done++
                        update(projectId) { it.copy(done = done) }
                        log(projectId, "✅ 第${t.chapterIndex}章完成（$done/${targets.size}）")
                    } else {
                        failedChapters.add(t.chapterIndex)
                        fail++
                        if (fail >= 3) { log(projectId, "连续失败3次，本任务已自动停止"); break }
                    }
                }
                log(projectId, "任务结束：完成 $done/${targets.size} 章" +
                    (if (failedChapters.isNotEmpty()) "；未完成：第${failedChapters.joinToString("、")}章" else ""))
                if (failedChapters.isNotEmpty()) {
                    log(projectId, "💡 重新开启自动写作即可断点续写：已写好的章节自动跳过，只补写缺失/失败的章（已有正文不会被改动）")
                }
            } finally {
                update(projectId) { it.copy(running = false, currentChapter = "") }
                jobs.remove(projectId)
            }
        }
    }

    /** 停止指定书的任务；pid 为空时停止全部并行任务 */
    fun stop(pid: Long? = null) {
        val targets = if (pid != null) listOf(pid)
        else state.value.tasks.values.filter { it.running }.map { it.projectId }
        for (p in targets) {
            if (state.value.tasks[p] == null) continue
            update(p) { it.copy(running = false) }
            jobs[p]?.cancel()
            jobs.remove(p)
        }
    }

    /**
     * v6.9.40：删除书等场景——停掉与该书相关的全部任务：自动写作、聊天生成（仅当正在生成的是这本书时）、
     * 页面长任务（灵感分析/补大纲/设定体检）。注意：聊天里对本书执行删除指令时不要用本函数
     * （ChatEngine 正 busy 在本书上，停掉会把删除指令自己掐断），那种场景用 stop(pid)+AppTasks.cancelProject(pid)。
     */
    fun stopProjectTasks(pid: Long) {
        stop(pid)
        runCatching { com.lele.novelmaster.engine.ChatEngine.stop(pid) }
        runCatching { com.lele.novelmaster.engine.AppTasks.cancelProject(pid) }
    }
}
