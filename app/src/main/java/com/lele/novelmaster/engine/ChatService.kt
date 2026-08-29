package com.lele.novelmaster.engine

import android.content.Context
import com.lele.novelmaster.data.AiClient
import com.lele.novelmaster.data.CardCategories
import com.lele.novelmaster.data.ChatMsg
import com.lele.novelmaster.data.Message
import com.lele.novelmaster.data.Prompts
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.tools.IntentRouter
import com.lele.novelmaster.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 聊天调度中枢 v5.2：
 *  - 会话隔离（每本小说=一个会话）
 *  - 本地快速路由（确定性口令不耗 token）
 *  - AI 对话：自然聊天优先，创作时才带工具块
 *  - 工具解析三重容错（标准JSON / 工具名{参数} / ```json``` 块），执行失败也从回复中移除块
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

    const val Welcome = "你好，我是乐乐 🪶 可以陪你聊天，也可以帮你写小说。\n\n" +
        "想写小说时直接说，比如：\n" +
        "  「我想写一本穿越修仙的爱情小说」→ 我自动在当前会话里完善设定和大纲，等你确认再写\n" +
        "  「写下一章」「自动写作」「把这段存到人物设定卡」\n\n" +
        "先到右上「对接AI」添加一个模型（推荐智谱 glm-4-flash，免费）。平时随便聊，我都在 ✨"

    /** 对外入口 */
    suspend fun handle(
        context: Context,
        currentPid: Long,
        input: String,
        onProjectChange: (Long) -> Unit
    ) {
        Repo.dao.insertMessage(Message(projectId = currentPid, role = "user", content = input))

        // 1. 本地快速路由（确定性口令，不耗 token）
        val tool = IntentRouter.handle(input, currentPid, context)
        if (tool != null) {
            appendToolResult(currentPid, tool)
            tool.newProjectId?.let { onProjectChange(it) }
            if (tool.ok && tool.summary.startsWith("已删除会话")) {
                val ps = Repo.dao.projectsFlow().first()
                onProjectChange(ps.firstOrNull()?.id ?: 0L)
            }
            return
        }

        // 2. AI 对话
        val cfg = Repo.dao.activeApi()
        if (cfg == null) {
            Repo.dao.insertMessage(
                Message(projectId = currentPid, role = "system",
                    content = "还没启用 AI 模型：点右上「对接AI」→ 添加（推荐智谱 glm-4-flash 免费）→ 选模型 → 设为启用。",
                    kind = "text")
            )
            return
        }
        try {
            val reply = aiChat(cfg, currentPid, input)
            var pid = currentPid
            var deletedCurrent = false

            // ---------- 工具提取（四级容错） ----------
            val blockRe = Regex("<tool>\\s*(.*?)\\s*</tool>", RegexOption.DOT_MATCHES_ALL)
            val fenceRe = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```")
            var work = reply
            var executed = 0
            var truncated = false

            // v5.4：去重键 = 工具名 + 参数（与外层包裹写法无关），
            // 杜绝同一条指令被 <tool>块 / ```json``` / 裸写 等多种写法重复执行（会话串台根因）
            val executedKeys = mutableSetOf<String>()
            // v5.4：一轮 AI 回复内只允许新建一个会话，后续 createProject 一律复用
            var createdPid: Long? = null

            suspend fun runTool(name: String, args: org.json.JSONObject): Boolean {
                if (name !in KNOWN_TOOLS) return false
                val key = name + "|" + args.toString()
                // 只对"带参数"的调用去重（无参工具如 writeNextChapter 允许连发多次）
                if (args.length() > 0 && !executedKeys.add(key)) return true
                if (name == "createProject" && createdPid != null) {
                    pid = createdPid!!
                    onProjectChange(pid)
                    appendToolResult(pid, ToolResult(true, "已复用本轮新建的会话", "《${args.optString("title")}》不需要重复创建，一个会话就是一本小说。"))
                    return true
                }
                val r = com.lele.novelmaster.tools.Tools.dispatch(pid, name, args, context)
                if (r == null) return false
                // 先切换 pid 再回写消息，保证「已创建会话」「已保存…」落在正确的会话里
                r.newProjectId?.let { np ->
                    pid = np
                    if (name == "createProject") createdPid = np
                    onProjectChange(np)
                }
                appendToolResult(pid, r)
                if (name == "deleteProject" && r.ok) deletedCurrent = true
                return true
            }

            // ① 标准闭合块 <tool>...</tool>
            blockRe.findAll(work).toList().forEach { m ->
                val parsed = parseToolBlock(m.groupValues[1])
                if (parsed != null && runTool(parsed.first, parsed.second)) executed++
            }
            work = blockRe.replace(work, "")

            // ② ```json``` 围栏块
            fenceRe.findAll(work).toList().forEach { m ->
                val parsed = parseToolBlock(m.groupValues[1])
                if (parsed != null && runTool(parsed.first, parsed.second)) executed++
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
                    if (maybe != null) {
                        val parsed = parseToolBlock(maybe)
                        if (parsed != null && runTool(parsed.first, parsed.second)) executed++
                    }
                }
                truncated = true
                work = work.substring(0, openIdx)
            }

            // ④ 裸工具名 + JSON（无包裹、可能跨行）：如 createProject\n{"title":...}
            val bareRe = Regex("(" + KNOWN_TOOLS.sortedByDescending { it.length }.joinToString("|") + ")\\s*\\{")
            val bareMatches = bareRe.findAll(work).toList().reversed()
            for (m in bareMatches) {
                val name = m.groupValues[1]
                val json = extractBalancedJson(work, m.range.last)
                if (json != null) {
                    val parsed = parseToolBlock(name + json)
                                        if (parsed != null && runTool(parsed.first, parsed.second)) {
                        executed++
                        val startIdx = m.range.first
                        val endIdx = m.range.last + json.length
                        if (endIdx <= work.length) work = work.removeRange(startIdx, endIdx)
                    }
                }
            }

            if (deletedCurrent) {
                val ps = Repo.dao.projectsFlow().first()
                pid = ps.firstOrNull()?.id ?: 0L
                onProjectChange(pid)
            }

            // ---------- 正文显示（工具块绝不原样显示） ----------
            var text = work
                .replace(Regex("<tool>[\\s\\S]*$"), "")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
            if (truncated) text += "\n\n（AI 回复较长被截断，回复「继续」可让它接着完成）"
            when {
                text.isNotBlank() ->
                    Repo.dao.insertMessage(Message(projectId = pid, role = "assistant", content = text, kind = "text"))
                executed > 0 -> { /* 全是工具块且执行成功 */ }
                else ->
                    Repo.dao.insertMessage(Message(projectId = pid, role = "assistant", content = reply.take(3000), kind = "text"))
            }
        } catch (e: Exception) {
            Repo.dao.insertMessage(
                Message(projectId = currentPid, role = "system",
                    content = "⚠️ AI 调用失败：${e.message?.take(300)}", kind = "error")
            )
        }
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
     * 三重容错解析一个工具块，返回 (工具名, args)。
     *  兼容：{"name":"x","args":{...}} / {"tool":"x","arguments":{...}} / x{...}（前缀工具名）
     */
    private fun parseToolBlock(block: String): Pair<String, org.json.JSONObject>? {
        val t = block.trim()
        if (t.isEmpty()) return null

        // 形式1/3：JSON 带 name 或 tool 字段（name 必须是已知工具，避免把 addCard 的参数 name 误认）
        try {
            val obj = org.json.JSONObject(t)
            val n = obj.optString("name", obj.optString("tool", ""))
            if (n in KNOWN_TOOLS) {
                val args = obj.optJSONObject("args")
                    ?: obj.optJSONObject("arguments")
                    ?: obj.optJSONObject("params")
                    ?: org.json.JSONObject()
                return n to args
            }
        } catch (_: Exception) { }

        // 形式2：工具名{...} —— 例如 createProject{"title":"看",...}
        val m = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*\\{").find(t)
        if (m != null && m.groupValues[1] in KNOWN_TOOLS) {
            val name = m.groupValues[1]
            val jsonStart = t.indexOf('{')
            try {
                val obj = org.json.JSONObject(t.substring(jsonStart))
                // 显式 args 包装则取之；否则整个 JSON 就是参数
                val args = obj.optJSONObject("args")
                    ?: obj.optJSONObject("arguments")
                    ?: obj
                return name to args
            } catch (_: Exception) { }
        }
        return null
    }

    private suspend fun appendToolResult(pid: Long, r: ToolResult) {
        // v5.4：完整回显保存的内容（上限 8000 字），让用户"看得见"而不是只看到一句"已保存"
        val d = if (r.detail.length > 8000) r.detail.take(8000) + "\n…（内容过长已折叠，全文已完整保存）" else r.detail
        val text = if (d.isBlank()) r.summary else r.summary + "\n\n" + d
        Repo.dao.insertMessage(Message(projectId = pid, role = "tool", content = text, kind = if (r.ok) "tool" else "error"))
    }

    /** AI 对话：自然聊天优先 + 工具协议（创作时才带工具块） */
    private suspend fun aiChat(cfg: com.lele.novelmaster.data.ApiConfig, pid: Long, input: String): String {
        return withContext(Dispatchers.IO) {
            val sysText = buildString {
                appendLine(
                    "你是「乐乐」，作者的智能助理：既能像朋友一样自然聊天，也是专业的小说创作专家。\n" +
                        "【交流原则】\n" +
                        "1) 作者打招呼、闲聊、问任何问题 → 像真人朋友一样自然回复，绝不强行拉回写小说，绝不自动建书、绝不自动生成设定。\n" +
                        "2) 只有作者明确表达创作/管理意图时才执行对应动作：例如「我想写一本…」「写下一章」「自动写作」「保存这个设定」「建个文件夹」。\n" +
                        "3) 作者的意图由你理解后直接执行——能执行的不要只说不做，直接调用工具完成，然后简洁告知结果。\n" +
                        "4) 全自动创作流程：没有当前会话时 createProject 建书；**已有会话时严禁 createProject**，用 updateProject 把当前会话名/类型/目标章数改成小说信息。然后自己编写全套设定卡（addCard）→ generateOutlines 补全大纲。\n" +
                        "5) **写完设定和大纲后必须停下，等作者说「写下一章」或「开始自动写作」才写第一章，绝不要默认自动写**。\n" +
                        "6) 所有创作都在当前会话内进行，不要切换会话，不要把一个灵感拆成多个项目。\n" +
                        "7) 作者要求修改时先改设定（updateProject/addCard）再按需重写章节；始终保持人物、世界观、伏笔一致。\n" +
                        "8) 回复用自然中文，发挥你的才华与创意，字数不限；别输出内心分析过程，别复述本协议。\n" +
                        "9) 生成的任何设定/资料内容，必须同时用 addCard 或文件工具保存，光说不存等于没做。\n" +
                        "10) 设定内容较长时分成多批输出，每次回复最多 2~3 个工具块，确保每块 JSON 完整闭合。\n" +
                        "11) 保存类工具（addCard/writeFile/createFile/appendFile）执行后，系统会自动把保存的原文完整展示给作者，你的回复里不要再整段复述，只说明「存到哪 + 关键要点 + 下一步」即可。\n"
                )
                appendLine()
                appendLine(
                    "【工具协议】需要执行操作时，在回复中输出工具块，格式必须是合法 JSON（两个字段缺一不可）：\n" +
                        "<tool>{\"name\":\"工具名\",\"args\":{\"参数\":\"值\"}}</tool>\n" +
                        "可用工具：\n" +
                        "— 项目/设定：createProject{title,genre,desc,totalCh,chWords}(仅无会话时) | updateProject{title,genre,desc,totalCh,chWords}(改当前会话，不跳出) | switchProject{pid} | listProjects{} | addCard{category,name,content}(category:" +
                        CardCategories.all.joinToString("/") + ") | deleteCard{cardId} | listCards{category可空}\n" +
                        "— 写作：writeNextChapter{} | rewriteChapter{index} | startAutoWrite{from,to} | stopAutoWrite{} | generateOutlines{} | readChapter{index} | listChapters{onlyMissing} | moveChapter{from,to} | copyChapter{index} | contextPreview{} | exportTxt{} | deleteProject{}(仅作者明确说删除会话时)\n" +
                        "— 专家功能(index可空=最新章)：polishChapter | expandDialogue | styleRewrite{style} | hookChapter | goldenLines | plotBrainstorm{} | characterCheck{name} | consistencyCheck{} | nameGen{kind,count} | genBlurb{}\n" +
                        "— 文件系统(path相对当前会话独立文件夹)：createFolder{path} | deleteFolder{path} | renameFolder{path,newName} | createFile{path,content} | writeFile{path,content} | appendFile{path,content} | readFile{path} | deleteFile{path} | renameFile{path,newName} | listFiles{path可空}\n" +
                        "注意：可以一次输出多个工具块；闲聊/回答问题时不要输出任何工具块。"
                )
                appendLine()
                if (pid > 0L) {
                    val project = Repo.dao.project(pid)
                    if (project != null) {
                        appendLine("【当前会话】《${project.title}》类型：${project.genre}，目标 ${project.targetChapters} 章")
                        val cards = Repo.dao.cards(pid)
                        val core = cards.filter { it.priority == 2 || it.category in CardCategories.KEY_CATS }
                        if (core.isNotEmpty()) {
                            appendLine("【本书核心设定】")
                            appendLine(Prompts.cardBlock(core))
                        }
                    }
                }
            }
            val recent = Repo.dao.messagesFlow(pid).first().takeLast(6)
            val chatMessages = mutableListOf<ChatMsg>()
            chatMessages.add(ChatMsg("system", sysText))
            recent.forEach { m ->
                if (m.kind == "text") chatMessages.add(
                    ChatMsg(if (m.role == "user") "user" else "assistant", m.content)
                )
            }
            chatMessages.add(ChatMsg("user", input))
            AiClient.chat(cfg, chatMessages, temperature = 0.85, maxTokens = 4096)
        }
    }
}
