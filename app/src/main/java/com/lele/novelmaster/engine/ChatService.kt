package com.lele.novelmaster.engine

import android.content.Context
import com.lele.novelmaster.data.AiClient
import com.lele.novelmaster.data.ApiConfig
import com.lele.novelmaster.data.CardCategories
import com.lele.novelmaster.data.ChatMsg
import com.lele.novelmaster.data.Message
import com.lele.novelmaster.data.Prompts
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.tools.IntentRouter
import com.lele.novelmaster.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 聊天调度中枢 v5.7：
 *  - 流式输出：AI 吐第一个字就显示到界面，不再"卡住看不到东西"
 *  - 分批即时保存：流式中一旦出现完整工具块立刻执行落库，长内容不再丢
 *  - 自动续跑：被长度截断时自动接着生成下一批（最多 4 轮），内容完整不残
 *  - 「继续」指令真实生效
 *  - 五级工具容错解析 + 截断自动补全（兼容老模型）
 */
object ChatService {

    /** 全部可被 AI 触发的工具名。
     *  v6.9.34 修复：补齐 11 个一致性体检/修复工具（此前系统提示里宣传了这些工具，
     *  但 KNOWN_TOOLS 白名单没加——AI 自由对话输出工具块会被 runTool 静默拒绝） */
    val KNOWN_TOOLS = setOf(
        "createProject", "switchProject", "updateProject", "listProjects", "addCard", "deleteCard", "updateCard", "renameGlobal", "readCard", "writeNextChapter", "rewriteChapter",
        "startAutoWrite", "stopAutoWrite", "generateOutlines", "inspireFromText",
        "readChapter", "listCards", "listChapters", "exportTxt", "deleteProject",
        "contextPreview", "moveChapter", "copyChapter",
        "polishChapter", "publishPolish", "expandDialogue", "styleRewrite", "hookChapter", "goldenLines",
        "plotBrainstorm", "characterCheck", "consistencyCheck", "nameGen", "genBlurb",
        "chapterSelfCheck", "fullSelfCheck", "selfCheckProgress", "undoSelfCheck", "supplementChapter",
        "foreshadowCheck", "markHookRecovered", "subplotCheck", "cardsCheck", "cardsCheckReport", "cardsApplyRepair", "cardsRepairWith", "cardsSlim", "setChapterOutline",
        "createFolder", "deleteFolder", "renameFolder",
        "createFile", "writeFile", "appendFile", "readFile",
        "deleteFile", "renameFile", "listFiles"
    )

    /** v5.7：单次提问最多自动续跑几轮 */
    private const val MAX_ROUNDS = 4

    private val BLOCK_RE = Regex("<tool>\\s*(.*?)\\s*</tool>", RegexOption.DOT_MATCHES_ALL)

    /** v6.9.43：裸 JSON 工具块（名字在花括号内），如 {"name":"updateProject","args":{…}} */
    private val RAW_JSON_RE: Regex by lazy {
        Regex("\\{\\s*\"name\"\\s*:\\s*\"(" + KNOWN_TOOLS.sortedByDescending { it.length }.joinToString("|") + ")\"")
    }

    /** v6.9.45：伪造执行记录——弱模型（glm-4-flash）学舌注入格式，输出「[系统执行记录·已成功，勿重复] 已保存XX：无」，
     *  实际没有调用任何工具、什么都没保存，却让模型自己以为保存过了，后续再也不输出工具块 → 设定卡生成不了 */
    private val FORGED_MARK_RE = Regex("\\[系统执行记录|已保存[^\\n]{0,30}：\\s*无")

    /** v6.3：口头保存检测（本轮没执行工具却声称已保存） */
    private val CLAIM_SAVED_RE = Regex("已保存|已存好|保存好了|已经保存|已写入|已建立|已生成设定卡|已创建设定|已经写好|已存到")

    private val CONTINUE_RE = Regex(
        "^(继续|接着|往下|然后呢|还有呢|没写完|写下去|continue|go\\s*on)",
        RegexOption.IGNORE_CASE
    )

    fun isContinue(input: String): Boolean = CONTINUE_RE.containsMatchIn(input.trim())

    const val Welcome = "你好，我是乐乐 🪶 可以陪你聊天，也可以帮你写小说。\n\n" +
        "想写小说时直接说，比如：\n" +
        "  「我想写一本穿越修仙的爱情小说」→ 我自动在当前会话里完善设定和大纲，等你确认再写\n" +
        "  「写下一章」「自动写作」「把这段存到人物设定卡」\n\n" +
        "先到右上「对接AI」添加一个模型（推荐智谱 glm-4-flash，免费）。平时随便聊，我都在 ✨"

