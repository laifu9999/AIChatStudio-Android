package com.lele.novelmaster.data

/**
 * 提示词与上下文组装器 —— 本App的"记忆核心"。
 *
 * 设计目标：写 300 章不跑题、不浪费 token。
 * 策略：
 *  1. 不发送全部历史正文，只发送「前5章摘要 + 上一章结尾600字」→ 用摘要代替全文，token 恒定不随章节数膨胀
 *  2. 设定卡按优先级与关键词相关度智能挑选：每章必发卡 + 未回收伏笔全发；常规卡按与本章大纲的关键词重合度排序取前14张
 *  3. 低频卡（priority=0）不参与注入
 */
object Prompts {

    fun cardBlock(cards: List<SettingCard>): String =
        cards.joinToString("\n") { "【${it.category}·${it.name}】${it.content}${if (it.category == "伏笔钩子") "（状态：${it.status.ifBlank { "埋设中" }}）" else ""}" }

    /** v5.6：带字数预算的设定卡注入（单卡截断，整体不超预算），保证 600 章注入恒定 ~3000 字 */
    fun budgetCardBlock(cards: List<SettingCard>, budget: Int = 1900, perCard: Int = 450): String {
        val sb = StringBuilder()
        for (c in cards) {
            val status = if (c.category == "伏笔钩子") "（状态：${c.status.ifBlank { "埋设中" }}）" else ""
            val content = if (c.content.length > perCard) c.content.take(perCard) + "…" else c.content
            val line = "【${c.category}·${c.name}】$content$status\n"
            if (sb.length + line.length > budget + perCard) break
            sb.append(line)
        }
        return sb.toString().trim()
    }

    /** 硬约束分类：无论优先级强制必发（人物/世界观/圣经是写作的一致性底线） */
    val HARD_CATS = setOf("人物设定", "世界观", "设定圣经")

