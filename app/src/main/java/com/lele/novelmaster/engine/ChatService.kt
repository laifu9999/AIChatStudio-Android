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

    /** 全部可被 AI 触发的工具名 */
    val KNOWN_TOOLS = setOf(
        "createProject", "switchProject", "updateProject", "listProjects", "addCard", "deleteCard", "writeNextChapter", "rewriteChapter",
        "startAutoWrite", "stopAutoWrite", "generateOutlines", "inspireFromText",
        "readChapter", "listCards", "listChapters", "exportTxt", "deleteProject",
        "contextPreview", "moveChapter", "copyChapter",
        "polishChapter", "expandDialogue", "styleRewrite", "hookChapter", "goldenLines",
        "plotBrainstorm", "characterCheck", "consistencyCheck", "nameGen", "genBlurb",
        "createFolder", "deleteFolder", "renameFolder",
        "createFile", "writeFile", "appendFile", "readFile",
        "deleteFile", "renameFile", "listFiles"
    )

    /** v5.7：单次提问最多自动续跑几轮 */
    private const val MAX_ROUNDS = 4

    private val BLOCK_RE = Regex("<tool>\\s*(.*?)\\s*</tool>", RegexOption.DOT_MATCHES_ALL)

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
        currentPid: Long,
        input: String,
        onProjectChange: (Long) -> Unit,
        onStream: suspend (String) -> Unit = {}
    ): Long? {
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

        // 2. AI 对话
        val cfg = Repo.dao.activeApi()
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

        suspend fun runTool(rawName: String, rawArgs: JSONObject): Boolean {
            val name = rawName.trim()
            val args = cleanArgs(rawArgs)
            if (name !in KNOWN_TOOLS) return false
            val key = name + "|" + args.toString()
            // 只对"带参数"的调用去重（无参工具如 writeNextChapter 允许连发多次）
            if (args.length() > 0 && !executedKeys.add(key)) return true

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
                    if (p != null && runTool(p.first, p.second)) executed++
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
            var i = v.indexOf("<tool")
            if (i >= 0) v = v.substring(0, i)
            i = v.indexOf("```")
            if (i >= 0) v = v.substring(0, i)
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
                    m.kind == "text" ->
                        msgs.add(ChatMsg(if (m.role == "user") "user" else "assistant", m.content.take(8000)))
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
                    if (ok) executed++
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

                // ---------- 判断是否要自动续跑 ----------
                val truncatedByLength = stopReason == "length"
                val unterminated = raw.lastIndexOf("<tool>") > raw.lastIndexOf("</tool>")
                val unfinished = text.length > 120 && !endsWell(text) && executed == 0
                val needMore = (truncatedByLength || unterminated || unfinished) && round < MAX_ROUNDS

                if (!needMore) break

                // 组下一轮：把已经写出来的交给 AI 当上下文，让它接着写
                msgs.add(ChatMsg("assistant", text.ifBlank { "（本轮已执行保存操作）" }))
                msgs.add(
                    ChatMsg(
                        "user",
                        "继续。严格接着上一条的最后一句话往下输出，不要重复已输出的内容，不要写「（续）」「接上文」「未完待续」这类话，直接把剩下的部分完整写完。"
                    )
                )
                onStream(shown.toString() + "\n\n…（内容较长，正在自动继续输出）")
                delay(150)
            }

            // v6.3：戳穿"口头保存"——本轮没执行任何工具，文字里却说已保存/已写好
            val claimSaved = executed == 0 && Regex(
                "已保存|已存好|保存好了|已经保存|已写入|已建立|已生成设定卡|已创建设定|已经写好|已存到"
            ).containsMatchIn(shown.toString())
            if (claimSaved) {
                Repo.dao.insertMessage(
                    Message(projectId = pid, role = "system", kind = "error",
                        content = "⚠️ 检测到本轮只是**口头说已保存**，实际没有执行任何保存工具——内容并没有真正存下来。\n" +
                            "请用 addCard（设定卡）或 writeFile/createFile（文件）的工具块重新保存一遍。")
                )
                // 再给 AI 一次机会：明确要求用工具块补存
                msgs.add(ChatMsg("assistant", shown.toString().take(3000)))
                msgs.add(ChatMsg("user", "你刚才只是口头说保存，没有真正调用工具。现在请用工具块把上面的内容全部保存（设定用 addCard，文件用 writeFile），一个工具块一条，立刻执行。"))
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
                "· 严禁「（略）」「（以下省略）」「未完待续」「由于篇幅限制」这类偷懒输出，也不要解释自己省略了什么。\n" +
                "· 每个工具块的 JSON 必须完整闭合，content 里换行用 \\n、引号用 \\\" 转义，不要用中文引号。\n"
        )
        if (continuing) {
            appendLine()
            if (resumeMid) {
                appendLine("【本次是「继续」指令·半截续写】你上一条在半截被打断，严格接着最后一句话往下输出完，不要重复、不要重新开头。")
            } else {
                appendLine("【本次是「继续」指令·下一步】对照下方「已保存的设定卡清单」：设定/分章大纲没建全 → 接着建全；已建全 → 调用 writeNextChapter 写下一章完整正文（不是梗概）。已完成的部分严禁重做，严禁一口气连写多章。")
            }
        }
        appendLine()
        appendLine(
            "【工具协议】需要执行操作时，在回复中输出工具块，格式必须是合法 JSON（两个字段缺一不可）：\n" +
                "<tool>{\"name\":\"工具名\",\"args\":{\"参数\":\"值\"}}</tool>\n" +
                "可用工具：\n" +
                "— 项目/设定：createProject{title,genre,desc,totalCh,chWords}(仅无会话时) | updateProject{title,genre,desc,totalCh,chWords}(改当前会话，不跳出) | switchProject{pid} | listProjects{} | addCard{category,name,content}(category:" +
                CardCategories.all.joinToString("/") + ") | deleteCard{cardId} | listCards{category可空}\n" +
                "— 写作：writeNextChapter{} | rewriteChapter{index} | startAutoWrite{from,to} | stopAutoWrite{} | generateOutlines{} | readChapter{index} | listChapters{onlyMissing} | moveChapter{from,to} | copyChapter{index} | contextPreview{} | exportTxt{} | deleteProject{}(仅作者明确说删除会话时)\n" +
                "— 专家功能(index可空=最新章)：polishChapter | expandDialogue | styleRewrite{style} | hookChapter | goldenLines | plotBrainstorm{} | characterCheck{name} | consistencyCheck{}(全书体检·只列矛盾) | nameGen{kind,count} | genBlurb{}\n" +
                "— 一致性体检/修复：chapterSelfCheck{index}(单章自检·矛盾自动修正) | fullSelfCheck{}(全书逐章自检修复) | selfCheckProgress{}(自检进度) | undoSelfCheck{index}(撤销最近一次修改) | supplementChapter{index}(按大纲补写缺失剧情) | cardsCheck{}(设定体检·检查全部设定卡+分章大纲矛盾并自动修复) | cardsCheckReport{}(查看最近一次设定体检报告·只读) | setChapterOutline{index,text}(手动修改第N章大纲并同步镜像卡·text传新大纲全文)\n" +
                "— 伏笔/支线：foreshadowCheck{}(伏笔体检·埋收状态审计) | markHookRecovered{name}(标记伏笔已回收) | subplotCheck{}(支线体检·推进状态与收束建议)\n" +
                "— 文件系统(path相对当前会话独立文件夹)：createFolder{path} | deleteFolder{path} | renameFolder{path,newName} | createFile{path,content} | writeFile{path,content} | appendFile{path,content} | readFile{path} | deleteFile{path} | renameFile{path,newName} | listFiles{path可空}\n" +
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

    private suspend fun appendToolResult(pid: Long, r: ToolResult) {
        // v5.4：完整回显保存的内容（上限 8000 字），让用户"看得见"而不是只看到一句"已保存"
        val d = if (r.detail.length > 8000) r.detail.take(8000) + "\n…（内容过长已折叠，全文已完整保存）" else r.detail
        val text = if (d.isBlank()) r.summary else r.summary + "\n\n" + d
        // v5.8：空内容不落库，避免出现没有文字的空白气泡
        if (text.isBlank()) return
        Repo.dao.insertMessage(Message(projectId = pid, role = "tool", content = text, kind = if (r.ok) "tool" else "error"))
    }
}
