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
 * 聊天调度中枢。负责编排：
 *  1) 持久化用户消息（会话隔离：每本小说=一个会话，消息按 pid 分库存）
 *  2) 先走 IntentRouter（本地快速路由 → 不浪费 token）
 *  3) 路由未命中 → AI 自然对话；AI 可携带工具块（新旧模型兼容解析）由本地执行
 *  4) 结果与回复写回数据库
 */
object ChatService {

    /** 全部可被 AI 触发的工具名（用于校验与协议说明同步） */
    val KNOWN_TOOLS = setOf(
        "createProject", "addCard", "deleteCard", "writeNextChapter", "rewriteChapter",
        "startAutoWrite", "stopAutoWrite", "generateOutlines", "inspireFromText",
        "readChapter", "listCards", "listChapters", "exportTxt", "deleteProject",
        "contextPreview", "moveChapter", "copyChapter",
        // 专家级写作功能
        "polishChapter", "expandDialogue", "styleRewrite", "hookChapter", "goldenLines",
        "plotBrainstorm", "characterCheck", "consistencyCheck", "nameGen", "genBlurb",
        // 文件系统
        "createFolder", "deleteFolder", "renameFolder",
        "createFile", "writeFile", "appendFile", "readFile",
        "deleteFile", "renameFile", "listFiles"
    )

    const val Welcome = "你好，我是乐乐 —— 你的小说写作 AI 助理 🪶\n\n" +
        "点右上「＋」新建会话即新建一本小说；每个会话的设定、章节、聊天记录完全独立。\n\n" +
        "你可以直接说：\n" +
        "  • 「我想写一本玄幻小说，主角叫林墨…」→ 我自动建书+生成整套设定卡+分章大纲\n" +
        "  • 「写下一章」「自动写作 1 到 600」→ 自动逐章写作并保存\n" +
        "  • 「把这段存到人物设定卡」→ 分类保存\n" +
        "  • 「给我看下一章会注入什么」→ 透明查看上下文\n\n" +
        "先到右上「AI 模型」添加一个（推荐智谱 glm-4-flash，免费）✨"