    /** v6.9.41：卡名归一化——去掉文件名转义符与常见装饰符号，用于识别「慕昭（魔尊/反派）」和「慕昭（魔尊_反派）」是同一张卡 */
    fun normCardName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|（）()·．.\\s_－—~～]"), "")

    /** 按优先级与关键词相关度挑选要注入的设定卡 */
    fun selectCards(all: List<SettingCard>, focusText: String): List<SettingCard> {
        // v6.9.23：分章大纲系统卡不参与整卡注入——写章走「大纲窗口」（前两章+本章+后一章）独立注入，
        // 整卡必发在长篇里只会被预算截成开头几章（无用内容），还挤占其他卡的预算
        // v6.9.41：inject=false 的卡不参与注入（用户按卡开关）；人物等重名卡按归一化名去重（保留最早的）
        val pool = all.filter { it.name != "分章大纲" && it.inject }
        // v6.8.2/v6.8.3：硬约束分类强制必发——手动建卡默认优先级是「常规」，用户忘选必发时
        // 人物卡/世界观/圣经可能落选导致体质灵根、力量体系等硬设定丢失
        val always = pool.filter { it.priority == 2 || it.category in HARD_CATS }
        val foreshadow = pool.filter { it.category == "伏笔钩子" && it.status != "已回收" }
        val normal = pool.filter { it.priority == 1 && it.id !in always.map { a -> a.id } && it.id !in foreshadow.map { f -> f.id } }

        fun score(c: SettingCard): Int {
            var s = if (c.category in CardCategories.KEY_CATS) 1 else 0
            val text = c.name + c.content
            var hits = 0
            var i = 0
            while (i < focusText.length - 1) {
                val g = focusText.substring(i, i + 2)
                if (!g.any { it == '，' || it == '。' || it == '、' || it == ' ' || it == '：' } && text.contains(g)) hits++
                i++
            }
            return s + minOf(hits, 6)
        }

        val ranked = normal.sortedByDescending { score(it) }.take(14)
        val merged = (always + foreshadow + ranked).distinctBy { it.id }
        // v6.9.41：归一化名去重——同一分类下「名字只差 / 与 _ 等符号」的重复卡只注入最早那张
        val minIdByKey = mutableMapOf<String, Long>()
        for (c in merged) {
            val key = c.category + "|" + normCardName(c.name)
            val cur = minIdByKey[key]
            if (cur == null || c.id < cur) minIdByKey[key] = c.id
        }
        return merged.filter { minIdByKey[it.category + "|" + normCardName(it.name)] == it.id }
    }

    fun writerSystem(selected: List<SettingCard>): String = buildString {
        appendLine("你是一位顶级网络小说作家，正在按大纲逐章写作。严格遵守：")
        appendLine("1. 人物的体质、灵根、血脉、境界、功法、外貌、称谓、性格是硬性设定，必须与【人物设定】逐字一致，绝不允许自行更改或另造（例如设定为先天剑体/天灵根，就绝不能写成其他体质灵根）。世界观规则同样不得违背。")
        appendLine("2. 与前情摘要、上一章结尾自然衔接；不重复已写过的情节，不另起炉灶。")
        appendLine("3. 伏笔规则：【伏笔钩子】中状态为“埋设中”的伏笔要按计划推进；“已回收”的不可再当新伏笔。需要埋新伏笔时自然埋下。")
        appendLine("4. 每章必须有推进、有冲突、章末留钩子（悬念）。多用场景与对话（对话建议占三成以上，对话要有潜台词、能推动剧情与信息差），少干巴巴的旁白。")
        appendLine("5. 只输出正文本身：不要输出章节标题、章节号、序号、解释、总结或任何多余内容。")
        appendLine("6. 必须一次性写完整一章：从开头一路写到章末钩子，情节自然收束，绝不允许中途停笔、省略或写“未完待续/下半部分”。")
        appendLine("7. 绝不重复：不要复述前情摘要里已经写过的情节，不要写“正如前文所说”“上一章提到”这类回顾句，直接推进新的戏。")
        appendLine("8. 不写任何创作说明：不要出现“本章完”“作者有话”“（此处省略）”“由于篇幅”之类的话，写完最后一个句号就停。")
        appendLine("9. 笔法：场景、动作、对话、心理交替推进；每 300~500 字给一个新的信息点或小转折，保持张力。开头直接入戏（从场景或冲突切入），不要铺垫式开头。")
        appendLine("10. 若存在【写作禁忌】块，其中列出的都是本书写错过并被系统纠正的矛盾模式（原文=>修正），本章绝不允许再犯同样的错误。")
        appendLine("11. 去AI味（发布级硬要求）：结尾严禁写概括本章的总结段；少用“仿佛/似乎/宛如”式堆砌比喻；严禁“眸中闪过一丝XX”“嘴角勾起一抹弧度”“空气仿佛凝固了”这类高频套路句；不滥用成语与四字排比；形容词少而准，多用具体名词与动作呈现（show, don't tell）。")
        appendLine("12. 全知解说克制：不写“他知道”“他明白”“殊不知”式的作者旁白替人物解释心理，改由动作、对话与细节自然流露；同一句式、同一个口头禅、同一种比喻在一章内不要反复出现。")
        appendLine("13. 章末钩子必须是具体事件（危机降临/意外来客/秘密揭露/关键反转/重大决定），不能只是一句情绪渲染或“他不知道的是…”的空泛预告。")
        appendLine()
        // v6.8.3：三层注入——
        //  第1层 人物设定（硬约束，600字/张全量）；
        //  第2层 世界观+设定圣经（硬约束，800字/张全量）——力量/规则体系（灵根规则等）多写在这里，
        //        之前和普通卡挤 900 字预算会被截断/丢弃，导致体系自相矛盾；
        //  第3层 其余卡（伏笔/主线/大纲/相关卡）维持 900 字预算，注入总量基本恒定
        val charCards = selected.filter { it.category == "人物设定" }
        val worldCards = selected.filter { it.category == "世界观" || it.category == "设定圣经" }
        // v6.9.3：写作禁忌卡单独完整注入——它是自检修正过的矛盾模式清单，
        // 混在普通卡里走 900 字预算会被 300 字/卡截断，避坑信息不完整
        val taboo = selected.filter { it.category == "辅助设定" && it.name == "写作禁忌" }
        val rest = selected.filter {
            it.category != "人物设定" && it.category != "世界观" && it.category != "设定圣经" &&
                it.id !in taboo.map { t -> t.id }
        }
        if (charCards.isNotEmpty()) {
            appendLine("【人物设定（硬性约束，体质/灵根/功法/称谓必须与此完全一致）】")
            // 预算按张数动态给足：每张 600 字上限，一张不丢、一字不截
            append(budgetCardBlock(charCards, budget = charCards.size * 600, perCard = 600))
            appendLine()
        }
        if (worldCards.isNotEmpty()) {
            appendLine("【世界观与力量体系（硬性约束，修炼体系/灵根规则/势力格局不得违背）】")
            append(budgetCardBlock(worldCards, budget = worldCards.size * 800, perCard = 800))
            appendLine()
        }
        if (taboo.isNotEmpty()) {
            appendLine("【写作禁忌（本书已写错并被系统纠正的矛盾模式，原文=>修正，绝不允许再犯）】")
            append(budgetCardBlock(taboo, budget = taboo.size * 600, perCard = 600))
            appendLine()
        }
        if (charCards.isNotEmpty() || worldCards.isNotEmpty()) {
            appendLine("【其他设定资料】")
        } else {
            appendLine("【设定资料】")
        }
        // v6.0：其余卡注入总量恒定 ~900 字（摘要 5×80 + 结尾 300 + 大纲 250 + 任务另计），
        // 600 章注入不膨胀；一致性靠必发卡 + 未回收伏笔
        // v6.9.35：主线剧情/全书大纲/核心冲突卡截 220 字再进预算——这三类最容易写啰嗦/互相重复，
        // 源头精简（瘦身）+ 这里兜底截断，保证注入 token 恒定
        val restTrimmed = rest.map {
            if (it.category == "主线剧情" || it.category == "全书大纲" || it.category == "核心冲突")
                it.copy(content = it.content.take(220))
            else it
        }
        append(budgetCardBlock(restTrimmed, budget = 900, perCard = 300))
    }

    /** 组装写章所需的完整消息；v6.9.41 summaryCount/windowPrev 可配（默认不注入前情摘要、窗口前2章） */
    fun buildChapterMessages(
        project: Project,
        cards: List<SettingCard>,
        chapters: List<Chapter>,
        chapter: Chapter,
        summaryCount: Int = 0,
        windowPrev: Int = 2
    ): List<ChatMsg> {
        val selected = selectCards(cards, chapter.outline + chapter.title)
        val recent = chapters
            .filter { it.chapterIndex < chapter.chapterIndex && it.summary.isNotBlank() }
            .takeLast(summaryCount.coerceAtLeast(0))
        val prev = chapters.firstOrNull { it.chapterIndex == chapter.chapterIndex - 1 }

        // v6.6：大纲窗口注入——只注入分章大纲的「前N章 + 当前章 + 后一章」，
        // 不再注入整卷/整卡（之前 20 章本卷全景太浪费 token，弱模型也跟不上）
        val window = chapters
            .filter { it.chapterIndex in (chapter.chapterIndex - windowPrev)..(chapter.chapterIndex + 1) }
            .sortedBy { it.chapterIndex }
            .joinToString("\n") { ch ->
                val t = ch.title.ifBlank { "未命名" }
                val core = ch.outline.replace(Regex("\\s+"), " ")
                val mark = if (ch.chapterIndex == chapter.chapterIndex) "\u25b6" else "\u00b7"
                if (ch.chapterIndex < chapter.chapterIndex)
                    "$mark 第${ch.chapterIndex}章《$t》（已写）：${core.take(40).ifBlank { "（无大纲）" }}"
                else if (ch.chapterIndex == chapter.chapterIndex)
                    "$mark 第${ch.chapterIndex}章《$t》（本章）：${core.take(120).ifBlank { "（无大纲）" }}"
                else
                    "$mark 第${ch.chapterIndex}章《$t》（下一章引向这里）：${core.take(80).ifBlank { "（待补大纲）" }}"
            }

        val user = buildString {
            appendLine("【书名】${project.title}　【类型】${project.genre}")
            if (project.description.isNotBlank()) appendLine("【简介】${project.description.take(100)}")
            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("【前情摘要】")
                recent.forEach { appendLine("第${it.chapterIndex}章《${it.title}》：${it.summary.take(80)}") }
            }
            if (prev != null && prev.content.isNotBlank()) {
                appendLine()
                appendLine("【上一章结尾（本章开头要自然衔接）】")
                appendLine(prev.content.takeLast(300))
            }
            if (window.isNotBlank()) {
                appendLine()
                appendLine("【分章大纲窗口（前${windowPrev}章+本章+后一章，章节标题以此为准）】")
                appendLine(window)
            }
            appendLine()
            appendLine("【本章任务】第${chapter.chapterIndex}章")
            if (chapter.title.isNotBlank()) appendLine("章节名：${chapter.title}")
            if (chapter.outline.isNotBlank()) {
                appendLine("本章大纲：${chapter.outline.take(250)}")
            } else {
                appendLine("本章大纲未给出，请根据前情与相邻章节大纲自然推进剧情。")
            }
            appendLine()
            val wt = project.chapterWordTarget
            val lo = (wt * 0.85).toInt()
            val hi = (wt * 1.15).toInt()
            append("请写出本章完整正文，字数 ${lo}~${hi} 字，必须写完整一章（含章末钩子收束），不要中途停笔。只输出正文。")
        }
        return listOf(ChatMsg("system", writerSystem(selected)), ChatMsg("user", user))
    }

    /** 大纲生成的系统提示 */
    const val OUTLINE_SYSTEM = "你是资深网文主编，擅长设计节奏紧凑、冲突递进、钩子抓人的分章大纲（对标番茄/起点等平台的连载标准）。只输出要求格式的内容，不要任何解释。"

    fun buildOutlineUser(
        project: Project,
        cards: List<SettingCard>,
        existing: List<Chapter>,
        from: Int,
        to: Int,
        count: Int,
        seed: List<Chapter> = emptyList()
    ): String = buildString {
        appendLine("【书名】${project.title}　【类型】${project.genre}")
        if (project.description.isNotBlank()) appendLine("【简介】${project.description}")
        // v6.9.25：分章大纲卡不注入（旧大纲已由下方 takeLast(20) 窗口覆盖；600章整卡数万字）；核心卡预算截断
        // v6.9.43：人物设定必须注入（此前不在 KEY_CATS，priority!=2 时大纲生成完全看不到人物——大纲与人物脱节、后面的章忘记前面人物的根因）
        val core = cards.filter { it.name != "分章大纲" && (it.priority == 2 || it.category in CardCategories.KEY_CATS || it.category == "人物设定") }
        if (core.isNotEmpty()) {
            appendLine("【核心设定】")
            appendLine(budgetCardBlock(core, budget = 3200, perCard = 600))
        }
        val lastOutlines = existing.filter { it.outline.isNotBlank() }.takeLast(20)
        if (lastOutlines.isNotEmpty()) {
            appendLine("【已有大纲（保持连贯，不要重复已写内容）】")
            lastOutlines.forEach { appendLine("第${it.chapterIndex}章《${it.title}》:${it.outline}") }
        }
        // v6.9.43：开篇基调大纲——并行批次据此保持前后一致（后面的章必须承接前面的章，严禁各写各的）
        if (seed.isNotEmpty()) {
            appendLine("【开篇大纲（全书基调与人物出场基准，本批章节必须自然承接以下剧情与人物线，严禁推翻或另起炉灶）】")
            seed.forEach { appendLine("第${it.chapterIndex}章《${it.title}》:${it.outline}") }
        }
        appendLine()
        append("请为第${from}章到第${to}章编写分章大纲，共${count}行。\n" +
            "硬性格式要求：①必须覆盖第${from}章到第${to}章的每一章，严禁跳章、漏章、合并章；②每章严格一行，每行必须以「第N章《标题》：」开头，标题2~8字必填，没有标题按不合格处理；③除此之外不输出任何其他文字。\n" +
            "每行格式示例：第${from}章《夜闯断魂崖》：出场人物…关键事件…【钩子】=具体事件。\n" +
            "质量要求：每章都要有冲突升级或新信息，禁止日常流水账章；每3~5章安排一个小高潮；每章末尾写明【钩子】=具体事件（危机/反转/来客/秘密），不要空泛悬念。\n" +
            "连贯性硬要求：①每章大纲里出场的人物必须来自【核心设定】的人物卡，言行符合其性格与目标，严禁凭空冒出新角色顶替主要人物；②剧情必须贴着【核心设定】的世界观规则与设定圣经展开，能力/资源不得违反体系；③本批章节要落在【核心设定】全书大纲的对应阶段上，推进阶段目标，不得偏离主线自说自话。")
    }

    /** v5.7：灵感分析 → 生成设定卡（按"还缺哪些分类"精准下单，一次只补一批，内容能写足）；v6.9.35 各类卡分工明确、严禁互抄 */
    const val INSPIRE_SYSTEM =
        "你是资深网文策划师。根据用户的灵感与需求输出结构化设定。\n" +
            "严格按行输出，一行一条，格式：分类｜名称｜内容（分隔符必须是中文全角竖线｜）\n" +
            "分类只能用：全书大纲、世界观、人物设定、主线剧情、支线任务、伏笔钩子、核心冲突、设定圣经、剧情进度、时间线、辅助设定\n" +
            "【各类卡分工严格，同一件事只写在一张卡里，其他卡严禁复述】\n" +
            "  世界观：只写直接影响剧情的规则（地理/势力/资源），不写百科式背景介绍\n" +
            "  人物设定：只写身份/性格/能力/目标/人物关系，不写他的经历剧情\n" +
            "  主线剧情（仅1条，≤120字）：只写「主角的核心目标＋最大阻力＋结局走向」三件事，严禁展开阶段过程\n" +
            "  核心冲突（仅1条，≤80字）：只写对立双方与不可调和的点，不举例不展开\n" +
            "  支线任务：只写这条支线自身的起因和结果，不重复主线目标\n" +
            "  伏笔钩子：只写「埋设点→回收点」一句话，不写剧情细节\n" +
            "  设定圣经（仅1条）：只写力量/规则体系本身，不写剧情\n" +
            "  全书大纲（3~5条）：每条=阶段名＋该阶段发生的最大变化或转折（40~80字），只写「会发生什么」，严禁复述主线剧情的目标与冲突，严禁写到具体章节细节\n" +
            "  时间线（仅1条）：关键事件先后顺序＋相对时间跨度，每件事不超过一句话\n" +
            "【必须按以下顺序一次性全部输出，一项不落、一条不重复、不要中途停下】\n" +
            "  第1条：世界观（仅1条）\n" +
            "  第2~5条：人物设定（主角+2~3个核心人物，每人1条）\n" +
            "  第6条：主线剧情（仅1条）\n" +
            "  第7条：核心冲突（仅1条）\n" +
            "  第8~10条：支线任务（2~3条）\n" +
            "  第11~13条：伏笔钩子（至少3条，名称简短便于追踪）\n" +
            "  第14条：设定圣经（仅1条，力量/规则体系摘要）\n" +
            "  第15~19条：全书大纲（按起承转合分3~5个阶段，每阶段1条）\n" +
            "  第20条：时间线（仅1条，从开篇到第一个大高潮的关键事件按先后排列，标注相对时间跨度）\n" +
            "要求：\n" +
            "1) 每条的「内容」控制在 60~200 字，抓重点、信息密度高、可直接用于写作；设定之间要互相勾连、有戏剧张力，能撑起长篇连载。\n" +
            "2) 卡与卡之间严禁重复：同一个目标/冲突/事件只在一类卡里详细写，别的卡最多一句话带过；每张卡宁短勿长，严禁啰嗦。\n" +
            "3) 一行一条，不要编号、不要 Markdown 符号、不要解释、不要空行标题。\n" +
            "4) 内容里不要再出现｜符号，也不要换行，一整条必须写在同一行内。\n" +
            "5) 每个分类只输出要求的条数，严禁同一个分类输出两条（人物设定/支线任务/伏笔钩子/全书大纲除外）。"

    fun buildInspireUser(
        project: Project,
        inspiration: String,
        need: List<String>,
        existing: List<SettingCard>
    ): String = buildString {
        appendLine("书名：${project.title}（类型：${project.genre}）")
        if (project.description.isNotBlank()) appendLine("已有简介：${project.description}")
        appendLine()
        appendLine("用户的灵感与需求：")
        appendLine(inspiration)
        appendLine()
        appendLine("本轮一次性把以下分类全部写完（按上面规定的顺序，每个分类的条数按系统要求）：${need.joinToString("、")}")
        appendLine("只输出这些分类的行，一次写完，不要输出任何已存在的分类，不要重复。")
        if (existing.isNotEmpty()) {
            appendLine()
            appendLine("【已建成的设定卡（不要重复生成，保持与它们一致）】")
            existing.take(30).forEach { appendLine("${it.category}｜${it.name}：${it.content.take(80)}") }
        }
        appendLine()
        append("现在请只输出「${need.joinToString("、")}」这些分类的设定行，每行：分类｜名称｜内容。")
    }

    /** 编辑器内续写（也用于章节补完）；words=期望续写字数 */
    fun continueMessages(
        project: Project,
        cards: List<SettingCard>,
        chapter: Chapter,
        currentText: String,
        words: Int = 800
    ): List<ChatMsg> {
        val selected = selectCards(cards, chapter.outline)
        val user = buildString {
            appendLine("【本章任务】${chapter.outline.ifBlank { chapter.title }}")
            appendLine("【已有正文（结尾部分）】")
            appendLine(currentText.takeLast(1500))
            append("请从上文结尾处自然续写约${words.coerceIn(200, 2000)}字。只输出续写内容，不要重复已有内容，不要输出标题或解释；若本章剧情已可收束，就写到章末钩子为止。")
        }
        return listOf(ChatMsg("system", writerSystem(selected)), ChatMsg("user", user))
    }
}
