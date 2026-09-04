package com.lele.mobipaint

/** 提示词工程：从墨笔 PC 端同源移植（番茄/飞卢/起点网文 SOP）。 */
object Prompts {

    val CHAT_SYSTEM = (
        "你是作者的私人小说创作伙伴，像主流 AI 助手一样与作者自然、放松地聊天。"
        + "你可以：陪作者聊灵感并把它发展成完整创意、设计人物和金手指、编大纲、"
        + "也可以在作者一句话吩咐下直接写出章节正文。写作时遵循网文原则"
        + "（短段落、快节奏、爽点密集、章末留钩子），输出正文时直接给内容不要加解释。"
        + "你掌握本书全部设定资料、剧情记忆与最近对话，回答必须与之一致，绝不前后矛盾。\n"
        + "\n【设定自动沉淀（重要）】\n"
        + "设定库是这本书的长期记忆，会注入之后的每一次写作。当对话中确定了以下内容时，"
        + "你必须在回复末尾自动输出设定更新指令（可多条），不要等作者开口：\n"
        + "· 新出现或被修改的人物（姓名、性格、境界、关系等）→ 人物设定\n"
        + "· 世界观/力量体系/背景规则（如修仙境界划分、金手指规则）→ 世界观\n"
        + "· 被确认的剧情走向、分卷规划 → 大纲\n"
        + "· 其他值得长期记住的创作决定 → 随记\n"
        + "指令格式：【设定更新】分类|标题|完整新内容【/设定更新】\n"
        + "分类只允许：人物设定、世界观、大纲、随记。若标题已存在则整体覆盖该条目，否则新增。\n"
        + "注意：只沉淀确定的设定，不要把闲聊寒暄记进去；沉淀后在正文里用一句话告诉作者记了什么。\n"
        + "\n【题材判定（重要）】\n"
        + "书档案里登记的题材只是建书时的参考信息，不是定论。本书的类型、背景、世界观"
        + "永远以聊天内容为准：聊的是修仙就是修仙，聊的是都市就是都市。\n"
        + "当你从对话中确认（或变更）了本书题材，且与登记题材不一致时，在回复末尾输出：\n"
        + "【题材更新】新题材（如：仙侠·修真凡人流 / 都市异能 / 玄幻……）【/题材更新】\n"
        + "应用会自动更新书档案；并在正文里用一句话告知作者。没有明确变化就不要输出该指令。"
        )

    /** 长文本截取：保留开头与结尾。 */
    fun buildContext(content: String?, head: Int = 1200, tail: Int = 1200): String {
        val text = (content ?: "").trim()
        if (text.length <= head + tail) return text.ifEmpty { "（暂无内容）" }
        return text.take(head) + "\n……（中间省略）……\n" + text.takeLast(tail)
    }

    fun projectBrief(p: Db.Project): String =
        "【书名】${p.title}\n【登记题材】${p.genre.ifEmpty { "未定" }}" +
        "（仅建书登记参考——实际题材以剧情记忆与聊天内容为准）\n" +
        (if (p.brief.isNotBlank()) "【简介】${p.brief}\n" else "")

    fun settingsSummary(pid: Long, limit: Int = 3500): String {
        val rows = Db.listSettings(pid)
        if (rows.isEmpty()) return "（暂无设定，随聊天自动沉淀）"
        val sb = StringBuilder()
        var used = 0
        for (r in rows) {
            val line = "· [${r.cat}] ${r.title}：${r.content}\n"
            if (used + line.length > limit) break
            sb.append(line)
            used += line.length
        }
        return sb.toString()
    }

    fun recentTitles(pid: Long, n: Int = 30): String {
        val list = Db.listChapters(pid)
        if (list.isEmpty()) return "（还没有章节）"
        val tail = list.takeLast(n)
        val headMark = if (tail.size < list.size) "……（前面章节省略）……\n" else ""
        return headMark + tail.joinToString("\n") { "第${it.no}章 ${it.title}" }
    }

    /** 对话创作上下文：系统提示 + 记忆设定 + 最近章节 + 最近 12 条过滤后的对话。 */
    fun chatMessages(pid: Long, userText: String): List<Pair<String, String>> {
        val proj = Db.project(pid) ?: return listOf(Pair("user", userText))
        val lastCh = Db.listChapters(pid).lastOrNull()
        val lastTail = if (lastCh != null && lastCh.content.isNotBlank())
            buildContext(lastCh.content, head = 0, tail = 1200) else "（还没有章节）"
        val sys = (CHAT_SYSTEM + "\n\n" + projectBrief(proj)
            + "\n\n【剧情记忆与设定资料】\n" + settingsSummary(pid)
            + "\n\n【已写章节】\n" + recentTitles(pid, 30)
            + "\n\n【最新一章结尾】\n" + lastTail)
        val msgs = ArrayList<Pair<String, String>>()
        msgs.add(Pair("system", sys))
        var added = 0
        val history = Db.listChat(pid, 40)
        for (i in history.indices.reversed()) {
            if (added >= 12) break
            val m = history[i]
            val c = (m.content ?: "").trim()
            if (isNoise(m.role, c)) continue
            msgs.add(Pair(m.role, m.content))
            added++
        }
        msgs.add(Pair("user", userText))
        return msgs
    }