    /** 对外入口：返回最后插入的 assistant 消息 id（供界面跳过重复的打字机动画） */
    suspend fun handle(
        context: Context,
        pid0: Long,
        input: String,
        onProjectChange: (Long) -> Unit,
        onStream: suspend (String) -> Unit = {}
    ): Long? {
        // v6.9.50：幽灵会话守卫——pid 指向已删除的项目时，先回正到真实会话（此前会话整体跑在
        // 不存在的 pid 上：addCard/listFiles 照样"成功"但数据成孤儿，写章则报「项目不存在」）
        val currentPid = if (pid0 > 0L && Repo.dao.project(pid0) == null) {
            val np = Repo.dao.projectsFlow().first().firstOrNull()?.id ?: 0L
            if (np > 0L) onProjectChange(np)
            np
        } else pid0
        Repo.dao.insertMessage(Message(projectId = currentPid, role = "user", content = input))

        // v6.5：作者在本地文件里改过的内容先同步回库（磁盘→库），并合并重复的单卡分类——
        // 之后 AI 注入到的永远是作者改过的最新版
        if (currentPid > 0L) {
            runCatching { com.lele.novelmaster.data.WriterEngine.mergeDuplicateSingles(currentPid) }
            runCatching { com.lele.novelmaster.data.WriterEngine.syncFromLocalFiles(currentPid, context) }
        }

        // 1. 本地快速路由（确定性口令，不耗 token）
        val tool = IntentRouter.handle(input, currentPid, context)
        if (tool != null) {
            appendToolResult(currentPid, tool)
            tool.newProjectId?.let { onProjectChange(it) }
            if (tool.ok && tool.summary.startsWith("已删除会话")) {
                val ps = Repo.dao.projectsFlow().first()
                onProjectChange(ps.firstOrNull()?.id ?: 0L)
            }
            return null
        }

        // 2. AI 对话（v6.9.34：本书绑定了独立模型就用它，无则回落全局启用）
        val cfg = Repo.apiFor(currentPid)
        if (cfg == null) {
            Repo.dao.insertMessage(
                Message(projectId = currentPid, role = "system",
                    content = "还没启用 AI 模型：点右上「对接AI」→ 添加（推荐智谱 glm-4-flash 免费）→ 选模型 → 设为启用。",
                    kind = "text")
            )
            return null
        }

        return try {
            runRounds(cfg, currentPid, input, context, onProjectChange, onStream)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Repo.dao.insertMessage(
                Message(projectId = currentPid, role = "system",
                    content = "⚠️ AI 调用失败：${e.message?.take(300)}", kind = "error")
            )
            null
        }
    }

    // ------------------------------------------------------------------
    // 多轮流式生成（自动续跑 + 分批保存）
    // ------------------------------------------------------------------