    /** 对外入口 */
    suspend fun handle(
        context: Context,
        currentPid: Long,
        input: String,
        onProjectChange: (Long) -> Unit
    ) {
        // 1. 持久化用户消息
        Repo.dao.insertMessage(Message(projectId = currentPid, role = "user", content = input))

        // 2. 本地快速路由（确定性指令，不浪费 token）
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

        // 3. AI 对话（可携带工具块，由本地执行）
        val cfg = Repo.dao.activeApi()
        if (cfg == null) {
            val msg = "你还没启用任何 AI 模型。\n点右上「AI 模型」→ 添加一个（推荐智谱 glm-4-flash 免费）→ 选好模型 → 设为启用。"
            Repo.dao.insertMessage(Message(projectId = currentPid, role = "system", content = msg, kind = "text"))
            return
        }
        try {
            val reply = aiChat(cfg, currentPid, input)
            var pid = currentPid
            var deletedCurrent = false
            // 兼容两种格式：<tool>{...}</tool>（新模型）与 ```json {...} ```（老模型）
            val strict = Regex("<tool>\\s*(\\{.*?\\})\\s*</tool>", RegexOption.DOT_MATCHES_ALL)
            val fence = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```")
            val candidates = strict.findAll(reply).map { it.groupValues[1] } +
                fence.findAll(reply).map { it.groupValues[1] }.filter { it.contains("\"name\"") }
            var executed = 0
            val seen = mutableSetOf<String>()
            candidates.forEach { jsonText ->
                try {
                    if (!seen.add(jsonText)) return@forEach
                    val obj = org.json.JSONObject(jsonText)
                    val name = obj.optString("name")
                    if (name.isBlank() || name !in KNOWN_TOOLS) return@forEach
                    val args = obj.optJSONObject("args") ?: org.json.JSONObject()
                    val r = com.lele.novelmaster.tools.Tools.dispatch(pid, name, args, context)
                    if (r != null) {
                        appendToolResult(pid, r)
                        r.newProjectId?.let { np -> pid = np; onProjectChange(np) }
                        if (name == "deleteProject" && r.ok) deletedCurrent = true
                        executed++
                    }
                } catch (_: Exception) { /* 单块失败不影响整体 */ }
            }
            if (deletedCurrent) {
                val ps = Repo.dao.projectsFlow().first()
                pid = ps.firstOrNull()?.id ?: 0L
                onProjectChange(pid)
            }
            val text = reply.replace(strict, "").replace(fence) { m ->
                if (m.groupValues[1].contains("\"name\"")) "" else m.value
            }.trim()
            if (text.isNotBlank()) {
                Repo.dao.insertMessage(Message(projectId = pid, role = "assistant", content = text, kind = "text"))
            } else if (executed == 0) {
                Repo.dao.insertMessage(Message(projectId = pid, role = "assistant", content = reply.take(2000), kind = "text"))
            }
        } catch (e: Exception) {
            Repo.dao.insertMessage(
                Message(
                    projectId = currentPid,
                    role = "system",
                    content = "⚠️ AI 调用失败：${e.message?.take(300)}",
                    kind = "error"
                )
            )
        }
    }

    private suspend fun appendToolResult(pid: Long, r: ToolResult) {
        val text = if (r.detail.isBlank()) r.summary else r.summary + "\n\n" + r.detail
        val kind = if (r.ok) "tool" else "error"
        Repo.dao.insertMessage(Message(projectId = pid, role = "tool", content = text, kind = kind))
    }

    /** AI 自然对话：注入项目核心上下文（token 恒定）+ 工具协议 */
    private suspend fun aiChat(cfg: com.lele.novelmaster.data.ApiConfig, pid: Long, input: String): String {
        return withContext(Dispatchers.IO) {
            val sysText = buildString {
                appendLine(
                    "你是「乐乐」，一个**全自动**小说创作 Agent。工作原则（必须遵守）：\n" +
                        "1) 作者只负责提供灵感与修改意见，所有规划与创作**由你完成**：世界观、人物、主线、支线、伏笔、核心冲突、大纲、章节结构等一律由你主动编写，**绝不反问作者、绝不要求作者制定或补充设定**。\n" +
                        "2) 作者没提到、但小说必需的内容，你**自行创作补全**，并立即用工具保存，然后简短告知补了什么。\n" +
                        "3) 收到灵感后的标准动作（不要问，直接做）：createProject 建书 → 用 addCard 逐张写好并保存设定卡（世界观/主要人物各一张/主线剧情/核心冲突/至少3条伏笔钩子/设定圣经）→ generateOutlines 生成分章大纲 → 然后直接开始写（writeNextChapter 或 startAutoWrite）。整个过程一气呵成。\n" +
                        "4) 作者提修改意见时：先更新对应设定卡或文件，再按需 rewriteChapter 受影响的章节。\n" +
                        "5) 章节之外的资料（角色小传、时间线、考据笔记等）用文件系统工具自主归类：如 createFolder「设定」、createFile「设定/主角-林墨.md」。\n" +
                        "6) 回复简洁：说清楚做了什么、存到了哪里即可，不要长篇大论。\n" +
                        "7) 保持人物、世界观、伏笔的一致性（参考下方核心设定）。"
                )
                appendLine()
                appendLine(
                    "【工具协议】执行操作时在回复中输出工具调用块：\n" +
                        "<tool>{\"name\":\"工具名\",\"args\":{\"参数\":\"值\"}}</tool>\n" +
                        "可一次输出多个块按顺序执行。可用工具：\n" +
                        "— 项目/设定：createProject{title,genre,desc,totalCh,chWords} | addCard{category,name,content}(category:" +
                        CardCategories.all.joinToString("/") + ") | deleteCard{cardId} | listCards{category可空}\n" +
                        "— 写作：writeNextChapter{} | rewriteChapter{index} | startAutoWrite{from,to}(1~600写完自动停) | stopAutoWrite{} | generateOutlines{} | readChapter{index} | listChapters{onlyMissing} | moveChapter{from,to} | copyChapter{index} | contextPreview{} | exportTxt{} | deleteProject{}(仅作者明确说删除会话时)\n" +
                        "— 专家功能(不带index默认处理最新已写章)：polishChapter{index可空}润色 | expandDialogue{index可空}对话扩写 | styleRewrite{index可空,style}风格改写 | hookChapter{index可空}强化章末钩子 | goldenLines{index可空}生成金句 | plotBrainstorm{}推演3条剧情走向 | characterCheck{name}人物一致性体检 | consistencyCheck{}全书体检 | nameGen{kind,count}起名 | genBlurb{}生成发布简介书名\n" +
                        "— 文件系统(路径相对当前会话独立文件夹，会话间完全隔离，绝不会存到别的会话)：createFolder{path} | deleteFolder{path} | renameFolder{path,newName} | createFile{path,content} | writeFile{path,content}(覆盖) | appendFile{path,content} | readFile{path} | deleteFile{path} | renameFile{path,newName} | listFiles{path可空}"
                )
                appendLine()
                if (pid > 0L) {
                    val project = Repo.dao.project(pid)
                    if (project != null) {
                        appendLine("【当前会话/小说】《${project.title}》 类型：${project.genre} 目标 ${project.targetChapters} 章 / 每章 ${project.chapterWordTarget} 字")
                        if (project.description.isNotBlank()) appendLine("简介：${project.description}")
                        val cards = Repo.dao.cards(pid)
                        val coreCards = cards.filter { it.priority == 2 || it.category in CardCategories.KEY_CATS }
                        if (coreCards.isNotEmpty()) {
                            appendLine()
                            appendLine("【核心设定（必保持一致）】")
                            appendLine(Prompts.cardBlock(coreCards))
                        }
                    }
                }
            }
            // 上下文：最近 6 条聊天消息（会话隔离：只取当前 pid 的记录）
            val recent = Repo.dao.messagesFlow(pid).first().takeLast(6)
            val chatMessages = mutableListOf<ChatMsg>()
            chatMessages.add(ChatMsg("system", sysText))
            recent.forEach { m ->
                if (m.kind == "text") chatMessages.add(ChatMsg(when (m.role) { "user" -> "user"; else -> "assistant" }, m.content))
            }
            chatMessages.add(ChatMsg("user", input))
            AiClient.chat(cfg, chatMessages, temperature = 0.85, maxTokens = 2000)
        }
    }
}