    /** 机器回执/噪音判定（与 PC 端过滤口径一致）。 */
    fun isNoise(role: String, c: String): Boolean {
        if (c.isEmpty()) return true
        if (c.startsWith("❌")) return true
        if (c.startsWith("（🤖") || c.startsWith("（自动连写") || c.startsWith("（请更新")) return true
        if (role == "assistant" && c.startsWith("✅ 第")) return true
        return false
    }

    /** 主线剧情记忆生成（999 章不跑偏的核心）。 */
    fun memoryPrompt(pid: Long): String {
        val proj = Db.project(pid) ?: return ""
        val titles = Db.listChapters(pid).takeLast(60)
            .joinToString("\n") { "第${it.no}章 ${it.title}（${it.words}字）" }
            .ifEmpty { "（还没有章节）" }
        return "请为这部小说更新「主线剧情记忆」（约600~900字），" +
            "这份记忆会在后续每一章写作时注入上下文，帮助 AI 写到第999章也记得住前情。\n\n" +
            "【书名】${proj.title}\n" +
            "【登记题材】${proj.genre.ifEmpty { "未定" }}（仅建书登记参考——实际题材以剧情记忆与聊天内容为准）\n\n" +
            "【设定资料】\n${settingsSummary(pid)}\n\n" +
            "【已写章节】\n$titles\n\n" +
            "【与作者的创作对话记录（本书剧情的主要来源）】\n${chatDigest(pid)}\n\n" +
            "【旧版记忆】\n${proj.memory.ifBlank { "（暂无）" }}\n\n" +
            "【铁律】\n" +
            "- 只能依据上面的设定资料、章节、对话记录提炼，严禁编造本书没有的剧情、人物、章节数或字数；\n" +
            "- 对话记录是本书剧情的首要依据：题材、背景、人物、力量体系都从中提炼（例如聊的是修仙世界，记忆就必须是修仙世界）；\n" +
            "- 若资料与旧版记忆冲突，以设定资料和对话记录为准。\n\n" +
            "要求包含：①题材与世界观 ②主线推进到第几章、当前阶段 " +
            "③已确定的关键设定与事件（按时间线）④活跃人物及其关系 " +
            "⑤未回收的伏笔与悬念 ⑥下一步剧情方向。\n" +
            "直接输出记忆正文，不要解释。"
    }

    /** 把最近的创作对话压成一段文本（排除连写回执/记忆指令/错误等噪音）。 */
    fun chatDigest(pid: Long, maxRows: Int = 50, perMsg: Int = 500, total: Int = 9000): String {
        val rows = Db.listChat(pid, maxRows)
        val lines = ArrayList<String>()
        var skipNextAssistant = false
        for (m in rows) {
            val c = (m.content ?: "").trim()
            if (m.role == "user") {
                if (c.startsWith("（请更新")) {
                    skipNextAssistant = true
                    continue
                }
                skipNextAssistant = false
            } else if (skipNextAssistant) {
                continue
            }
            if (c.isEmpty() || c.startsWith("❌")) continue
            if (c.startsWith("（🤖") || c.startsWith("（自动连写") ||
                (m.role == "assistant" && c.startsWith("✅ 第"))) continue
            val who = if (m.role == "user") "作者" else "AI"
            lines.add("$who：${c.take(perMsg)}")
        }
        val text = lines.joinToString("\n")
        return if (text.length > total) text.takeLast(total) else text.ifEmpty { "（还没有对话）" }
    }

    /** 自动连写单章提示词（约 2000 字，不低于 1500，不注水不扩写）。 */
    fun batchChapterPrompt(pid: Long, no: Int): String {
        val proj = Db.project(pid) ?: return ""
        val lastCh = if (no > 1) Db.chapter(pid, no - 1) else null
        val prevTail = if (lastCh != null) buildContext(lastCh.content, head = 0, tail = 1000)
            else "（这是第一章，从故事开始写起）"
        return "请撰写这部小说的第${no}章正文。\n\n" +
            "【书名】${proj.title}\n" +
            "【登记题材】${proj.genre.ifEmpty { "未定" }}（仅登记参考，实际以剧情记忆与聊天内容为准）\n\n" +
            "【主线剧情记忆】\n${proj.memory.ifBlank { "（暂无，按设定资料写）" }}\n\n" +
            "【人物与世界观设定】\n${settingsSummary(pid)}\n\n" +
            "【前文章节标题】\n${recentTitles(pid, 30)}\n\n" +
            "【上一章结尾】\n$prevTail\n\n" +
            "要求：\n" +
            "- 直接输出正文，以「第${no}章 标题」开头（自带本章标题）\n" +
            "- 字数约2000字（不低于1500，不要刻意注水），不要扩写或改写之前的章节\n" +
            "- 严格衔接前情与剧情记忆，人物设定一致，绝不前后矛盾\n" +
            "- 分段清晰，对话推动剧情，结尾留悬念钩子；不要输出任何解释、总结或写作说明"
    }