    private suspend fun runRounds(
        cfg: ApiConfig,
        currentPid: Long,
        input: String,
        context: Context,
        onProjectChange: (Long) -> Unit,
        onStream: suspend (String) -> Unit
    ): Long? {
        var pid = currentPid
        var deletedCurrent = false
        var executed = 0
        val executedKeys = mutableSetOf<String>()
        var createdPid: Long? = null
        // v6.7：本次小结用——执行过哪些工具、是否截断、是否被手动停止
        val doneTools = mutableListOf<String>()
        var anyTruncated = false
        var wasCanceled = false

        /** JSON null 参数 -> ""（org.json 的 optString 会把 JSON null 变成字面量 "null"，必须拦在工具层之前） */
        fun cleanArgs(a: JSONObject): JSONObject {
            val out = JSONObject()
            val keys = a.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = a.opt(k)) {
                    null, org.json.JSONObject.NULL -> out.put(k, "")
                    is String -> out.put(k, if (v == "null") "" else v)
                    else -> out.put(k, v)
                }
            }
            return out
        }

        // v6.9.45：去重命中标记——去重路径 return true 但并没真的执行工具，
        // 不能让调用点把它计入 executed（否则「口头保存」检测永远失效）
        var lastDedup = false

        suspend fun runTool(rawName: String, rawArgs: JSONObject): Boolean {
            val name = rawName.trim()
            val args = cleanArgs(rawArgs)
            lastDedup = false
            if (name !in KNOWN_TOOLS) return false
            val key = name + "|" + args.toString()
            // 只对"带参数"的调用去重（无参工具如 writeNextChapter 允许连发多次）
            if (args.length() > 0 && !executedKeys.add(key)) {
                lastDedup = true
                return true
            }

            // v5.6：已有会话时，AI 再怎么误调 createProject 也一律转成"改当前会话"——代码级兜底，绝不跳会话
            if (name == "createProject" && pid > 0L) {
                val r2 = com.lele.novelmaster.tools.Tools.updateProject(
                    pid,
                    args.optString("title").takeIf { it.isNotBlank() },
                    args.optString("genre").takeIf { it.isNotBlank() },
                    args.optString("desc").takeIf { it.isNotBlank() },
                    args.optInt("totalCh", -1).takeIf { it > 0 },
                    args.optInt("chWords", -1).takeIf { it > 0 }
                )
                appendToolResult(pid, r2)
                return true
            }
            if (name == "createProject" && createdPid != null) {
                pid = createdPid!!
                onProjectChange(pid)
                appendToolResult(pid, ToolResult(true, "已复用本轮新建的会话", "《${args.optString("title")}》不需要重复创建，一个会话就是一本小说。"))
                return true
            }
            val r = com.lele.novelmaster.tools.Tools.dispatch(pid, name, args, context)
            if (r == null) return false
            r.newProjectId?.let { np ->
                pid = np
                if (name == "createProject") createdPid = np
                onProjectChange(np)
            }
            appendToolResult(pid, r)
            if (r.ok) doneTools.add("$name：${r.summary.take(50)}")
            if (name == "deleteProject" && r.ok) deletedCurrent = true
            return true
        }

        val shown = StringBuilder()   // 各轮已生成并展示的正文（累计）
        val raw = StringBuilder()     // 本轮原始输出
        var drained = 0

        /** 流式中一旦出现完整工具块就立刻执行 —— 长设定/长正文不再因为截断而丢失 */
        suspend fun drainBlocks() {
            val seg = raw.toString()
            val ms = BLOCK_RE.findAll(seg).toList()
            if (ms.size > drained) {
                for (k in drained until ms.size) {
                    val p = parseToolLenient(ms[k].groupValues[1])
                    if (p != null && runTool(p.first, p.second) && !lastDedup) executed++
                }
                drained = ms.size
            }
        }

        /** v6.0：剥离思考过程（<think>…</think> 或未闭合的 <think>…），绝不显示给作者 */
        fun stripThinking(s: String): String =
            s.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<think>[\\s\\S]*$", RegexOption.IGNORE_CASE), "")

        /** 给用户看的文本：剔除思考过程、工具块与未闭合的片段 */
        fun visibleOf(seg: String): String {
            var v = stripThinking(seg)
            v = BLOCK_RE.replace(v, "")
            // v6.9.43：裸 JSON 工具块不展示（流式中正在输出/未闭合时，从起点截断到末尾）
            RAW_JSON_RE.find(v)?.let { v = v.substring(0, it.range.first) }
            var i = v.indexOf("<tool")
            if (i >= 0) v = v.substring(0, i)
            i = v.indexOf("```")
            if (i >= 0) v = v.substring(0, i)
            // v6.9.45：剔除 AI 学舌伪造的「[系统执行记录…]」「已保存××：无」行——
            // 这些不是真的系统回执，是模型模仿注入格式输出的假记录，绝不能显示/落库
            v = v.lines().filterNot { FORGED_MARK_RE.containsMatchIn(it) }.joinToString("\n")
            return v.replace(Regex("\\n{3,}"), "\n\n").trimEnd()
        }

        var lastEmit = 0L
        suspend fun emit(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastEmit < 60) return
            lastEmit = now
            val cur = if (raw.isEmpty()) "" else visibleOf(raw.toString())
            val prev = shown.toString()
            onStream(
                when {
                    prev.isEmpty() -> cur
                    cur.isEmpty() -> prev
                    else -> prev + "\n\n" + cur
                }
            )
        }

        return withContext(Dispatchers.IO) {

            // ---------- 组装消息 ----------
            // v6.0：「继续」的两种语义：
            //   上一条明显被截断（结尾没收束）→ 接着把没写完的内容写完；
            //   否则 → 继续流程的下一步（设定/大纲没建完→建完；建完了→写下一章），绝不连续不停地写。
            val recent = Repo.dao.messagesFlow(pid).first().takeLast(8).toMutableList()
            // 去掉刚插入的同一条 user 消息，避免重复
            if (recent.lastOrNull()?.role == "user" && recent.lastOrNull()?.content == input) {
                recent.removeAt(recent.lastIndex)
            }
            val lastAssistant = recent.lastOrNull { it.role == "assistant" && !it.content.startsWith("📖") && !it.content.startsWith("✍️") }?.content?.trimEnd().orEmpty()
            val resumeMidSentence = isContinue(input) && lastAssistant.isNotEmpty() &&
                lastAssistant.last() !in "。！？…」』”\"!?~）)】"

            val sysText = buildSystemText(pid, isContinue(input), resumeMidSentence)
            val msgs = mutableListOf<ChatMsg>(ChatMsg("system", sysText))
            recent.forEach { m ->
                when {
                    m.kind == "text" -> {
                        // v6.9.45：历史里可能已有学舌伪造的「[系统执行记录…]」行（旧版本落库的），注入前剔除防继续污染
                        val c = m.content.lines().filterNot { FORGED_MARK_RE.containsMatchIn(it) }.joinToString("\n").trim()
                        if (c.isNotBlank()) msgs.add(ChatMsg(if (m.role == "user") "user" else "assistant", c.take(8000)))
                    }
                    // v5.9：把已执行的工具记录也喂给 AI —— 否则「继续」时它不知道已经存过什么，
                    // 会从世界观/人物设定重新开始生成，永远走不出来
                    m.kind == "tool" && m.role == "tool" ->
                        msgs.add(ChatMsg("user", "[系统执行记录·已成功，勿重复] " + m.content.lines().first().take(150)))
                    m.kind == "error" ->
                        msgs.add(ChatMsg("user", "[系统执行记录·失败，请修正后重试] " + m.content.take(150)))
                }
            }
            msgs.add(
                ChatMsg(
                    "user",
                    when {
                        resumeMidSentence ->
                            "继续。你上一条回复在半截被打断了，严格接着最后一句话往下输出完，不要重复已写过的内容，不要写「（续）」这类开场白。"
                        isContinue(input) ->
                            "继续 = 按流程继续下一步：对照「已保存的设定卡清单」，设定/分章大纲还没建全就接着建全；已经建全了就调用 writeNextChapter 写下一章（要完整一章正文，不是梗概）。已完成的部分严禁重做，也严禁一口气连写多章。"
                        else -> input
                    }
                )
            )

            // v6.9.42：「继续」时系统先点名缺什么——不给 AI 含糊空间。
            // 用户踩坑：设定卡没生成完，AI 空回一句文字+小结；再叫「继续」还是什么都不做。
            if (isContinue(input) && !resumeMidSentence && pid > 0L) {
                runCatching {
                    val cs = Repo.dao.cards(pid)
                    val have = cs.map { it.category }.toSet()
                    val needCats = listOf("世界观", "人物设定", "主线剧情", "核心冲突", "支线任务", "伏笔钩子", "设定圣经", "全书大纲")
                    val missingCats = needCats.filter { it !in have }
                    val chs = Repo.dao.chapters(pid)
                    val miss = missingCats + if (chs.isNotEmpty() && chs.any { it.outline.isBlank() })
                        listOf("分章大纲（仅 ${chs.count { it.outline.isNotBlank() }}/${chs.size} 章有大纲）") else emptyList()
                    if (miss.isNotEmpty()) {
                        msgs.add(
                            ChatMsg(
                                "user",
                                "系统检查：这本书还有没建全的设定——${miss.joinToString("、")}。" +
                                    "本轮回复必须直接输出补齐它们的工具块（addCard/generateOutlines），不要任何开场白、确认语或小结，做完才许汇报。"
                            )
                        )
                    }
                }
            }

            var round = 0
            var stopReason = "stop"

            while (round < MAX_ROUNDS) {
                round++
                drained = 0
                raw.setLength(0)
                var canceled = false

                try {
                    val res = AiClient.chatStream(
                        cfg, msgs,
                        temperature = 0.85,
                        maxTokens = AiClient.MAX_TOKENS_HUGE
                    ) { delta ->
                        raw.append(delta)
                        drainBlocks()
                        emit(false)
                    }
                    stopReason = res.finishReason
                    if (res.finishReason == "length") anyTruncated = true
                } catch (ce: CancellationException) {
                    canceled = true   // 用户点了停止：已生成的部分照样保存，不丢内容
                    wasCanceled = true
                }

                // 收尾：再扫一遍（兼容不带 <tool> 包裹的写法）
                drainBlocks()
                var work = scanLooseTools(raw.toString()) { n, a ->
                    val ok = runTool(n, a)
                    if (ok && !lastDedup) executed++
                    ok
                }
                val text = visibleOf(work).trim()

                // v5.8：防重复——模型续跑时把前面写过的内容原样再输出一遍，就立刻停，不再循环
                val prevSoFar = shown.toString()
                val dup = text.isNotBlank() && prevSoFar.length > 60 &&
                    (prevSoFar.contains(text.take(80)) || text.length <= prevSoFar.length && text.take(60) == prevSoFar.takeLast(60))

                if (text.isNotBlank() && !dup) {
                    if (shown.isNotEmpty()) shown.append("\n\n")
                    shown.append(text)
                }
                emit(true)

                if (canceled || dup) break

                // ---------- 戳穿伪造/口头保存（v6.9.45 移入循环内：此前在循环外是死代码，且永不该漏过） ----------
                // v6.9.45：伪造执行记录——弱模型学舌注入格式「[系统执行记录·已成功，勿重复] 已保存XX：无」，
                // 实际没调任何工具；不论 executed 是否>0 都要纠偏（去重命中也不再虚增 executed）。
                // 注意必须检测 raw（原始输出）：shown 里的伪造行已被 visibleOf 剔除，检测 shown 永远不命中
                val forged = FORGED_MARK_RE.containsMatchIn(stripThinking(raw.toString()))
                val claimSaved = forged || (executed == 0 && CLAIM_SAVED_RE.containsMatchIn(shown.toString()))

                // ---------- 判断是否要自动续跑 ----------
                val truncatedByLength = stopReason == "length"
                val unterminated = raw.lastIndexOf("<tool>") > raw.lastIndexOf("</tool>")
                val unfinished = text.length > 120 && !endsWell(text) && executed == 0
                val needMore = (truncatedByLength || unterminated || unfinished || claimSaved) && round < MAX_ROUNDS

                if (!needMore) break

                // 组下一轮：把已经写出来的交给 AI 当上下文，让它接着写
                msgs.add(ChatMsg("assistant", text.ifBlank { if (claimSaved) "（我没有真正保存任何内容）" else "（本轮已执行保存操作）" }))
                if (claimSaved) {
                    // v6.9.45：纠偏消息顶掉「继续」指令——伪造/口头保存时必须先真保存，不是接着写
                    Repo.dao.insertMessage(
                        Message(projectId = pid, role = "system", kind = "error",
                            content = if (forged)
                                "⚠️ 检测到你输出了伪造的「[系统执行记录…]」/「已保存××：无」文字——那只是系统注入的记录格式，你没有真正调用工具，**什么都没有保存**。\n" +
                                    "现在请逐条输出 addCard 工具块，把该保存的设定真正保存一遍。"
                            else
                                "⚠️ 检测到本轮只是**口头说已保存**，实际没有执行任何保存工具——内容并没有真正存下来。\n" +
                                    "请用 addCard（设定卡）或 writeFile/createFile（文件）的工具块重新保存一遍。")
                    )
                    msgs.add(
                        ChatMsg(
                            "user",
                            if (forged)
                                "你刚才输出的「[系统执行记录…]」「已保存××：无」是伪造的执行记录，系统里根本没有这些卡——你没有调用任何工具，什么都没保存。现在请逐条输出工具块真正保存（设定用 addCard，格式：<tool>{\"name\":\"addCard\",\"args\":{\"category\":\"分类\",\"name\":\"名称\",\"content\":\"内容\"}}</tool>），一个工具块一条，立刻执行，严禁再输出任何「[系统执行记录」或「已保存××：无」字样的文字。"
                            else
                                "你刚才只是口头说保存，没有真正调用工具。现在请用工具块把上面的内容全部保存（设定用 addCard，文件用 writeFile），一个工具块一条，立刻执行。"
                        )
                    )
                } else {
                    msgs.add(
                        ChatMsg(
                            "user",
                            "继续。严格接着上一条的最后一句话往下输出，不要重复已输出的内容，不要写「（续）」「接上文」「未完待续」这类话，直接把剩下的部分完整写完。"
                        )
                    )
                }
                onStream(shown.toString() + "\n\n…（内容较长，正在自动继续输出）")
                delay(150)
            }

            if (deletedCurrent) {
                val ps = Repo.dao.projectsFlow().first()
                pid = ps.firstOrNull()?.id ?: 0L
                onProjectChange(pid)
            }

            // ---------- 落库 ----------
            val finalText = stripThinking(shown.toString())
                .replace(Regex("<tool>[\\s\\S]*$"), "")
                .replace(Regex("```[\\s\\S]*$"), "")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()

            val savedId = when {
                finalText.isNotBlank() -> {
                    val id = Repo.dao.insertMessage(
                        Message(projectId = pid, role = "assistant", content = finalText, kind = "text")
                    )
                    id
                }
                executed > 0 -> null
                else -> {
                    Repo.dao.insertMessage(
                        Message(projectId = pid, role = "system",
                            content = "⚠️ AI 这次没有返回可显示的内容，请再发一次，或换一个模型试试。", kind = "error")
                    )
                    null
                }
            }

            // v6.7：每次回复结束的「本次小结」——生成保存情况 + 是否截断 + 建议
            if (pid > 0L) runCatching {
                val rep = StringBuilder("📋 本次小结\n")
                if (doneTools.isEmpty()) {
                    rep.appendLine("✅ 本轮为文字回复，没有生成新的设定卡或文件。")
                } else {
                    rep.appendLine("✅ 已生成并保存 ${doneTools.size} 项：")
                    doneTools.forEach { rep.appendLine("  • $it") }
                }
                rep.appendLine(
                    when {
                        anyTruncated -> "⚠️ 截断检测：输出被截断过（已自动续写）；若内容仍不完整，说「继续」接着生成。"
                        wasCanceled -> "⏹ 已手动停止，本轮已生成的部分都保存好了，不会丢。"
                        else -> "📄 完整性：未截断，内容完整。"
                    }
                )
                rep.append(
                    when {
                        anyTruncated -> "💡 建议：说「继续」补完剩余内容；长内容会自动分批保存不丢。"
                        doneTools.any { it.startsWith("writeNextChapter") || it.startsWith("startAutoWrite") } ->
                            "💡 建议：可继续「写下一章」推进进度，或到书架阅读检查连贯性；不满意就说「重写本章」。"
                        doneTools.any { it.startsWith("addCard") } ->
                            "💡 建议：到「设定卡」页可查看刚保存的卡；要修改直接说「修改××卡的××」。"
                        else -> "💡 建议：直接告诉我下一步（写章/改设定/查大纲），我马上开工。"
                    }
                )
                Repo.dao.insertMessage(
                    Message(projectId = pid, role = "tool", kind = "tool", content = rep.toString())
                )
            }
            savedId
        }
    }

    // ------------------------------------------------------------------
    // 系统提示词
    // ------------------------------------------------------------------

    private suspend fun buildSystemText(pid: Long, continuing: Boolean, resumeMid: Boolean = false): String = buildString {
        appendLine(
            "你是「乐乐」，作者的智能写作助理：既像朋友一样自然聊天，更是顶级小说创作专家+全能执行智能体。\n" +
                "【行事风格（最重要）】\n" +
                "· 作者提出创作需求后**直接开始干活**，不要反问、不要请示、不要输出「你想…还是…」之类的客套选择题。\n" +
                "· 过程性播报一律省略（例如「已更新会话信息」这类话不要说），只在全部做完后简短汇报结果和下一步。\n" +
                "· 作者的意思按字面执行：说写章就写完整一章正文（绝不用梗概/摘要/大纲代替正文）；说保存就保存；说改就改。\n" +
                "· 一次把事做完：能一口气完成的绝不拆成多次问答。\n"
        )
        appendLine(
            "【创作流程】\n" +
                "1) 作者打招呼、闲聊、问问题 → 像真人朋友一样自然回复，不强行拉回写小说。\n" +
                "2) 作者给出灵感/题材（例如「帮写一个家庭幸福的小说，共36章」）→ 立即全套执行：updateProject 写入书名/类型/章数 → 用 addCard 逐条建全设定（世界观、主要人物各一张、主线剧情、核心冲突、支线任务、伏笔钩子至少3个、设定圣经、全书大纲）→ generateOutlines 补全分章大纲 → 汇报「设定和大纲已就绪，说『写下一章』开写」。\n" +
                "3) **设定和大纲建完后停下等作者说「写下一章」/「开始写第一章」/「继续」才写正文**，绝不要默认自动写。\n" +
                "3.1) **系统硬性校验**：设定卡八类（世界观、人物设定、主线剧情、核心冲突、支线任务、伏笔钩子、设定圣经、全书大纲）+ 分章大纲没建全时，writeNextChapter/startAutoWrite 会被系统拒绝并自动补全。所以必须先建全设定（工具回执可见）再写正文，严禁跳步、严禁没建完就调写作工具。不要创建「分卷大纲」卡（已废弃，与分章大纲重复）。\n" +
                "4) 已有会话时严禁 createProject（一律用 updateProject 改当前会话）；所有创作都在当前会话内完成，绝不跳会话。\n" +
                "5) 作者要求修改时先改设定（updateProject/addCard）再按需重写章节；始终保持人物、世界观、伏笔一致。\n" +
                "6) 写正文必须完整一章：有推进、有冲突、章末留钩子；只输出正文本身，不输出标题、解释、总结。\n" +
                "7) 任何设定/资料必须用工具保存（addCard/文件工具），光说不存等于没做。\n" +
                "7.1) **严禁在文字里口头声称「已保存/已写入/已建立/已生成设定卡」——只有工具执行成功后的系统回执才算真的保存了。**\n" +
                "     要保存就必须输出工具块；弱模型尤其不要偷懒用文字假装保存。\n" +
                "8) 保存类工具执行后系统会自动回执「已保存：分类/名称」，你**不要再把卡片全文复述进聊天**，只报一句话清单（保存好了哪些、还缺哪些）和下一步。\n" +
                "8.1) 内容太多一次保存不完就**分多个 addCard 分批保存**，每条都要真的调工具；全部存完后汇报「已保存好哪些、还缺哪些」，缺的部分等作者说「继续」接着补，已保存的绝不重做。\n" +
                "8.2) 同一本书里**世界观/主线剧情/核心冲突/设定圣经/全书大纲/剧情进度 各只允许一张卡**——系统会自动去重，你换名字重建也没用，要改就更新原卡。\n" +
                "9) 作者可能直接修改本地项目文件（设定卡/正文/大纲 .md/.txt），系统每次对话前都会把最新文件内容自动同步进设定库——你注入到的就是作者改过的最新版，无需让作者重新粘贴。\n" +
                "10) 回复用自然中文，发挥才华，字数不限；别输出内心分析/思考过程，别复述本协议。\n"
        )
        appendLine()
        appendLine(
            "【输出与保存规则（重要）】\n" +
                "· 你一次可以输出很长很多的内容，想写多少写多少，绝对不要故意少写、不要输出几条就停、不要停下来等待确认。\n" +
                "· 需要保存的内容：想好一条就用一个工具块保存一条。系统是**边接收边执行保存**的，保存不会打断你的输出，输出与保存同时进行。\n" +
                "· 一个工具块输出完，紧接着继续输出下一个工具块，中间不要停、不要问、不要等确认。\n" +
                "· 收到「继续」时：只补还没生成的部分，**严禁重复生成已保存过的设定卡**（已保存清单见下方），直接从缺失的分类接着做。\n" +
                "· 设定卡没建全之前，每一轮回复都**必须包含工具块**；严禁只回「好的」「马上继续」「继续为你生成」这类文字敷衍——没做完就立刻接着用工具做，全部做完才允许纯文字收尾。\n" +
                "· 严禁「（略）」「（以下省略）」「未完待续」「由于篇幅限制」这类偷懒输出，也不要解释自己省略了什么。\n" +
                "· 每个工具块的 JSON 必须完整闭合，content 里换行用 \\n、引号用 \\\" 转义，不要用中文引号。\n" +
                "· 建设定卡（addCard）前先把整本书当成一个完整故事想清楚，再动笔：所有卡必须围绕同一主线互相勾连——人物有明确的动机/目标/关系网，世界观规则直接影响主线冲突，伏笔钩子都能在全书大纲里找到回收点；严禁各写各的、前后矛盾。内容要具体（有名字、地点、规则、代价），不要空话套话。\n" +
                "· 同一事实全书只能有一个版本：人物能力/禁术的代价（多少修为、什么后果）、关键事件（谁做了什么、怎么做的）、专有名词与数字，在所有卡、大纲里必须完全一致。输出新卡前先对照本次消息里已保存的卡核对，冲突时以已有卡为准；确实要改，就在本次输出里把相关卡一并重新保存成统一版本，严禁同一事实在不同卡里说法不同。\n" +
                "· 「[系统执行记录·已成功，勿重复]」这种方括号标记是系统注入给你的历史回执格式，你的回复里**绝对禁止出现**这种标记；更严禁输出「已保存××：无」这类伪造记录——没有输出工具块并看到系统回执，就等于什么都没保存。要保存就输出工具块，一条一行，不要用任何文字假装保存。\n"
        )
        if (continuing) {
            appendLine()
            if (resumeMid) {
                appendLine("【本次是「继续」指令·半截续写】你上一条在半截被打断，严格接着最后一句话往下输出完，不要重复、不要重新开头。")
            } else {
                appendLine("【本次是「继续」指令·下一步】对照下方「已保存的设定卡清单」：设定/分章大纲没建全 → 本轮**必须立即输出工具块**（addCard/generateOutlines）接着补齐缺失项，严禁只回文字计划、确认语或小结而不调工具；已建全 → 调用 writeNextChapter 写下一章完整正文（不是梗概）。已完成的部分严禁重做，严禁一口气连写多章。")
            }
        }
        appendLine()
        appendLine(
            "【工具协议】需要执行操作时，在回复中输出工具块，格式必须是合法 JSON（两个字段缺一不可）：\n" +
                "<tool>{\"name\":\"工具名\",\"args\":{\"参数\":\"值\"}}</tool>\n" +
                "可用工具：\n" +
                "— 项目/设定：createProject{title,genre,desc,totalCh,chWords}(仅无会话时) | updateProject{title,genre,desc,totalCh,chWords}(改当前会话，不跳出) | switchProject{pid} | listProjects{} | addCard{category,name,content}(category:" +
                CardCategories.all.joinToString("/") + ") | updateCard{name,content}(修改已有设定卡·整卡覆盖) | deleteCard{cardId} | renameGlobal{old,new,alsoChapters可空}(全书联动改名/改设定) | readCard{name}(看某张卡完整原文) | listCards{category可空}\n" +
                "— 写作：writeNextChapter{} | rewriteChapter{index} | startAutoWrite{from,to} | stopAutoWrite{} | generateOutlines{} | readChapter{index} | listChapters{onlyMissing} | moveChapter{from,to} | copyChapter{index} | contextPreview{} | exportTxt{} | deleteProject{}(仅作者明确说删除会话时)\n" +
                "— 专家功能(index可空=最新章)：polishChapter | publishPolish{}(发布打磨·去AI味+开头+节奏+钩子，发布前最后一道工序) | expandDialogue | styleRewrite{style} | hookChapter | goldenLines | plotBrainstorm{} | characterCheck{name} | consistencyCheck{}(全书体检·只列矛盾) | nameGen{kind,count} | genBlurb{}\n" +
                "— 一致性体检/修复：chapterSelfCheck{index}(单章自检·矛盾自动修正) | fullSelfCheck{}(全书逐章自检修复) | selfCheckProgress{}(自检进度) | undoSelfCheck{index}(撤销最近一次修改) | supplementChapter{index}(按大纲补写缺失剧情) | cardsCheck{}(设定体检·只出报告不改卡) | cardsCheckReport{}(查看最近一次体检报告·只读) | cardsApplyRepair{}(确认修复·按最近报告立即改卡) | cardsRepairWith{instruction}(按作者调整要求修复) | cardsSlim{}(设定瘦身·全部设定卡去重压缩抓重点) | setChapterOutline{index,text}(手动修改第N章大纲并同步镜像卡·text传新大纲全文)\n" +
                "— 伏笔/支线：foreshadowCheck{}(伏笔体检·埋收状态审计) | markHookRecovered{name}(标记伏笔已回收) | subplotCheck{}(支线体检·推进状态与收束建议)\n" +
                "— 文件系统(path相对当前会话独立文件夹)：createFolder{path} | deleteFolder{path} | renameFolder{path,newName} | createFile{path,content} | writeFile{path,content} | appendFile{path,content} | readFile{path} | deleteFile{path} | renameFile{path,newName} | listFiles{path可空}\n" +
                "【改名/统一设定铁律】作者要求改人物名/书名/专有名词/统一某个说法（境界、称呼、数字等）时，必须调 renameGlobal{old,new} 全书联动——所有设定卡、分章大纲、章节大纲一次改齐（作者说「连正文/已写章节也改」时加 alsoChapters=true），改完建议跑一次设定体检核对；严禁只新建或只改一张卡就声称改完。单张卡小修用 updateCard；改之前想确认原文用 readCard。\n" +
                "【体检对话流程】cardsCheck 只出报告不改卡：作者对报告有疑问 → 先 cardsCheckReport/readCard 查看再如实解释，可讨论可调整；作者确认要修 → 调 cardsApplyRepair 按报告立即修复；作者带调整要求（如「保留李炎那张卡，把君既白并进去」）→ 调 cardsRepairWith{instruction} 按要求修复。没跑过体检时作者问体检相关，先跑 cardsCheck。\n" +
                "注意：可以一次输出多个工具块；闲聊/回答问题时不要输出任何工具块。"
        )
        appendLine()
        if (pid > 0L) {
            val project = Repo.dao.project(pid)
            if (project != null) {
                appendLine("【当前会话】《${project.title}》类型：${project.genre}，目标 ${project.targetChapters} 章，每章约 ${project.chapterWordTarget} 字")
                val cards = Repo.dao.cards(pid)
                if (cards.isNotEmpty()) {
                    // v5.9：已保存清单——「继续」时 AI 据此只补缺失项，不再重复生成世界观/人物设定
                    appendLine("【已保存的设定卡清单（这些已存在，严禁重复生成）】")
                    cards.groupBy { it.category }.forEach { (cat, list) ->
                        appendLine("· $cat：${list.joinToString("、") { it.name }}")
                    }
                    // v6.9.24：分章大纲卡不整卡注入（600章可达数万字，每轮对话都付不起），
                    // 核心卡改 budgetCardBlock 预算截断（总3200/单卡600），与 freeTask 同款；AI 需要时可调 listCards
                    val core = cards.filter { it.name != "分章大纲" && (it.priority == 2 || it.category in CardCategories.KEY_CATS) }
                    if (core.isNotEmpty()) {
                        appendLine("【本书核心设定】")
                        appendLine(Prompts.budgetCardBlock(core, budget = 3200, perCard = 600))
                    }
                } else {
                    appendLine("【本书核心设定】尚未创建。作者给出灵感后，你要用 addCard 一条条把设定建全，一次做完不要停。")
                }
                val done = Repo.dao.chapters(pid).count { it.content.isNotBlank() }
                appendLine("【进度】已写完 $done / ${project.targetChapters} 章")
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具解析（五级容错）
    // ------------------------------------------------------------------

    /** 收尾时扫描非 <tool> 包裹的写法，执行后把它们从文本里剔除，返回剩余文本 */
    private suspend fun scanLooseTools(work0: String, exec: suspend (String, JSONObject) -> Boolean): String {
        var work = work0
        // ① 标准闭合块（流式中通常已执行，这里做兜底；去重机制保证不会重复执行）
        BLOCK_RE.findAll(work).toList().forEach { m ->
            parseToolLenient(m.groupValues[1])?.let { exec(it.first, it.second) }
        }
        work = BLOCK_RE.replace(work, "")

        // ② ```json``` 围栏块
        val fenceRe = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```")
        fenceRe.findAll(work).toList().forEach { m ->
            parseToolLenient(m.groupValues[1])?.let { exec(it.first, it.second) }
        }
        work = fenceRe.replace(work, "")

        // ③ 未闭合的 <tool>（AI 长回复被截断）：括号配对尽力提取执行
        val openIdx = work.lastIndexOf("<tool>")
        if (openIdx >= 0 && !work.substring(openIdx).contains("</tool>")) {
            val brace = work.indexOf('{', openIdx)
            if (brace >= 0) {
                val maybe = extractBalancedJson(work, brace)
                    ?: run {
                        val lastBrace = work.lastIndexOf('}')
                        if (lastBrace > brace) work.substring(brace, lastBrace + 1) else null
                    }
                val candidate = maybe ?: autocloseJson(work.substring(brace))
                parseToolLenient(candidate)?.let { exec(it.first, it.second) }
            }
            work = work.substring(0, openIdx)
        }

        // ④ 裸工具名 + JSON（无包裹、可能跨行，兼容 工具名{ / 工具名: { / 工具名：{）
        val bareRe = Regex("(" + KNOWN_TOOLS.sortedByDescending { it.length }.joinToString("|") + ")\\s*[:：]?\\s*\\{")
        val bareMatches = bareRe.findAll(work).toList().reversed()
        for (m in bareMatches) {
            val name = m.groupValues[1]
            val braceIdx = work.indexOf('{', m.range.first)
            if (braceIdx < 0) continue
            val json = extractBalancedJson(work, braceIdx)
            if (json != null) {
                val parsed = parseToolLenient(name + json)
                if (parsed != null && exec(parsed.first, parsed.second)) {
                    val startIdx = m.range.first
                    val endIdx = braceIdx + json.length
                    if (endIdx <= work.length) work = work.removeRange(startIdx, endIdx)
                }
            }
        }

        // ⑤ 截断的裸工具块（结尾没有闭合 }）——自动补全闭合后仍然执行
        val leftover = bareRe.find(work)
        if (leftover != null) {
            val braceIdx = work.indexOf('{', leftover.range.first)
            if (braceIdx >= 0) {
                val closed = autocloseJson(work.substring(braceIdx))
                val parsed = parseToolLenient(leftover.groupValues[1] + closed)
                if (parsed != null && exec(parsed.first, parsed.second)) {
                    work = work.substring(0, leftover.range.first)
                }
            }
        }

        // ⑥ v6.9.43：裸 JSON 块 {"name":"工具名","args":{…}}（名字在花括号内，④⑤的"工具名{"格式抓不到）
        for (m in RAW_JSON_RE.findAll(work).toList().reversed()) {
            val braceIdx = m.range.first
            val json = extractBalancedJson(work, braceIdx)
            if (json != null) {
                val parsed = parseToolLenient(json)
                if (parsed != null && exec(parsed.first, parsed.second)) {
                    work = work.removeRange(braceIdx, braceIdx + json.length)
                }
            } else {
                // 截断的裸 JSON（结尾无闭合 }）：自动补全后仍然执行，整段从正文剔除
                val closed = autocloseJson(work.substring(braceIdx))
                val parsed = parseToolLenient(closed)
                if (parsed != null && exec(parsed.first, parsed.second)) {
                    work = work.substring(0, braceIdx)
                }
            }
        }
        return work
    }

    /** 从 start（须指向 '{'）做括号配对提取完整 JSON；字符串内的括号忽略 */
    private fun extractBalancedJson(s: String, start: Int): String? {
        if (start !in s.indices || s[start] != '{') return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (esc) { esc = false; continue }
            when {
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
        }
        return null
    }

    /**
     * v5.6：把截断/不规范的 JSON 尽力修补成可解析：
     * 去掉尾逗号 → 智能引号转半角 → 自动补全未闭合的引号和括号。
     */
    private fun autocloseJson(s: String): String {
        var inStr = false
        var esc = false
        val stack = ArrayDeque<Char>()
        for (c in s) {
            if (esc) { esc = false; continue }
            if (inStr) {
                when {
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}' -> if (stack.lastOrNull() == '}') stack.removeLast()
                ']' -> if (stack.lastOrNull() == ']') stack.removeLast()
            }
        }
        var out = s.trimEnd()
        if (inStr) out += "\""
        out = out.trimEnd().trimEnd(',')
        while (stack.isNotEmpty()) out += stack.removeLast()
        return out
    }

    /** v5.6：宽松解析 = 严格解析失败后，修复尾逗号 / 智能引号 / 未闭合结构再试一次 */
    private fun parseToolLenient(block: String): Pair<String, JSONObject>? {
        parseToolBlock(block)?.let { return it }
        var t = block.trim()
        if (t.isEmpty()) return null
        var repaired = t.replace(Regex(",\\s*([}\\])])"), "$1")
        parseToolBlock(repaired)?.let { return it }
        repaired = repaired
            .replace('\u201c', '"').replace('\u201d', '"')
            .replace('\u2018', '"').replace('\u2019', '"')
        parseToolBlock(repaired)?.let { return it }
        repaired = autocloseJson(repaired)
        return parseToolBlock(repaired)
    }

    /** 三重容错解析一个工具块，返回 (工具名, args) */
    private fun parseToolBlock(block: String): Pair<String, JSONObject>? {
        val t = block.trim()
        if (t.isEmpty()) return null

        // 形式1/3：JSON 带 name 或 tool 字段
        try {
            val obj = JSONObject(t)
            val n = obj.optString("name", obj.optString("tool", ""))
            if (n in KNOWN_TOOLS) {
                val args = obj.optJSONObject("args")
                    ?: obj.optJSONObject("arguments")
                    ?: obj.optJSONObject("params")
                    ?: JSONObject()
                return n to args
            }
        } catch (_: Exception) { }

        // 形式2：工具名{...} / 工具名: {...}
        val m = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*[:：]?\\s*\\{").find(t)
        if (m != null && m.groupValues[1] in KNOWN_TOOLS) {
            val name = m.groupValues[1]
            val jsonStart = t.indexOf('{')
            try {
                val obj = JSONObject(t.substring(jsonStart))
                val args = obj.optJSONObject("args")
                    ?: obj.optJSONObject("arguments")
                    ?: obj
                return name to args
            } catch (_: Exception) { }
        }
        return null
    }

    /** 结尾是否已收束（句号/叹号/问号/省略号/引号等） */
    private fun endsWell(s: String): Boolean {
        val t = s.trimEnd()
        if (t.isEmpty()) return false
        return t.last() in "。！？…」』”\"!?~）)】"
    }

    // v6.9.42：改为公开——体检报告里的「确认修复」按钮（ChatScreen）直接调工具后，用同一条落库链路出报告气泡
    suspend fun appendToolResult(pid: Long, r: ToolResult) {
        val text = if (r.detail.isBlank()) r.summary else r.summary + "\n\n" + r.detail
        // v5.8：空内容不落库，避免出现没有文字的空白气泡
        if (text.isBlank()) return
        // v6.9.41：体检/打磨等长报告完整落库为 kind="report"（不再截 8000 字），
        // 聊天里渲染成折叠卡片——点击「查看完整报告」打开独立窗口看全文（用户要求：显示不了完整就开窗看）
        if (r.ok && r.detail.length > 600) {
            val full = (r.summary + "\n\n" + r.detail).take(30000)
            Repo.dao.insertMessage(Message(projectId = pid, role = "tool", content = full, kind = "report"))
            return
        }
        Repo.dao.insertMessage(Message(projectId = pid, role = "tool", content = text, kind = if (r.ok) "tool" else "error"))
    }
}