    /** 写作台 AI 助手系统提示（与 PC 端 WRITER_SYSTEM 同源）。 */
    val WRITER_SYSTEM = (
        "你是一位全职中文网文作者兼写作教练，深耕番茄小说、飞卢小说网、起点中文网三大平台，"
        + "熟悉各平台的文风、节奏与读者偏好。写作原则：\n"
        + "1.【黄金三章思维】开篇即冲突，前三章必须抛出金手指/悬念/爽点，忌大段背景介绍；\n"
        + "2.【节奏】短段落（每段1~3行）、多对话、快节奏，每章结尾留钩子；\n"
        + "3.【爽点密度】每章至少1个小爽点或情绪爆点，打脸/扮猪吃虎/升级感要给足；\n"
        + "4.【人设一致】严格遵循已有人物设定、世界观与力量体系，不擅自吃书；\n"
        + "5.【文字】生动口语化，画面感强，杜绝翻译腔与AI腔；\n"
        + "6. 直接输出正文内容，不要输出'好的''以下是'等废话，不要用markdown标题包裹正文。")

    private fun writerBase(pid: Long): String {
        val proj = Db.project(pid) ?: return WRITER_SYSTEM
        return (WRITER_SYSTEM + "\n\n" + projectBrief(proj)
            + "\n\n【设定资料】\n" + settingsSummary(pid))
    }

    /** AI 续写当前章节。 */
    fun continuePrompt(pid: Long, content: String, targetWords: Int): String {
        val proj = Db.project(pid) ?: return ""
        return "请为当前章节续写约${targetWords}字。\n\n" +
            "【书名】${proj.title}\n" +
            "【登记题材】${proj.genre.ifEmpty { "未定" }}（仅建书登记参考——实际题材以剧情记忆与聊天内容为准）\n\n" +
            "【人物与世界观设定】\n${settingsSummary(pid)}\n\n" +
            "【当前章节已有内容（从中间截取）】\n${buildContext(content)}\n\n" +
            "要求：\n" +
            "- 无缝衔接已有内容，人称、时态、文风保持一致\n" +
            "- 推进剧情并制造新的冲突或爽点\n" +
            "- 直接输出续写内容，不要重复已有内容"
    }

    fun polishPrompt(selection: String): String =
        "请润色下面这段小说文字，保持剧情与信息量不变，提升文笔与画面感：\n\n" +
            selection + "\n\n要求：直接输出润色后的文字，不要解释改动。"

    fun expandPrompt(selection: String): String =
        "请把下面这段小说文字扩写至约2倍篇幅，增加细节描写、心理活动与环境刻画，剧情走向不变：\n\n" +
            selection + "\n\n要求：直接输出扩写后的文字，不要解释。"

    /** 写作台自定义指令。 */
    fun editorChatPrompt(pid: Long, cid: Long, instruction: String): String {
        val proj = Db.project(pid) ?: return instruction
        val ch = Db.chapterById(cid)
        val ctx = if (ch != null) buildContext(ch.content) else "（还没有内容）"
        return ("你正在协助作者创作小说《${proj.title}》（${proj.genre.ifEmpty { "未定" }}）。\n\n"
            + "【人物与世界观设定】\n${settingsSummary(pid)}\n\n"
            + "【最近章节】\n${recentTitles(pid, 10)}\n\n"
            + "【当前章节内容】\n$ctx\n\n【作者指令】$instruction")
    }

    /** AI 大纲规划（章节管理页）。 */
    fun outlinePrompt(pid: Long, from: Int, to: Int, direction: String): String {
        val proj = Db.project(pid) ?: return ""
        val prevOutline = Db.listSettings(pid, "大纲")
            .takeLast(10)
            .joinToString("\n") { "· ${it.title}：${buildContext(it.content, 200, 0)}" }
            .ifEmpty { "（暂无）" }
        return "请为这部小说生成章节大纲，一次性给出第${from}章到第${to}章的规划。\n\n" +
            "【书名】${proj.title}\n" +
            "【题材】${proj.genre.ifEmpty { "未定" }}（登记参考，实际以剧情记忆/设定为准）\n" +
            (if (proj.brief.isNotBlank()) "【简介】${proj.brief}\n" else "") +
            "\n【已有设定】\n${settingsSummary(pid)}\n\n" +
            "【主线剧情记忆】\n${proj.memory.ifBlank { "（暂无）" }}\n\n" +
            "【前情大纲要点】\n$prevOutline\n" +
            (if (direction.isNotBlank()) "\n【作者要求的剧情方向】$direction\n" else "") +
            "\n要求：\n" +
            "- 每章一行，格式严格为：第N章|章节标题|本章剧情要点(50~100字)\n" +
            "- 剧情要有推进、有冲突、有爽点，章末留钩子\n" +
            "- 只输出大纲行，不要其他解释"
    }

    /** 写作台 AI 消息组装。 */
    fun writerMessages(pid: Long, userPrompt: String): List<Pair<String, String>> =
        listOf(Pair("system", writerBase(pid)), Pair("user", userPrompt))
}
